package org.telegram.margelet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление форка мимо магазина.
 *
 * В репозитории лежит файл с номером последней версии и ссылкой на apk. Клиент
 * знает свой номер, сравнивает и, если в файле новее, показывает внизу экрана
 * ту же полоску, что телеграм показывает для своих бета-сборок.
 *
 * Почему не штатный механизм телеграма: он качает не по ссылке, а документ из
 * переписки, через свою файловую очередь. Наш apk лежит на гитхабе и документом
 * не является, поэтому скачивание здесь своё.
 */
public class MargeletUpdate {

    /** Файл с описанием последней версии и ключ, под которым он лежит в кэше. */
    private static final String FILE = "version.json";
    private static final String CACHE_KEY = "version";

    private static final String PREFS = "margelet_update";
    /** Версия, под которую скачан apk. Пусто — скачанного нет. */
    private static final String KEY_READY_VERSION = "ready_version";
    /**
     * Версия, чьё скачивание сервер ответил отказом (битая ссылка в
     * version.json, релиз не выложен). Такая ошибка сама не пройдёт: если
     * продолжать предлагать ту же версию, полоска обновления зациклится.
     */
    private static final String KEY_FAILED = "failed_version";
    private static final String KEY_FAILED_AT = "failed_at";
    /** Через сколько часов забыть отказ и предложить версию снова. */
    private static final long FAILURE_FORGET_MS = 12L * 60 * 60 * 1000;

    private static volatile boolean downloading;
    private static volatile float progress;
    /** Скачивание можно отменить: полоска умеет и это. */
    private static volatile boolean cancelled;

    /** Что написано в файле про последнюю версию. */
    public static final class Info {
        public final String version;
        public final String apk;
        private final JSONObject json;

        Info(String version, String apk, JSONObject json) {
            this.version = version;
            this.apk = apk;
            this.json = json;
        }

