package org.telegram.margelet;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Настройки форка. Держим их в отдельном файле и в своём разделе настроек,
 * чтобы правки не растекались по коду оригинала: чем меньше тронуто чужих
 * строк, тем проще будет подтягивать новые версии телеграма.
 */
public class MargeletConfig {

    private static final String PREFS = "margelet";

    public static final int INPUT_LINES_DEFAULT = 6;
    public static final float INPUT_TEXT_SIZE_DEFAULT = 18f;

    public static final String APP_NAME = "Margelet";

    /**
     * Номер этой сборки. По нему клиент понимает, что на гитхабе лежит версия
     * новее, и предлагает обновиться.
     *
     * Поднимать его надо руками при выпуске — вместе с номером в version.json.
     * Забыть про него значит выпустить сборку, которая всю жизнь будет
     * предлагать обновиться сама на себя.
     */
    public static final String APP_VERSION = "0.3.4";

    /**
     * Как часто спрашивать гитхаб про новую версию, в минутах. Ноль — не
     * спрашивать вовсе; проверить руками кнопкой можно и тогда.
     */
    public static final int UPDATE_INTERVAL_OFF = 0;
    public static final int UPDATE_INTERVAL_DEFAULT = 3;
    /** Значения для выбора: от трёх минут до суток. */
    public static final int[] UPDATE_INTERVALS = {3, 15, 60, 6 * 60, 24 * 60, UPDATE_INTERVAL_OFF};

    public static int updateIntervalMinutes() {
        return prefs().getInt("update_interval", UPDATE_INTERVAL_DEFAULT);
    }

    public static void setUpdateIntervalMinutes(int minutes) {
        prefs().edit().putInt("update_interval", minutes).apply();
    }

    /** Спрашивать ли самому. Кнопки «проверить сейчас» это не касается. */
    public static boolean updatesChecked() {
        return updateIntervalMinutes() > 0;
    }

    public static final String CHANNEL_URL = "https://t.me/margeletter";
    /**
     * Реквизиты для доната. Лежат здесь, а не в строках: это не перевод, а
     * данные владельца форка, и в каждом языке они одни и те же.
     */
    public static final String DONATE_YOOMONEY = "2204120143055305";
    public static final String DONATE_ROBLOX = "h4ru_456";
    /**
     * Страница пожертвований. В отличие от номера кошелька её не копируют, а
     * открывают: там уже готовая форма, и человеку не нужно никуда вставлять
     * цифры руками.
     */
    public static final String DONATE_PAGE = "https://yoomoney.ru/to/4100118196133693";
    /** Кому дарить подарок за звёзды. Ник нужен на случай, если номера нет в кэше. */
    public static final long DONATE_GIFT_USER = 7826361017L;
    public static final String DONATE_GIFT_USERNAME = "narezany";

    /** Свой набор стикеров: ставится обычной кнопкой, как любой другой набор. */
    public static final String STICKERS_URL = "https://t.me/addstickers/MargeletPackMargeletter";

