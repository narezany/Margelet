package org.telegram.margelet;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Встроенный прокси: MTProto снаружи, websocket внутри.
 *
 * Зачем. Там, где телеграм закрыт, обычное соединение до дата-центра не
 * доходит, а то же самое внутри websocket-а к их же домену — доходит. Чужих
 * серверов в схеме нет: трафик идёт в те же дата-центры телеграма, и ключи
 * от переписки по-прежнему только у собеседников. Мы лишь меняем обёртку.
 *
 * Почему именно MTProto-прокси, а не socks, хотя socks был бы вдвое проще:
 * номер дата-центра клиент кладёт в заголовок ТОЛЬКО при работе через
 * MTProto-прокси с секретом (см. MargeletObfuscation). Через socks узнать,
 * куда направлять соединение, было бы неоткуда.
 *
 * Прокси слушает только 127.0.0.1 — снаружи к нему не подключиться.
 */
public class MargeletProxy {

    /** Дата-центры телеграма в вебе зовутся планетами. */
    private static final String[] PLANETS = {
            "pluto", "venus", "aurora", "vesta", "flora"
    };

    private static ServerSocket server;
    private static Thread accepting;
    private static volatile int port;
    private static volatile boolean working;

    public static synchronized boolean running() {
        return working;
    }

    public static synchronized int port() {
        return port;
    }

    /** Секрет прокси. Живёт между запусками: иначе настройка протухала бы. */
    public static synchronized byte[] secret() {
        final String saved = MargeletConfig.proxySecret();
        if (saved != null && saved.length() == 32) {
            final byte[] bytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                bytes[i] = (byte) Integer.parseInt(saved.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        }
        final byte[] fresh = MargeletObfuscation.newSecret();
        MargeletConfig.setProxySecret(MargeletObfuscation.hex(fresh));
        return fresh;
    }

    public static synchronized boolean start() {
        if (working) {
            return true;
        }
        try {
            // Порт свободный, а не заранее выбранный: занятый порт — это
            // молча не поднявшийся прокси, а мы обещали человеку связь.
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            working = true;
            accepting = new Thread(MargeletProxy::accept, "margelet-proxy");
            accepting.setDaemon(true);
            accepting.start();
            MargeletPluginHost.log("прокси", "поднят на 127.0.0.1:" + port, false);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            working = false;
            return false;
        }
    }

    public static synchronized void stop() {
        working = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        server = null;
        port = 0;
    }

    /**
     * Включить или выключить. Включение поднимает релей и прописывает его в
     * те же настройки соединения, которыми телеграм пользуется для любого
     * прокси, — своего пути в обход у нас нет и не надо.
     */
    public static void enable(boolean on) {
        if (on) {
            if (!start()) {
                MargeletConfig.setProxyEnabled(false);
                return;
            }
            MargeletConfig.setProxyEnabled(true);
            ConnectionsManager.setProxySettings(true, "127.0.0.1", port(), "", "",
                    MargeletObfuscation.hex(secret()));
        } else {
            MargeletConfig.setProxyEnabled(false);
            ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            stop();
        }
    }

    /**
     * Предложить прокси тому, у кого телеграм не подключается.
     *
     * Условий три, и все три обязательны. Первое: связь ДЕЙСТВИТЕЛЬНО не
     * поднимается — предлагать обход тому, у кого всё работает, значит пугать
     * на ровном месте. Второе: телефон говорит, что мы в России, — страна
     * берётся у сим-карты и у сети, а не по языку: язык у человека может быть
     * любой. Третье: он ещё не просил больше не спрашивать.
     *
     * Зовётся с задержкой, а не сразу: сразу после запуска телеграм всегда
     * «подключается», и окно выскочило бы у каждого.
     */
    public static void offerIfStuck(android.app.Activity activity, int account) {
        if (activity == null || working || MargeletConfig.proxyEnabled()
                || MargeletConfig.proxyAsked() || !inRussia(activity)) {
            return;
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            final int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (state != ConnectionsManager.ConnectionStateConnecting
                    && state != ConnectionsManager.ConnectionStateWaitingForNetwork) {
                return;
            }
            if (MargeletConfig.proxyAsked() || MargeletConfig.proxyEnabled()) {
                return;
            }
            ask(activity);
        }, 12000);
    }

