import org.telegram.margelet.MargeletObfuscation;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Проверка обфускации на столе.
 *
 * Клиента здесь изображает отдельный код, переписанный с Connection.cpp
 * построчно и НЕ пользующийся проверяемым классом: иначе проверка
 * повторяла бы за кодом, а не проверяла его.
 */
public class ObfuscationCheck {

    static int broken = 0;

    static void same(Object got, Object want, String about) {
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            System.out.println(" - " + about + ": ждали " + want + ", вышло " + got);
            broken++;
        }
    }

    /** Клиент: ровно то, что делает Connection.cpp при первом пакете. */
    static byte[] clientHead(byte[] secret, int datacenterId, boolean media, byte mark)
            throws Exception {
        final SecureRandom random = new SecureRandom();
        final byte[] bytes = new byte[64];
        while (true) {
            random.nextBytes(bytes);
            if ((bytes[0] & 0xff) == 0xef) continue;
            final int v1 = le(bytes, 0), v2 = le(bytes, 4);
            if (v1 == 0x44414548 || v1 == 0x54534f50 || v1 == 0x20544547
                    || v1 == 0x4954504f || v1 == 0xeeeeeeee || v1 == 0xdddddddd
                    || v1 == 0x02010316 || v2 == 0) continue;
            break;
        }
        bytes[56] = bytes[57] = bytes[58] = bytes[59] = mark;

        final short id = (short) (media ? -datacenterId : datacenterId);
        bytes[60] = (byte) (id & 0xff);
        bytes[61] = (byte) ((id >> 8) & 0xff);

        final byte[] temp = new byte[48];
        System.arraycopy(bytes, 8, temp, 0, 48);
        final Cipher encrypt = ctr(withSecret(temp, secret), Arrays.copyOfRange(temp, 32, 48));

        final byte[] out = encrypt.update(bytes, 0, 64);
        final byte[] packet = new byte[64];
        System.arraycopy(bytes, 0, packet, 0, 56);
        System.arraycopy(out, 56, packet, 56, 8);
        return packet;
    }

    static byte[] withSecret(byte[] material, byte[] secret) throws Exception {
        final byte[] key = Arrays.copyOfRange(material, 0, 32);
        if (secret == null || secret.length == 0) return key;
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(key);
        sha.update(secret, 0, Math.min(16, secret.length));
        return sha.digest();
    }

    static Cipher ctr(byte[] key, byte[] iv) throws Exception {
        final Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    static int le(byte[] d, int at) {
        return (d[at] & 0xff) | ((d[at + 1] & 0xff) << 8)
                | ((d[at + 2] & 0xff) << 16) | ((d[at + 3] & 0xff) << 24);
    }

    public static void main(String[] args) throws Exception {
        final byte[] secret = MargeletObfuscation.newSecret();

        // Обычное соединение: пятый дата-центр, метка abridged.
        MargeletObfuscation.Head head = MargeletObfuscation.read(
                clientHead(secret, 5, false, (byte) 0xef), secret);
        same(head.valid(), true, "метка протокола не признана");
        same(head.plainDatacenter(), 5, "номер дата-центра");
        same(head.media(), false, "соединение сочли медийным");

        // Медийное соединение второго дата-центра: клиент пишет номер со знаком.
        head = MargeletObfuscation.read(clientHead(secret, 2, true, (byte) 0xee), secret);
        same(head.plainDatacenter(), 2, "номер медийного дата-центра");
        same(head.media(), true, "медийное соединение не распознано");

        // Тестовый стенд: номера начинаются с десяти тысяч.
        head = MargeletObfuscation.read(clientHead(secret, 10003, false, (byte) 0xdd), secret);
        same(head.plainDatacenter(), 3, "номер тестового дата-центра");

        // Чужой секрет — расшифровка не сойдётся, и это должно быть ВИДНО.
        head = MargeletObfuscation.read(clientHead(secret, 1, false, (byte) 0xef),
                MargeletObfuscation.newSecret());
        same(head.valid(), false, "заголовок с чужим секретом сочли своим");

        // Поток в обе стороны: то, что мы зашифровали клиенту, он расшифрует.
        final byte[] clientPacket = clientHead(secret, 4, false, (byte) 0xef);
        head = MargeletObfuscation.read(clientPacket, secret);
        final byte[] temp = new byte[48];
        for (int i = 0; i < 48; i++) temp[i] = clientPacket[55 - i];
        final Cipher clientDecrypt = ctr(withSecret(temp, secret), Arrays.copyOfRange(temp, 32, 48));
        final byte[] message = "привет из прокси".getBytes("UTF-8");
        final byte[] sent = head.outgoing.run(message);
        same(new String(clientDecrypt.update(sent), "UTF-8"), "привет из прокси",
                "клиент не расшифровал то, что послал прокси");

        // Заголовок для дата-центра: метка на месте, номера там нет.
        final Object[] made = MargeletObfuscation.create(0xefefefef);
        final byte[] packet = (byte[]) made[0];
        same(packet.length, 64, "длина заголовка для дата-центра");
        final MargeletObfuscation.Head asServer = MargeletObfuscation.read(packet, null);
        same(asServer.valid(), true, "дата-центр не признал бы нашу метку");

        if (broken > 0) {
            System.out.println("СЛОМАНО: " + broken);
            System.exit(1);
        }
        System.out.println("обфускация сходится с клиентской");
    }
}