    public static final String SOURCE_URL = "https://github.com/narezany/Margelet";
    public static final String FORUM_URL = "https://t.me/margeletforum";
    /**
     * Написать нам. Это тот же канал, но с ?direct: телеграм открывает не
     * ленту, а поле для сообщения — человек с жалобой не должен искать, куда
     * её деть.
     */
    public static final String FEEDBACK_URL = "https://t.me/margeletter?direct";
    /**
     * Документация по плагинам. Ведёт на файл в репозитории, а не на страницу
     * сайта: страницы гитхаба у репозитория не включены, а ссылка на
     * несуществующий сайт — просто обман.
     *
     * Язык берётся из приложения. Английская — та, что без суффикса: она же
     * открывается по ссылке из README, и с неё есть переходы на остальные.
     */
    public static String pluginsDocsUrl() {
        final String base = "https://github.com/narezany/Margelet/blob/main/docs/plugins";
        String language = null;
        try {
            language = org.telegram.messenger.LocaleController.getInstance()
                    .getCurrentLocale().getLanguage();
        } catch (Exception ignored) {
        }
        if ("ru".equals(language) || "zh".equals(language)) {
            return base + "." + language + ".md";
        }
        return base + ".md";
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * До скольких строк растёт поле ввода, прежде чем начать прокручиваться.
     * Ноль означает «расти, пока есть место на экране»: сам EditText выше
     * своего контейнера не станет, так что бесконечности тут не будет — будет
     * ровно высота экрана, о которой и просили.
     */
    public static int inputMaxLines() {
        int v = inputMaxLinesRaw();
        return v <= 0 ? Integer.MAX_VALUE : v;
    }

    /**
     * То же значение, но как оно записано: ноль остаётся нулём. Экрану
     * настроек нужно именно это — иначе «без предела» пришлось бы угадывать
     * по двум миллиардам строк.
     */
    public static int inputMaxLinesRaw() {
        return prefs().getInt("input_max_lines", INPUT_LINES_DEFAULT);
    }

    public static void setInputMaxLines(int lines) {
        prefs().edit().putInt("input_max_lines", lines).apply();
    }

    /** Размер текста в поле ввода, в тех же единицах, что и в оригинале. */
    public static float inputTextSize() {
        return prefs().getFloat("input_text_size", INPUT_TEXT_SIZE_DEFAULT);
    }

    public static void setInputTextSize(float sp) {
        prefs().edit().putFloat("input_text_size", sp).apply();
    }

    /** Поле ввода сверху экрана, а не снизу. */
    public static boolean inputOnTop() {
        return prefs().getBoolean("input_on_top", false);
    }

    public static void setInputOnTop(boolean top) {
        prefs().edit().putBoolean("input_on_top", top).apply();
    }

    /**
     * Удалённые подарки в каталоге. По умолчанию выключено: это добавляет к
     * списку то, чего телеграм там не показывает, и включать такое за человека
     * молча неправильно.
     */
    public static boolean giftsEnabled() {
        return prefs().getBoolean("gifts", false);
    }

    public static void setGiftsEnabled(boolean on) {
        prefs().edit().putBoolean("gifts", on).apply();
    }

    /**
     * Виды своего оформления по отдельности. Всё включено по умолчанию:
     * выключение — это про «мне мешает», а не про «покажите сначала».
     */
    public static boolean markupEnabled(int kind) {
        return prefs().getBoolean("markup_" + kind, true);
    }

    public static void setMarkupEnabled(int kind, boolean on) {
        prefs().edit().putBoolean("markup_" + kind, on).apply();
    }

    /**
     * Показывать ли строку со ссылкой на форк в чужих сообщениях. По умолчанию
     * нет: внутри форка она и так ни к чему, она для тех, у кого форка нет.
     */
    public static boolean showWatermarks() {
        return prefs().getBoolean("watermarks", false);
    }

    public static void setShowWatermarks(boolean on) {
        prefs().edit().putBoolean("watermarks", on).apply();
    }

    /**
     * Разметка значками по видам: **жирный**, __курсив__ и прочие, включая
     * свои — ++подчёркнутый++ и цитаты через «больше». Всё включено по
     * умолчанию; выключенный вид остаётся в тексте как есть.
     */
    public static boolean markdownEnabled(String kind) {
        return prefs().getBoolean("markdown_" + kind, true);
    }

    public static void setMarkdownEnabled(String kind, boolean on) {
        prefs().edit().putBoolean("markdown_" + kind, on).apply();
    }

    /**
     * Держать ли канал форка первой строкой в списке чатов. По умолчанию да:
     * узнать о том, что происходит с форком, больше особо неоткуда.
     */
    public static boolean channelOnTop() {
        return prefs().getBoolean("channel_on_top", true);
    }

    public static void setChannelOnTop(boolean on) {
        prefs().edit().putBoolean("channel_on_top", on).apply();
    }

    /**
     * Скрывать ли вкладку «Все чаты» в списке папок.
     */
    public static boolean hideAllChats() {
        return prefs().getBoolean("hide_all_chats", false);
    }

    public static void setHideAllChats(boolean hide) {
        prefs().edit().putBoolean("hide_all_chats", hide).apply();
    }

    /**
     * Дописывать ли строку со ссылкой на форк в свои оформленные сообщения.
     * По умолчанию да: форк живёт тем, что о нём узнают.
     */
    public static boolean watermarkOnSend() {
        return prefs().getBoolean("watermark_send", false);
    }

    public static void setWatermarkOnSend(boolean on) {
        prefs().edit().putBoolean("watermark_send", on).apply();
    }

    /** Премиум-значки без премиума: видны только в форке. */
    public static boolean freeEmoji() {
        return prefs().getBoolean("free_emoji", true);
    }

    public static void setFreeEmoji(boolean on) {
        prefs().edit().putBoolean("free_emoji", on).apply();
    }

    public static boolean emojiWarned() {
        return prefs().getBoolean("emoji_warned", false);
    }

    public static void setEmojiWarned(boolean value) {
        prefs().edit().putBoolean("emoji_warned", value).apply();
    }

    /** Пункт «Копировать с оформлением» в меню сообщения. */
    public static boolean copyFormatting() {
        return prefs().getBoolean("copy_formatting", true);
    }

    public static void setCopyFormatting(boolean on) {
        prefs().edit().putBoolean("copy_formatting", on).apply();
    }

    /** Показывали ли предупреждение о своём оформлении. Один раз за всё время. */
    public static boolean markupWarned() {
        return prefs().getBoolean("markup_warned", false);
    }

    public static void setMarkupWarned(boolean value) {
        prefs().edit().putBoolean("markup_warned", value).apply();
    }

    /** Значки форка у имён. Включены по умолчанию: без них форк выглядит чужим. */
    public static boolean badgesEnabled() {
        return prefs().getBoolean("badges", true);
    }

    public static void setBadgesEnabled(boolean on) {
        prefs().edit().putBoolean("badges", on).apply();
    }

    /** Показывать айди в профилях людей, групп, каналов и ботов. */
    public static boolean showIds() {
        return prefs().getBoolean("show_ids", true);
    }

    public static void setShowIds(boolean on) {
        prefs().edit().putBoolean("show_ids", on).apply();
    }

    /**
     * «Приступ»: весь текст переливается радугой. Выключено по умолчанию и
     * включается только через предупреждение — мигающая картинка бывает опасна
     * не в переносном смысле.
     */
    public static boolean seizure() {
        return prefs().getBoolean("seizure", false);
    }

    public static void setSeizure(boolean on) {
        prefs().edit().putBoolean("seizure", on).apply();
    }

    /** Режим стримера: прятать номер телефона. */
    public static boolean streamerMode() {
        return prefs().getBoolean("streamer", false);
    }

    public static void setStreamerMode(boolean on) {
        prefs().edit().putBoolean("streamer", on).apply();
    }

    public static boolean streamerHidesOthers() {
        return prefs().getBoolean("streamer_others", false);
    }

    public static void setStreamerHidesOthers(boolean on) {
        prefs().edit().putBoolean("streamer_others", on).apply();
    }

    public static boolean streamerHidesUsername() {
        return prefs().getBoolean("streamer_username", false);
    }

    public static void setStreamerHidesUsername(boolean on) {
        prefs().edit().putBoolean("streamer_username", on).apply();
    }

    /** Правка тегов у музыки. По умолчанию включена. */
    public static boolean tagsEnabled() {
        return prefs().getBoolean("tags_enabled", true);
    }

    public static void setTagsEnabled(boolean enabled) {
        prefs().edit().putBoolean("tags_enabled", enabled).apply();
    }

    /**
     * Слышал ли человек мяуканье хоть раз. До этого раздела «Звук» в
     * настройках нет: настраивать то, о существовании чего не знаешь, незачем,
     * а найденная случайно шутка тем и хороша, что найдена.
     */
    public static boolean meowHeard() {
        return prefs().getBoolean("meow_heard", false);
    }

    public static void setMeowHeard() {
        prefs().edit().putBoolean("meow_heard", true).apply();
    }

    /** Мяуканье по нажатию на название — можно выключить совсем. */
    public static boolean meowEnabled() {
        return prefs().getBoolean("meow_enabled", true);
    }

    public static void setMeowEnabled(boolean enabled) {
        prefs().edit().putBoolean("meow_enabled", enabled).apply();
    }

    /**
     * Путь к своему звуку. Пусто — играет тот, что лежит в сборке. Файл
     * копируется к нам при выборе: ссылка на чужой файл живёт до первой
     * уборки в галерее, а копия — сколько нужно.
     */
    public static String meowPath() {
        return prefs().getString("meow_path", null);
    }

    public static void setMeowPath(String path) {
        if (path == null) {
            prefs().edit().remove("meow_path").apply();
        } else {
            prefs().edit().putString("meow_path", path).apply();
        }
    }

    /**
     * Первый запуск. Нужен, чтобы один раз включить тёмно-зелёную тему и
     * больше в выбор темы не лезть: если человек потом поставит другую, наше
     * дело в это не вмешиваться.
     */
    public static boolean claimFirstLaunch() {
        if (prefs().getBoolean("first_launch_done", false)) {
            return false;
        }
        prefs().edit().putBoolean("first_launch_done", true).apply();
        return true;
    }

    /**
     * Главный выключатель плагинов. По умолчанию выключен: плагин выполняется
     * внутри приложения и может всё, что может приложение, — такое не
     * включают за человека.
     */
    public static boolean pluginsEnabled() {
        return prefs().getBoolean("plugins_enabled", false);
    }

    public static void setPluginsEnabled(boolean on) {
        prefs().edit().putBoolean("plugins_enabled", on).apply();
    }

    /** Включён ли отдельный плагин. Новый плагин всегда выключен. */
    public static boolean pluginEnabled(String id) {
        return prefs().getBoolean("plugin_" + id, false);
    }

    public static void setPluginEnabled(String id, boolean on) {
        prefs().edit().putBoolean("plugin_" + id, on).apply();
    }

    /**
     * Пример плагина кладётся в папку один раз. Если человек его удалил,
     * второй раз он не появится: удаление — это ответ, а не случайность.
     */
    public static boolean claimExamplePlugin() {
        if (prefs().getBoolean("plugin_example_done", false)) {
            return false;
        }
        prefs().edit().putBoolean("plugin_example_done", true).apply();
        return true;
    }
}
