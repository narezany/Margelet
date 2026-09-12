package org.telegram.margelet;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Обфускация MTProto — та её половина, что нужна прокси.
 *
 * Разобрано не по памяти, а по исходнику самого клиента: TMessagesProj/jni/
 * tgnet/Connection.cpp, место, где он собирает первые 64 байта соединения.
 * Оттуда же взято и то, чего по документации не узнать: НОМЕР ДАТА-ЦЕНТРА
 * КЛИЕНТ ПИШЕТ В ЗАГОЛОВОК ТОЛЬКО ТОГДА, КОГДА ИДЁТ ЧЕРЕЗ MTProto-ПРОКСИ
 * С СЕКРЕТОМ (`if (useSecret != 0)`). При обычном соединении и через SOCKS
 * на этом месте случайный мусор.
 *
 * Из-за одной этой строки прокси обязан быть именно MTProto-прокси: иначе
 * узнать, в какой дата-центр направлять соединение, попросту неоткуда.
 *
 * Класс нарочно не знает ни про андроид, ни про сеть: так его можно
 * выполнить на столе и проверить, что расчёт сходится с клиентским.
 */
public class MargeletObfuscation {

    /** Длина начального пакета: 64 байта, из них последние 8 зашифрованы. */
    public static final int HEAD = 64;

    /** Метки протокола в байтах 56..60 расшифрованного заголовка. */
    private static final int TAG_ABRIDGED = 0xefefefef;
    private static final int TAG_INTERMEDIATE = 0xeeeeeeee;
    private static final int TAG_PADDED = 0xdddddddd;

    /** Поток AES-CTR: один на направление, состояние между вызовами живёт. */
    public static final class Stream {
        private final Cipher cipher;

        Stream(byte[] key, byte[] iv) {
            try {
                cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
            } catch (Exception e) {
                throw new IllegalStateException("нет AES/CTR", e);
            }
        }

        /**
         * Шифрование и расшифровка в CTR — одно и то же действие, поэтому
         * метод один. Состояние счётчика продолжается от вызова к вызову:
         * поток нельзя обрабатывать кусками независимо.
         */
        public byte[] run(byte[] data, int offset, int length) {
            return cipher.update(data, offset, length);
        }

        public byte[] run(byte[] data) {
            return run(data, 0, data.length);
        }
    }

    /** Что удалось прочитать из начального пакета клиента. */
    public static final class Head {
        /** Поток, которым клиент шифрует то, что шлёт нам. */
        public final Stream incoming;
        /** Поток, которым мы шифруем то, что шлём клиенту. */
        public final Stream outgoing;
        /** Номер дата-центра. Отрицательный — медийное соединение. */
        public final int datacenter;
        /** Метка протокола: abridged, intermediate или padded. */
        public final int tag;

        Head(Stream incoming, Stream outgoing, int datacenter, int tag) {
            this.incoming = incoming;
            this.outgoing = outgoing;
            this.datacenter = datacenter;
            this.tag = tag;
        }

        public boolean valid() {
            return tag == TAG_ABRIDGED || tag == TAG_INTERMEDIATE || tag == TAG_PADDED;
        }

        public int plainDatacenter() {
            final int number = Math.abs(datacenter);
            // Тестовые дата-центры клиент нумерует с десяти тысяч.
            return number >= 10000 ? number - 10000 : number;
        }

        public boolean media() {
            return datacenter < 0;
        }
    }

    /**
     * Ключ так, как его считает клиент: SHA256 от тридцати двух байт вместе
     * с секретом, а вектор — следующие шестнадцать байт БЕЗ хеша. Второе
     * легко упустить: в исходнике вектор берут из той же переменной уже
     * после того, как в её начало положили хеш.
     */
    private static byte[][] derive(byte[] material, byte[] secret) {
        final byte[] key = new byte[32];
        final byte[] iv = new byte[16];
        System.arraycopy(material, 0, key, 0, 32);
        System.arraycopy(material, 32, iv, 0, 16);
        if (secret != null && secret.length > 0) {
            try {
                final MessageDigest sha = MessageDigest.getInstance("SHA-256");
                sha.update(key);
                sha.update(secret, 0, Math.min(16, secret.length));
                System.arraycopy(sha.digest(), 0, key, 0, 32);
            } catch (Exception e) {
                throw new IllegalStateException("нет SHA-256", e);
            }
        }
        return new byte[][]{key, iv};
    }