        /** Что нового — строкой на языке приложения. Может быть пустым. */
        public String about() {
            return MargeletRemote.localized(json, "about", "");
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Спрашивает гитхаб про последнюю версию. Зовётся кнопкой и расписанием.
     *
     * Ответ всегда кладётся в кэш, поэтому даже неудачная проверка не делает
     * хуже: показывается то, что знали раньше.
     */
    /**
     * Проверка и версии, и значков разом. Для человека это одно действие:
     * «посмотри, не появилось ли нового».
     */
    public static void checkAll(Runnable done) {
        // Ручная проверка — это «проверь честно, даже если недавно было
        // отказано»: человек нажал кнопку, значит хочет повторить сам.
        clearFailure();
        MargeletBadge.refresh();
        check(done);
    }

    public static void check(Runnable done) {
        lastCheck = System.currentTimeMillis();
        MargeletRemote.fetch(FILE, CACHE_KEY, text -> {
            if (done != null) {
                done.run();
            }
        });
    }

    private static String failedKey(Info info) {
        return info.version + "|" + info.apk;
    }

    /** Отказ ещё свеж и совпадает с этой версией — предлагать её не надо. */
    private static boolean recentlyFailed(Info info) {
        if (!failedKey(info).equals(prefs().getString(KEY_FAILED, null))) {
            return false;
        }
        final long at = prefs().getLong(KEY_FAILED_AT, 0);
        return at > 0 && System.currentTimeMillis() - at < FAILURE_FORGET_MS;
    }

    private static void clearFailure() {
        prefs().edit().remove(KEY_FAILED).remove(KEY_FAILED_AT).apply();
    }

    /** Когда в последний раз спрашивали, за этот запуск приложения. */
    private static long lastCheck;
    /** Расписание живёт в одном экземпляре: второй пересоздаст очередь. */
    private static Runnable scheduled;

    public static long lastCheckTime() {
        return lastCheck;
    }

    /**
     * Ставит проверку по расписанию: раз в столько минут, сколько выбрано в
     * настройках. Ноль — не проверять вовсе.
     *
     * Зовётся при запуске и после смены значения в настройках, поэтому первым
     * делом снимает прошлое расписание: иначе после трёх заходов в настройки
     * проверок стало бы три.
     */
    public static void schedule() {
        if (scheduled != null) {
            AndroidUtilities.cancelRunOnUIThread(scheduled);
            scheduled = null;
        }
        final int minutes = MargeletConfig.updateIntervalMinutes();
        if (minutes <= 0) {
            return;
        }
        final long delay = minutes * 60L * 1000L;
        scheduled = new Runnable() {
            @Override
            public void run() {
                // Настройку могли выключить, пока мы ждали своей очереди.
                if (MargeletConfig.updateIntervalMinutes() <= 0) {
                    scheduled = null;
                    return;
                }
                check(() -> org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(org.telegram.messenger.NotificationCenter.appUpdateAvailable));
                // Заодно перечитываем значки. Раньше они читались только при
                // запуске приложения, и у того, кто держит телеграм открытым
                // сутками, новый значок не появлялся вовсе — ровно та же
                // беда, из-за которой здесь и завелось расписание.
                MargeletBadge.refresh();
                AndroidUtilities.runOnUIThread(this, delay);
            }
        };
        AndroidUtilities.runOnUIThread(scheduled, delay);
    }

    /**
     * Версия из файла, если она новее нашей. Иначе null.
     *
     * Ничего не качает: работает по тому, что уже скачано и лежит в кэше.
     */
    public static Info available() {
        final String text = MargeletRemote.cached(CACHE_KEY);
        if (text == null) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(text);
            final String version = json.optString("version", null);
            final String apk = json.optString("apk", null);
            if (version == null || apk == null || version.isEmpty() || apk.isEmpty()) {
                return null;
            }
            if (!newer(version, MargeletConfig.APP_VERSION)) {
                return null;
            }
            final Info info = new Info(version, apk, json);
            return recentlyFailed(info) ? null : info;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Больше ли первый номер второго. Сравниваем по числам через точку, а не
     * строкой: иначе «0.10» оказалось бы меньше «0.9».
     */
    static boolean newer(String candidate, String current) {
        final String[] a = candidate.split("\\.");
        final String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int left = number(a, i);
            final int right = number(b, i);
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int number(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Куда кладём скачанное. Папка уже описана в provider_paths. */
    private static File file() {
        final File directory = new File(ApplicationLoader.getFilesDirFixed(), "cache");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return new File(directory, "margelet_update.apk");
    }

    /** Скачанный apk именно этой версии или null. */
    public static File downloaded() {
        final Info info = available();
        if (info == null) {
            return null;
        }
        if (!info.version.equals(prefs().getString(KEY_READY_VERSION, null))) {
            return null;
        }
        final File file = file();
        return file.exists() && file.length() > 0 ? file : null;
    }

    public static boolean downloading() {
        return downloading;
    }

    public static float progress() {
        return progress;
    }

    public static void cancel() {
        cancelled = true;
    }

    /**
     * Качает apk. onChange зовётся в главном потоке на каждом шаге — полоска
     * по нему и перерисовывается.
     */
    public static void download(Runnable onChange) {
        final Info info = available();
        if (info == null || downloading) {
            return;
        }
        downloading = true;
        cancelled = false;
        progress = 0f;
        final Thread worker = new Thread(() -> {
            boolean ok = false;
            int code = -1;
            HttpURLConnection connection = null;
            final File target = file();
            try {
                connection = (HttpURLConnection) new URL(info.apk).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", MargeletConfig.APP_NAME);
                code = connection.getResponseCode();
                if (code == 200) {
                    final long total = connection.getContentLength();
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        final byte[] buffer = new byte[16384];
                        long written = 0;
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            if (cancelled) {
                                break;
                            }
                            out.write(buffer, 0, read);
                            written += read;
                            if (total > 0) {
                                progress = written / (float) total;
                                if (onChange != null) {
                                    AndroidUtilities.runOnUIThread(onChange);
                                }
                            }
                        }
                        ok = !cancelled && (total <= 0 || written >= total);
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            // Недокачанный файл не должен притворяться готовым: помечаем
            // готовность только целиком скачанной версией.
            if (ok) {
                prefs().edit().putString(KEY_READY_VERSION, info.version).apply();
                clearFailure();
            } else {
                prefs().edit().remove(KEY_READY_VERSION).apply();
                target.delete();
                if (!cancelled && code != 200 && code != -1) {
                    // Сервер ответил отказом — например, релиз ещё не выложен,
                    // а version.json уже его объявил. Это ошибка выпуска, а не
                    // сети: запоминаем, чтобы полоска не предлагала одно и то
                    // же каждые три минуты, и говорим вслух, в чём дело.
                    prefs().edit()
                            .putString(KEY_FAILED, failedKey(info))
                            .putLong(KEY_FAILED_AT, System.currentTimeMillis())
                            .apply();
                    MargeletPluginHost.log("margelet",
                            "обновление " + info.version + " не скачалось: сервер ответил " + code
                                    + ". Скорее всего, apk по ссылке из version.json ещё не выложен",
                            true);
                }
            }
            downloading = false;
            progress = 0f;
            if (onChange != null) {
                AndroidUtilities.runOnUIThread(onChange);
            }
        }, "margelet-update");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Отдаёт скачанный apk системе. Ставить будет она, спросив человека, —
     * молча подменить себе приложение нельзя, и это правильно.
     */
    public static void install(Activity activity) {
        final File file = downloaded();
        if (activity == null || file == null) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 24) {
                intent.setDataAndType(FileProvider.getUriForFile(activity,
                        ApplicationLoader.getApplicationId() + ".provider", file),
                        "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }
            activity.startActivityForResult(intent, 500);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
