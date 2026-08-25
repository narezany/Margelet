package org.telegram.margelet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Небольшие файлы с гитхаба: список значков, номер последней версии.
 *
 * Смысл в том, чтобы такие вещи правились без пересборки клиента — дописал
 * строчку в репозитории, и она приехала людям. Поэтому здесь нет ничего, кроме
 * «скачать текст и запомнить»: разбор формата — дело того, кто заказывал.
 *
 * Скачанное всегда кладётся в настройки. Без сети показывается последнее
 * скачанное, а пока не скачалось ни разу — тот, кто спрашивал, обходится своим
 * вшитым запасом. Значит, ни один экран не зависит от того, есть ли интернет.
 */
public class MargeletRemote {

    /** Куда смотрим за файлами. Ветка main репозитория форка. */
    public static final String BASE =
            "https://raw.githubusercontent.com/narezany/Margelet/main/";

    private static final String PREFS = "margelet_remote";
    /** Файлы здесь маленькие; всё, что больше, — уже не наш файл. */
    private static final int MAX_BYTES = 256 * 1024;
    private static final int TIMEOUT_MS = 15000;

    /**
     * Значение на языке приложения: сперва ключ с суффиксом языка, потом
     * основной. Так устроены и манифесты плагинов — формат один, чтобы
     * человеку не приходилось помнить два.
     */
    public static String localized(JSONObject json, String key, String fallback) {
        String language = null;
        try {
            language = LocaleController.getInstance().getCurrentLocale().getLanguage();
        } catch (Exception ignored) {
        }
        if (language != null) {
            final String value = json.optString(key + "_" + language, null);
            if (value != null && value.length() > 0) {
                return value;
            }
        }
        return json.optString(key, fallback);
    }

    public interface Callback {
        /** Вызывается в главном потоке. text — null, если скачать не вышло. */
        void onResult(String text);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Последнее удачно скачанное содержимое или null. */
    public static String cached(String key) {
        return prefs().getString(key, null);
    }

    /** Когда в последний раз удалось скачать, в миллисекундах. */
    public static long cachedAt(String key) {
        return prefs().getLong(key + "_at", 0);
    }

    /**
     * Качает файл в фоне и отдаёт ответ в главный поток.
     *
     * Удачная загрузка перезаписывает кэш. Неудачная не трогает его вовсе:
     * лучше показать вчерашний список, чем пустой.
     */
    public static void fetch(String path, String key, Callback callback) {
        final Thread worker = new Thread(() -> {
            String result = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(BASE + path).openConnection();
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", MargeletConfig.APP_NAME);
                if (connection.getResponseCode() == 200) {
                    try (InputStream in = connection.getInputStream()) {
                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        final byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0 && out.size() <= MAX_BYTES) {
                            out.write(buffer, 0, read);
                        }
                        if (out.size() <= MAX_BYTES) {
                            result = out.toString("UTF-8");
                        }
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (result != null) {
                prefs().edit()
                        .putString(key, result)
                        .putLong(key + "_at", System.currentTimeMillis())
                        .apply();
            }
            final String delivered = result;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(delivered));
            }
        }, "margelet-remote");
        worker.setDaemon(true);
        worker.start();
    }
}
