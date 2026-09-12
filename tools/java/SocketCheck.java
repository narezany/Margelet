import org.telegram.margelet.MargeletSocket;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Проверка кадров websocket на столе.
 *
 * Разбор здесь написан отдельно от того, что проверяем, и по описанию из
 * RFC 6455, а не по нашему коду: проверка, повторяющая за кодом, сойдётся
 * с ним и в ошибке тоже.
 */
public class SocketCheck {

    static int broken = 0;

    static void same(Object got, Object want, String about) {
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            System.out.println(" - " + about + ": ждали " + want + ", вышло " + got);
            broken++;
        }
    }

    /** Разбор кадра от клиента: маска обязательна, длина в трёх видах. */
    static byte[] unframe(byte[] frame) {
        int at = 0;
        final int first = frame[at++] & 0xff;
        same(first, 0x82, "первый байт кадра");
        final int second = frame[at++] & 0xff;
        same((second & 0x80) != 0, true, "кадр клиента обязан быть с маской");
        long length = second & 0x7f;
        if (length == 126) {
            length = ((frame[at++] & 0xffL) << 8) | (frame[at++] & 0xffL);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (frame[at++] & 0xffL);
            }
        }
        final byte[] mask = Arrays.copyOfRange(frame, at, at + 4);
        at += 4;
        final byte[] payload = new byte[(int) length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) (frame[at + i] ^ mask[i % 4]);
        }
        same(at + length, frame.length, "длина кадра не сошлась с заявленной");
        return payload;
    }

    public static void main(String[] args) {
        // Пример прямо из RFC 6455: по этому ключу сервер обязан ответить так.
        same(MargeletSocket.accept("dGhlIHNhbXBsZSBub25jZQ=="),
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "ответ на ключ рукопожатия");

        final SecureRandom random = new SecureRandom();
        // Длины взяты по границам, где меняется вид заголовка: 125/126 и
        // 65535/65536. Ошибки в кадрах живут ровно на этих переходах.
        for (int length : new int[]{0, 1, 64, 125, 126, 127, 1000, 65535, 65536, 70000}) {
            final byte[] data = new byte[length];
            random.nextBytes(data);
            final byte[] frame = MargeletSocket.frame(data, 0, length, random);
            same(Arrays.equals(unframe(frame), data), true,
                    "кадр длиной " + length + " не вернулся целым");
        }

        // Кусок из середины большого массива: смещение не должно теряться.
        final byte[] big = new byte[500];
        random.nextBytes(big);
        final byte[] part = MargeletSocket.frame(big, 100, 200, random);
        same(Arrays.equals(unframe(part), Arrays.copyOfRange(big, 100, 300)), true,
                "кадр из середины массива");

        if (broken > 0) {
            System.out.println("СЛОМАНО: " + broken);
            System.exit(1);
        }
        System.out.println("кадры websocket собираются и разбираются верно");
    }
}