    /** Прочитать начальный пакет клиента, пришедший к нам как к прокси. */
    public static Head read(byte[] head, byte[] secret) {
        if (head == null || head.length < HEAD) {
            return null;
        }
        // То, чем клиент шифрует нам, — для нас расшифровка.
        final byte[] straight = new byte[48];
        System.arraycopy(head, 8, straight, 0, 48);
        final byte[][] in = derive(straight, secret);

        // И наоборот: тот же кусок задом наперёд.
        final byte[] reversed = new byte[48];
        for (int i = 0; i < 48; i++) {
            reversed[i] = head[55 - i];
        }
        final byte[][] out = derive(reversed, secret);

        final Stream incoming = new Stream(in[0], in[1]);
        final Stream outgoing = new Stream(out[0], out[1]);

        final byte[] plain = incoming.run(head, 0, HEAD);
        final int tag = int32(plain, 56);
        final int datacenter = (short) ((plain[60] & 0xff) | ((plain[61] & 0xff) << 8));
        return new Head(incoming, outgoing, datacenter, tag);
    }

    /**
     * Собрать начальный пакет для дата-центра — то же самое, что делает
     * клиент, только без секрета: дата-центру секрет не предъявляют.
     *
     * @return пакет, который надо отправить, и два потока: первым мы шифруем
     *         то, что шлём, вторым расшифровываем то, что приходит.
     */
    public static Object[] create(int tag) {
        final SecureRandom random = new SecureRandom();
        final byte[] head = new byte[HEAD];
        while (true) {
            random.nextBytes(head);
            if ((head[0] & 0xff) == 0xef) {
                continue;
            }
            final int first = int32(head, 0);
            final int second = int32(head, 4);
            if (first == 0x44414548 || first == 0x54534f50 || first == 0x20544547
                    || first == 0x4954504f || first == 0xeeeeeeee || first == 0xdddddddd
                    || first == 0x02010316 || second == 0) {
                continue;
            }
            break;
        }
        final byte mark = (byte) (tag == TAG_INTERMEDIATE ? 0xee
                : tag == TAG_PADDED ? 0xdd : 0xef);
        head[56] = head[57] = head[58] = head[59] = mark;

        final byte[] straight = new byte[48];
        System.arraycopy(head, 8, straight, 0, 48);
        final byte[][] out = derive(straight, null);

        final byte[] reversed = new byte[48];
        for (int i = 0; i < 48; i++) {
            reversed[i] = head[55 - i];
        }
        final byte[][] in = derive(reversed, null);

        final Stream outgoing = new Stream(out[0], out[1]);
        final Stream incoming = new Stream(in[0], in[1]);

        // Клиент шифрует весь заголовок, а отправляет открытым всё, кроме
        // последних восьми байт. Повторяем в точности.
        final byte[] encrypted = outgoing.run(head, 0, HEAD);
        final byte[] packet = new byte[HEAD];
        System.arraycopy(head, 0, packet, 0, 56);
        System.arraycopy(encrypted, 56, packet, 56, 8);

        return new Object[]{packet, outgoing, incoming};
    }

    private static int int32(byte[] data, int at) {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8)
                | ((data[at + 2] & 0xff) << 16) | ((data[at + 3] & 0xff) << 24);
    }

    /** Секрет прокси: шестнадцать случайных байт, как у всех остальных. */
    public static byte[] newSecret() {
        final byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    public static String hex(byte[] data) {
        final StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }
}