    private static void ask(android.app.Activity activity) {
        final android.widget.CheckBox never = new android.widget.CheckBox(activity);
        never.setText(org.telegram.messenger.LocaleController.getString(
                org.telegram.messenger.R.string.MargeletProxyDontAsk));
        never.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(
                org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        final int side = org.telegram.messenger.AndroidUtilities.dp(22);
        never.setPadding(side, 0, side, 0);

        new org.telegram.ui.ActionBar.AlertDialog.Builder(activity)
                .setTitle(org.telegram.messenger.LocaleController.getString(
                        org.telegram.messenger.R.string.MargeletProxyOfferTitle))
                .setMessage(org.telegram.messenger.LocaleController.getString(
                        org.telegram.messenger.R.string.MargeletProxyOffer))
                .setView(never)
                .setPositiveButton(org.telegram.messenger.LocaleController.getString(
                        org.telegram.messenger.R.string.MargeletProxyTurnOn), (dialog, which) -> {
                    MargeletConfig.setProxyAsked(never.isChecked());
                    enable(true);
                })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(
                        org.telegram.messenger.R.string.Cancel), (dialog, which) ->
                        MargeletConfig.setProxyAsked(never.isChecked()))
                .show();
    }

    /**
     * Страна по сим-карте и по сети. Именно так, а не по языку приложения:
     * язык человек ставит какой хочет, а сим-карта врать не станет.
     */
    private static boolean inRussia(android.content.Context context) {
        try {
            final android.telephony.TelephonyManager phone =
                    (android.telephony.TelephonyManager)
                            context.getSystemService(android.content.Context.TELEPHONY_SERVICE);
            if (phone == null) {
                return false;
            }
            return "ru".equalsIgnoreCase(phone.getSimCountryIso())
                    || "ru".equalsIgnoreCase(phone.getNetworkCountryIso());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void accept() {
        while (working) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (working) {
                    FileLog.e(e);
                }
                return;
            }
            final Thread worker = new Thread(() -> serve(client), "margelet-proxy-link");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void serve(Socket client) {
        MargeletSocket upstream = null;
        try {
            client.setTcpNoDelay(true);
            final InputStream from = client.getInputStream();
            final OutputStream to = client.getOutputStream();

            final byte[] head = new byte[MargeletObfuscation.HEAD];
            readFully(from, head);

            final MargeletObfuscation.Head opened =
                    MargeletObfuscation.read(head, secret());
            if (opened == null || !opened.valid()) {
                // Либо не наш клиент, либо чужой секрет. Молчать здесь нельзя:
                // человек увидит «нет связи» и не узнает почему.
                MargeletPluginHost.log("прокси", "чужое соединение отклонено", true);
                client.close();
                return;
            }

            final int datacenter = opened.plainDatacenter();
            if (datacenter < 1 || datacenter > PLANETS.length) {
                MargeletPluginHost.log("прокси",
                        "неизвестный дата-центр: " + datacenter, true);
                client.close();
                return;
            }
            final boolean test = Math.abs(opened.datacenter) >= 10000;
            final String host = PLANETS[datacenter - 1] + ".web.telegram.org";
            final String path = test ? "/apiws_test" : "/apiws";

            upstream = MargeletSocket.open(host, path, 15000);

            // Своё начало разговора с дата-центром: там секрета нет, поэтому
            // заголовок собирается заново, а не пересылается клиентский.
            final Object[] made = MargeletObfuscation.create(opened.tag);
            upstream.send((byte[]) made[0], 0, MargeletObfuscation.HEAD);
            final MargeletObfuscation.Stream toServer = (MargeletObfuscation.Stream) made[1];
            final MargeletObfuscation.Stream fromServer = (MargeletObfuscation.Stream) made[2];

            final MargeletSocket upward = upstream;
            final Thread back = new Thread(() -> {
                try {
                    while (true) {
                        final byte[] chunk = upward.receive();
                        if (chunk == null) {
                            break;
                        }
                        // Снимаем обёртку дата-центра и надеваем клиентскую:
                        // у них разные ключи, сквозного потока здесь нет.
                        final byte[] plain = fromServer.run(chunk);
                        to.write(opened.outgoing.run(plain));
                        to.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    close(client);
                    upward.close();
                }
            }, "margelet-proxy-back");
            back.setDaemon(true);
            back.start();

            final byte[] buffer = new byte[16 * 1024];
            while (true) {
                final int read = from.read(buffer);
                if (read < 0) {
                    break;
                }
                final byte[] plain = opened.incoming.run(buffer, 0, read);
                final byte[] sealed = toServer.run(plain);
                upstream.send(sealed, 0, sealed.length);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            close(client);
            if (upstream != null) {
                upstream.close();
            }
        }
    }

    private static void readFully(InputStream from, byte[] into) throws IOException {
        int done = 0;
        while (done < into.length) {
            final int step = from.read(into, done, into.length - done);
            if (step < 0) {
                throw new IOException("клиент закрылся на заголовке");
            }
            done += step;
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
