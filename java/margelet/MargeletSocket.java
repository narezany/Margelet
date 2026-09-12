package org.telegram.margelet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocketFactory;

/**
 * Websocket ровно в том объёме, в каком он нужен прокси.
 *
 * Своя реализация, а не библиотека, по двум причинам. Первая: тащить в
 * телеграм ещё одну зависимость ради двух видов кадров — дорого. Вторая и
 * главная: здесь надо не «отправить сообщение», а гнать поток байт как есть,
 * и обёртки высокого уровня для этого неудобны.
 *
 * Сделано только то, что нужно: рукопожатие, двоичные кадры, ответ на «пинг»
 * и закрытие. Кадров от сервера с маской не бывает, и мы их не ждём.
 */
public class MargeletSocket {

    /** По этому числу сервер считает ответ на рукопожатие. Из RFC 6455. */
    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    private MargeletSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Открыть защищённый websocket. Бросает, если сервер не согласился. */
    public static MargeletSocket open(String host, String path, int timeoutMs) throws IOException {
        final Socket raw = SSLSocketFactory.getDefault().createSocket();
        raw.connect(new java.net.InetSocketAddress(host, 443), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        final MargeletSocket socket = new MargeletSocket(raw);
        socket.handshake(host, path);
        raw.setSoTimeout(0);
        return socket;
    }

    private void handshake(String host, String path) throws IOException {
        final byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        final String key = Base64.getEncoder().encodeToString(nonce);

        final String request =
                "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                // Телеграм отдаёт поток MTProto только под этим именем.
                + "Sec-WebSocket-Protocol: binary\r\n"
                + "Origin: https://" + host + "\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        final String answer = readHeaders();
        if (!answer.startsWith("HTTP/1.1 101")) {
            throw new IOException("сервер не поднял websocket: "
                    + answer.split("\r\n")[0]);
        }
        final String expected = accept(key);
        if (!answer.toLowerCase().contains("sec-websocket-accept: "
                + expected.toLowerCase())) {
            throw new IOException("сервер ответил чужим ключом");
        }
    }

    /** Ответ на ключ так, как его считает сервер по RFC 6455. */
    public static String accept(String key) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update((key + MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha.digest());
        } catch (Exception e) {
            throw new IllegalStateException("нет SHA-1", e);
        }
    }

    private String readHeaders() throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int last = 0;
        while (buffer.size() < 8192) {
            final int one = in.read();
            if (one < 0) {
                throw new IOException("соединение закрылось на рукопожатии");
            }
            buffer.write(one);
            if (one == '\n' && last == '\n') {
                break;
            }
            if (one != '\r') {
                last = one;
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
    }

    /** Отправить двоичный кадр. Кадры от клиента всегда с маской. */
    public synchronized void send(byte[] data, int offset, int length) throws IOException {
        out.write(frame(data, offset, length, random));
        out.flush();
    }

    /**
     * Собрать кадр. Вынесено отдельно и без сети — чтобы можно было
     * проверить сборку и разбор друг об друга, не поднимая сервера.
     */
    public static byte[] frame(byte[] data, int offset, int length, SecureRandom random) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(length + 14);
        out.write(0x82);                       // последний кадр, двоичный
        final byte[] mask = new byte[4];
        random.nextBytes(mask);
        if (length < 126) {
            out.write(0x80 | length);
        } else if (length < 65536) {
            out.write(0x80 | 126);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) length >> shift) & 0xff));
            }
        }
        out.write(mask, 0, 4);
        for (int i = 0; i < length; i++) {
            out.write((data[offset + i] ^ mask[i % 4]) & 0xff);
        }
        return out.toByteArray();
    }

    /**
     * Прочитать один кадр с данными. Служебные кадры обрабатываются здесь же
     * и наружу не отдаются: «пинг» получает ответ, «закрыть» — исключение.
     *
     * @return содержимое кадра или null, если поток кончился.
     */
    public byte[] receive() throws IOException {
        while (true) {
            final int first = in.read();
            if (first < 0) {
                return null;
            }
            final int opcode = first & 0x0f;
            final int second = in.read();
            if (second < 0) {
                return null;
            }
            // Маски от сервера не бывает; если пришла — считаем поток битым.
            final boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = (read() << 8) | read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | read();
                }
            }
            final byte[] mask = new byte[4];
            if (masked) {
                readFully(mask, 4);
            }
            final byte[] payload = new byte[(int) length];
            readFully(payload, payload.length);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            if (opcode == 0x8) {
                throw new IOException("сервер закрыл websocket");
            }
            if (opcode == 0x9) {
                sendControl(0xA, payload);     // «пинг» — отвечаем «понг»
                continue;
            }
            if (opcode == 0xA) {
                continue;
            }
            return payload;
        }
    }

    private synchronized void sendControl(int opcode, byte[] payload) throws IOException {
        final byte[] mask = new byte[4];
        random.nextBytes(mask);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(0x80 | opcode);
        buffer.write(0x80 | payload.length);
        buffer.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            buffer.write((payload[i] ^ mask[i % 4]) & 0xff);
        }
        out.write(buffer.toByteArray());
        out.flush();
    }

    private int read() throws IOException {
        final int one = in.read();
        if (one < 0) {
            throw new IOException("поток кончился посреди кадра");
        }
        return one;
    }

    private void readFully(byte[] into, int length) throws IOException {
        int done = 0;
        while (done < length) {
            final int step = in.read(into, done, length - done);
            if (step < 0) {
                throw new IOException("поток кончился посреди кадра");
            }
            done += step;
        }
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
