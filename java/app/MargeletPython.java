package org.telegram.margelet;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * Единственное место, где форк трогает питон напрямую.
 *
 * Лежит в модуле приложения, а не в общей библиотеке, потому что движок
 * питона подключается только к этой сборке: остальным вариантам приложения
 * он не нужен, и тащить одиннадцать мегабайт во все — глупость. Библиотека
 * зовёт этот класс по имени, через отражение: так она собирается и там, где
 * питона нет вовсе.
 */
public class MargeletPython {

    public static void start(Context context) {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
    }

    /** Открылся чат. Плагины, которые на это подписаны, узнают об этом. */
    public static void chatOpened(Object fragment) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("chat_opened", fragment);
    }

    /**
     * Человек отправляет текст. Ответ нужен сразу же, поэтому это
     * единственный вызов питона, который не откладывается, а ждёт.
     */
    public static String sending(String text, long dialogId) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("sending", text, dialogId);
        return answer == null ? text : answer.toString();
    }

    /**
     * Человек отправляет картинку.
     *
     * Ответ нужен до отправки, как и у текста, но ждут его не в главном
     * потоке: разбор картинки — работа не на миллисекунды.
     *
     * Возвращаем null, когда питон ответил None: «никто не заинтересовался» и
     * «заменить на пустую строку» — разные вещи, и склеивать их нельзя.
     */
    public static String sendingMedia(String path, String caption, long dialogId) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("sendingMedia", path, caption, dialogId);
        return answer == null ? null : answer.toString();
    }

    /** Пришло сообщение. */
    public static void received(String text, long dialogId, int messageId, boolean out) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("received", text, dialogId, messageId, out);
    }

    /** Нажали строчку плагина в одном из меню. */
    public static void menuClicked(String pluginId, String key, String where, Object fragment, Object target) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("menu_clicked", pluginId, key, where, fragment, target);
    }

    /**
     * Запрос к серверу, ответ сервера, обновление с сервера.
     *
     * Все три устроены одинаково: питон либо молчит, либо отдаёт замену.
     * Молчание — это None, и оно должно доехать до джавы именно как null:
     * «плагин не тронул» и «плагин вернул пустоту» — разные вещи, и склеить
     * их значит однажды выкинуть чужой запрос вместо того, чтобы пропустить.
     */
    public static Object requesting(Object request) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("requesting", request);
        return answer == null ? null : answer.toJava(Object.class);
    }

    public static Object answering(Object request, Object response, Object error) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("answering", request, response, error);
        return answer == null ? null : answer.toJava(Object.class);
    }

    public static Object updating(Object update) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("updating", update);
        return answer == null ? null : answer.toJava(Object.class);
    }

    /** Человек поменял настройку плагина на его экране настроек. */
    public static void settingsChanged(String pluginId, String key, String value) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("settings_changed", pluginId, key, value);
    }

    /** Сообщения удалили. */
    public static void deleted(int[] ids, long chat) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("deleted", ids, chat);
    }

    /** Чат закрепляют. Ответ решает, случится ли это. */
    public static boolean pinning(long chat, boolean pin) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        final PyObject answer = host.callAttr("pinning", chat, pin);
        return answer == null || answer.toBoolean();
    }

    public static void run(String id, String name, String folder) {
        final PyObject host = Python.getInstance().getModule("margelet_host");
        host.callAttr("run_plugin", id, name, folder);
    }
}
