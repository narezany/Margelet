package org.telegram.margelet;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Двери, через которые плагин узнаёт о происходящем.
 *
 * Раньше плагину оставалось одно: просыпаться по будильнику и смотреть, не
 * изменилось ли что-нибудь. Так написан первый наш плагин, и так писать
 * плохо: телефон греется впустую, а плагин всё равно узнаёт о событии позже,
 * чем оно случилось.
 *
 * Дверей нарочно немного, и каждая названа. Это не то же самое, что дать
 * плагину подменять любой метод приложения: подмена любого метода — это
 * переписывание чужого кода на ходу, для этого нужна отдельная библиотека,
 * которая правит машинный код, и любое обновление телеграма ломает всё, что
 * на ней написано. Названная дверь переживает обновление, потому что за неё
 * отвечаем мы, а не случайное совпадение имён.
 *
 * Флаги здесь не для красоты: пока никто не подписан, питон не дёргается
 * вовсе, и отправка сообщения стоит ровно столько же, сколько без плагинов.
 */
public class MargeletHooks {

    /**
     * Ответ питона, означающий «не отправляй».
     *
     * Записан экранированием нарочно. Раньше здесь стоял сам знак — невидимый
     * нулевой байт прямо в исходнике, — а в питоне на его месте оказался
     * обычный пробел. Строки не совпадали никогда, отмена не срабатывала ни
     * разу, и вместо неё в переписку уходила сама эта метка. Увидел это не я,
     * а человек, у которого перед каждым курсом биткоина появлялось
     * «margelet-cancel».
     *
     * Невидимый знак в исходнике нельзя ни прочитать глазами, ни сверить.
     * Поэтому — только экранированием, и в обоих файлах одинаково.
     */
    public static final String CANCEL = "\u0000margelet-cancel";

    /** Строчка плагина в одном из меню приложения. */
    public static final class Button {
        public final String pluginId;
        public final String key;
        public final String title;
        /** В каком меню стоит: {@link #CHAT}, {@link #PROFILE}, {@link #MESSAGE}, {@link #DRAWER}. */
        public final String where;

        Button(String pluginId, String key, String title, String where) {
            this.pluginId = pluginId;
            this.key = key;
            this.title = title;
            this.where = where;
        }
    }

    /** Три точки в шапке переписки. */
    public static final String CHAT = "chat";
    /** Три точки на экране человека, группы или канала. */
    public static final String PROFILE = "profile";
    /** Долгое нажатие на сообщение. */
    public static final String MESSAGE = "message";
    /** Боковое меню, которое выезжает слева. */
    public static final String DRAWER = "drawer";

    private static volatile boolean wantsSend;
    private static volatile boolean wantsMedia;
    private static volatile boolean wantsMessage;
    private static volatile boolean wantsRequest;
    private static volatile boolean wantsAnswer;
    private static volatile boolean wantsUpdate;
    /**
     * Строчки плагинов по местам. Раньше здесь был один список — только меню
     * чата, — и место было не нужно. Теперь мест четыре, а список остаётся
     * один на место: нажатие приходит номером строчки, и номера у разных
     * меню свои.
     */
    private static final java.util.LinkedHashMap<String, List<Button>> menus =
            new java.util.LinkedHashMap<>();
    private static boolean watching;

    // --- подписка со стороны питона ---

    public static void wantSend() {
        wantsSend = true;
    }

    public static void wantMedia() {
        wantsMedia = true;
    }

    public static void wantMessage() {
        wantsMessage = true;
        watch();
    }

    public static boolean hasSend() {
        return wantsSend;
    }

    public static boolean hasMedia() {
        return wantsMedia;
    }

    public static void wantRequest() {
        wantsRequest = true;
    }

    public static void wantAnswer() {
        wantsAnswer = true;
    }

    public static void wantUpdate() {
        wantsUpdate = true;
    }

    public static boolean hasRequest() {
        return wantsRequest;
    }

    public static boolean hasAnswer() {
        return wantsAnswer;
    }

    public static boolean hasUpdate() {
        return wantsUpdate;
    }

    /**
     * Строчка плагина в меню чата. Плагин зовёт это при запуске; при
     * перезапуске приложения список собирается заново, поэтому одинаковую
     * запись заменяем, а не копим.
     */
    public static synchronized void addButton(String pluginId, String key, String title) {
        addMenuItem(pluginId, key, title, CHAT);
    }

    /** То же самое, но в любое из четырёх меню. */
    public static synchronized void addMenuItem(String pluginId, String key, String title, String where) {
        List<Button> list = menus.get(where);
        if (list == null) {
            list = new ArrayList<>();
            menus.put(where, list);
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).pluginId.equals(pluginId) && list.get(i).key.equals(key)) {
                list.remove(i);
            }
        }
        list.add(new Button(pluginId, key, title, where));
    }

    /** Строчки плагинов для одного меню. Пусто — значит рисовать нечего. */
    public static synchronized List<Button> menu(String where) {
        final List<Button> list = menus.get(where);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    public static synchronized Button menuItem(String where, int index) {
        final List<Button> list = menus.get(where);
        return list != null && index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public static List<Button> buttons() {
        return menu(CHAT);
    }

    public static Button button(int index) {
        return menuItem(CHAT, index);
    }

    /** Нажали строчку плагина в меню чата. Экран уходит плагину как есть. */
    public static void buttonClicked(int index, Object fragment) {
        menuClicked(CHAT, index, fragment, null);
    }

    /**
     * Нажали строчку плагина в любом меню.
     *
     * Кроме экрана плагин получает то, на чём меню открыли: в профиле — номер
     * человека или чата, на сообщении — само сообщение. В меню чата и в
     * боковом меню такого предмета нет, туда уходит null.
     */
    public static void menuClicked(String where, int index, Object fragment, Object target) {
        final Button button = menuItem(where, index);
        if (button == null) {
            return;
        }
        MargeletPluginHost.post(() -> {
            try {
                MargeletPluginHost.python("menuClicked",
                        new Class<?>[]{String.class, String.class, String.class,
                                Object.class, Object.class},
                        button.pluginId, button.key, button.where, fragment, target);
            } catch (Throwable t) {
                FileLog.e(t);
                MargeletPluginHost.log(button.title, String.valueOf(t), true);
            }
        });
    }

    // --- отправка ---

    /**
     * Человек отправляет текст. Плагин может его поменять или отменить
     * отправку совсем.
     *
     * Здесь единственное место во всём движке плагинов, где питон зовётся
     * прямо на том потоке, который его позвал, и ждать приходится по-честному.
     * Иначе никак: сообщение уже уходит, и ответ «поменяй текст» нужен сейчас,
     * а не через секунду. Поэтому долгая работа в этом обработчике задержит
     * отправку — и мы говорим об этом вслух, в консоль, когда замечаем.
     *
     * @return текст, который надо отправить, или null — не отправлять.
     */
    /**
     * На что отвечал человек в отправке, которую плагин отменил.
     *
     * Нужно вот зачем. Команда плагина устроена так: обработчик отменяет
     * отправку и шлёт свой ответ отдельным сообщением. При этом «на что
     * отвечали» оставалось в отменённом, и «.погода» ответом кому-то давала
     * ответ в пустоту. Искать это сообщение заново неоткуда и незачем — оно
     * только что было у нас в руках, надо просто не выбросить.
     */
    private static final java.util.HashMap<Long, MessageObject> replyOf = new java.util.HashMap<>();

    /** Запоминает ответ отменяемой отправки. Зовётся из места отправки. */
    public static void rememberReply(long dialogId, MessageObject replyTo) {
        if (replyTo == null) {
            replyOf.remove(dialogId);
        } else {
            replyOf.put(dialogId, replyTo);
        }
    }

    /**
     * Что плагин решил про отправляемую картинку.
     *
     * Три исхода, и все три разные: не тронули, заменили текстом, отменили.
     * Одной строкой их не выразить — пустой текст и «не тронули» слились бы,
     * а именно на этом различии здесь всё и держится.
     */
    public static final class Media {
        /** Взял ли плагин эту картинку на себя. Ложь — отправляем как обычно. */
        public final boolean handled;
        /** Чем заменить. Пусто — не отправлять вовсе. */
        public final String text;

        Media(boolean handled, String text) {
            this.handled = handled;
            this.text = text;
        }
    }

    /**
     * Дверь для картинок: плагин видит файл и подпись до отправки.
     *
     * Отдельно от {@link #sending}, потому что это разные вещи. Там уходит
     * набранный текст, здесь — файл с диска, и подпись у него своя. Свести их
     * в одну дверь значило бы отдать плагину строку и умолчать, что за ней
     * стоит картинка.
     *
     * Зовётся не из главного потока: разбор картинки — работа не на
     * миллисекунды, и держать на ней палец человека нельзя.
     *
     * @param path     файл на диске; может быть пусто, если картинка пришла адресом
     * @param caption  подпись, как её набрали
     * @return что решил плагин, или null — никто не заинтересовался
     */
    public static Media sendingMedia(String path, String caption, long dialogId) {
        if (!wantsMedia) {
            return null;
        }
        try {
            final Object answer = MargeletPluginHost.pythonValue("sendingMedia",
                    new Class<?>[]{String.class, String.class, long.class},
                    path == null ? "" : path, caption == null ? "" : caption, dialogId);
            if (answer == null) {
                return null;
            }
            final String result = String.valueOf(answer);
            if (CANCEL.equals(result)) {
                return new Media(true, null);
            }
            // Та же страховка, что и у текста: нулевого байта в наборе человека
            // быть не может, значит это разъехавшаяся метка, а не ответ.
            if (result.indexOf('\u0000') >= 0) {
                return null;
            }
            return new Media(true, result);
        } catch (Throwable t) {
            FileLog.e(t);
            MargeletPluginHost.log("margelet", String.valueOf(t), true);
            return null;
        }
    }

    public static String sending(String text, long dialogId) {
        if (!wantsSend || text == null) {
            return text;
        }
        final long started = System.currentTimeMillis();
        try {
            final Object answer = MargeletPluginHost.pythonValue("sending",
                    new Class<?>[]{String.class, long.class}, text, dialogId);
            final long spent = System.currentTimeMillis() - started;
            if (spent > 100) {
                MargeletPluginHost.log("margelet",
                        "обработчик отправки думал " + spent + " мс — столько же ждал человек", true);
            }
            if (answer == null) {
                return text;
            }
            final String result = String.valueOf(answer);
            // Сравнение с меткой — основной путь. Проверка на нулевой байт —
            // страховка от того, что уже один раз случилось: метки разошлись
            // между двумя файлами, и мусор ушёл в переписку. Набрать такое с
            // клавиатуры нельзя, значит это в любом случае не текст человека.
            if (CANCEL.equals(result) || result.indexOf('\u0000') >= 0) {
                return null;
            }
            return result;
        } catch (Throwable t) {
            FileLog.e(t);
            MargeletPluginHost.log("margelet", String.valueOf(t), true);
            return text;
        }
    }

    // --- разговор с сервером ---

    /**
     * Плагин уже внутри одной из дверей этого раздела.
     *
     * Нужно вот зачем: плагин, разглядывая чужой запрос, вполне может послать
     * свой — например, спросить у сервера, кто этот человек. Этот его запрос
     * снова придёт сюда, оттуда снова в питон, и так до упора стека. Пока мы
     * внутри — чужие запросы проходят мимо плагина, как будто он не подписан.
     */
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    private static boolean busy() {
        return Boolean.TRUE.equals(inside.get());
    }

    /**
     * Общий разговор с питоном для трёх дверей ниже.
     *
     * Ответ питона читается так: ничего не вернул — оставить как было, вернул
     * ложь — не пропускать, вернул предмет — отправить его вместо. Три исхода
     * нарочно разные: «не тронул» и «замени на пустоту» — не одно и то же, и
     * на этом различии в форке уже один раз погорели.
     */
    private static Object ask(String method, String about, Class<?>[] types, Object[] args, Object original) {
        if (busy()) {
            return original;
        }
        inside.set(Boolean.TRUE);
        final long started = System.currentTimeMillis();
        try {
            final Object answer = MargeletPluginHost.pythonValue(method, types, args);
            if (answer == null) {
                return original;
            }
            if (Boolean.FALSE.equals(answer)) {
                return null;
            }
            return answer;
        } catch (Throwable t) {
            FileLog.e(t);
            MargeletPluginHost.log("margelet", String.valueOf(t), true);
            return original;
        } finally {
            inside.set(Boolean.FALSE);
            final long spent = System.currentTimeMillis() - started;
            // Здесь питон думает прямо на потоке сети. Своей задержки он не
            // видит, поэтому говорим о ней вслух — иначе автор плагина узнает
            // о ней от людей, у которых «телеграм тормозит».
            if (spent > 50) {
                MargeletPluginHost.log("margelet",
                        about + " думал " + spent + " мс на потоке сети", true);
            }
        }
    }

    /**
     * Запрос к серверу, пока он ещё не ушёл.
     *
     * @return что отправлять вместо него, или null — не отправлять вовсе.
     */
    public static Object requesting(Object request) {
        if (!wantsRequest || request == null) {
            return request;
        }
        return ask("requesting", "обработчик запроса",
                new Class<?>[]{Object.class}, new Object[]{request}, request);
    }

    /**
     * Ответ сервера, пока его ещё не увидело приложение.
     *
     * @return что подсунуть вместо ответа, или null — считать, что ответа нет.
     */
    public static Object answering(Object request, Object response, Object error) {
        if (!wantsAnswer || response == null) {
            return response;
        }
        return ask("answering", "обработчик ответа",
                new Class<?>[]{Object.class, Object.class, Object.class},
                new Object[]{request, response, error}, response);
    }

    /**
     * Обновление с сервера, пока его ещё не разобрало приложение.
     *
     * @return что разбирать вместо него, или null — пропустить совсем.
     */
    public static Object updating(Object update) {
        if (!wantsUpdate || update == null) {
            return update;
        }
        return ask("updating", "обработчик обновления",
                new Class<?>[]{Object.class}, new Object[]{update}, update);
    }

    // --- приход сообщений ---

    /**
     * Ставит наблюдателя за новыми сообщениями — по одному на каждую учётную
     * запись, потому что уведомления в телеграме заведены отдельно на каждую.
     *
     * Второй раз не ставим: два наблюдателя означали бы, что каждое сообщение
     * придёт плагину дважды.
     */
    private static void watch() {
        AndroidUtilities.runOnUIThread(() -> {
            if (watching) {
                return;
            }
            watching = true;
            final NotificationCenter.NotificationCenterDelegate delegate = (id, account, args) -> {
                if (id != NotificationCenter.didReceiveNewMessages || !wantsMessage
                        || args == null || args.length < 2) {
                    return;
                }
                deliver(args);
            };
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NotificationCenter.getInstance(account)
                        .addObserver(delegate, NotificationCenter.didReceiveNewMessages);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void deliver(Object[] args) {
        final long dialogId;
        final ArrayList<MessageObject> messages;
        try {
            dialogId = (Long) args[0];
            messages = (ArrayList<MessageObject>) args[1];
        } catch (Throwable ignored) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MessageObject message : messages) {
            if (message == null) {
                continue;
            }
            final String text = message.messageText == null ? "" : message.messageText.toString();
            final int messageId = message.getId();
            final boolean out = message.isOut();
            MargeletPluginHost.post(() -> {
                try {
                    MargeletPluginHost.python("received",
                            new Class<?>[]{String.class, long.class, int.class, boolean.class},
                            text, dialogId, messageId, out);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }

    // --- работа в стороне и своя отправка ---

    /**
     * Отдельный поток для долгой работы плагина.
     *
     * Появился не из красоты, а по чужому коду. Первые два плагина не от нас —
     * погода и курс валют — оба ходят в сеть прямо внутри обработчика
     * отправки. А обработчик этот исполняется на том же потоке, что рисует
     * экран: пока идёт запрос, телефон не рисует ничего. У погоды это до пяти
     * секунд, у курса — до восемнадцати, три запроса подряд по шесть.
     *
     * Обычно андроид ловит такое сам: полез в сеть с главного потока — сразу
     * падение с понятным объяснением. Здесь эта защита не срабатывает. Она
     * живёт в джавовых сокетах, а питон ходит в сеть своими, мимо джавы, и
     * охранник этого просто не видит. Ни падения, ни предупреждения — телефон
     * молча замирает. Оба автора были уверены, что у них всё хорошо.
     *
     * Значит, виновата не их невнимательность, а то, что замены не было.
     * Теперь есть.
     */
    public static void background(Runnable work) {
        final Thread thread = new Thread(work, "margelet-plugin-work");
        // Приложение не должно ждать закрытия из-за плагина, ушедшего в сеть.
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Отправить сообщение от имени человека. Так плагин отвечает на команду,
     * когда ответ наконец пришёл, — вместо того чтобы держать отправку.
     */
    public static void send(long dialogId, String text) {
        send(dialogId, text, false);
    }

    /**
     * То же, но с разбором разметки: тройные кавычки становятся моноширинным
     * блоком, звёздочки — жирным.
     *
     * Отдельным доводом, а не всегда: у плагина, шлющего чужой текст, звёздочка
     * в нём — просто звёздочка, и превращать её в жирный за его спиной нельзя.
     * Разбор просит тот, кто разметку и написал.
     */
    public static void send(long dialogId, String text, boolean markdown) {
        if (text == null || text.length() == 0 || dialogId == 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final int account = org.telegram.messenger.UserConfig.selectedAccount;
                java.util.ArrayList<org.telegram.tgnet.TLRPC.MessageEntity> entities = null;
                CharSequence body = text;
                if (markdown) {
                    // Разбор съедает сами кавычки и отдаёт разметку отдельно —
                    // ровно то же самое делает поле ввода, когда человек
                    // набирает тройные кавычки руками.
                    final CharSequence[] one = new CharSequence[]{
                            new android.text.SpannableStringBuilder(text)};
                    entities = org.telegram.messenger.MediaDataController
                            .getInstance(account).getEntities(one, true, true);
                    body = one[0];
                }
                final org.telegram.messenger.SendMessagesHelper.SendMessageParams params =
                        org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(
                                body.toString(), dialogId);
                params.entities = entities;
                // Отвечаем туда же, куда отвечал человек. Ответ берётся один
                // раз: второе сообщение плагина ответом уже не будет, иначе
                // плагин, пишущий по будильнику, отвечал бы вечно.
                params.replyToMsg = replyOf.remove(dialogId);
                org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(params);
            } catch (Throwable t) {
                FileLog.e(t);
                MargeletPluginHost.log("margelet", "не отправилось: " + t, true);
            }
        });
    }

    // --- удаление сообщений ---

    private static volatile boolean wantsDeleted;
    private static boolean watchingDeleted;

    public static void wantDeleted() {
        wantsDeleted = true;
        watchDeleted();
    }

    /**
     * Сообщения удалили — у нас или у собеседника.
     *
     * Той самой двери, которой не хватало для «анти-удаления»: плагин узнаёт
     * номера пропавших сообщений и может сохранить их у себя до того, как они
     * исчезнут с экрана.
     */
    private static void watchDeleted() {
        AndroidUtilities.runOnUIThread(() -> {
            if (watchingDeleted) {
                return;
            }
            watchingDeleted = true;
            final NotificationCenter.NotificationCenterDelegate delegate = (id, acc, args) -> {
                if (id != NotificationCenter.messagesDeleted || !wantsDeleted
                        || args == null || args.length < 2) {
                    return;
                }
                try {
                    @SuppressWarnings("unchecked")
                    final ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
                    final long channelId = (Long) args[1];
                    if (ids == null || ids.isEmpty()) {
                        return;
                    }
                    final int[] plain = new int[ids.size()];
                    for (int i = 0; i < ids.size(); i++) {
                        plain[i] = ids.get(i) == null ? 0 : ids.get(i);
                    }
                    MargeletPluginHost.post(() -> {
                        try {
                            MargeletPluginHost.python("deleted",
                                    new Class<?>[]{int[].class, long.class}, plain, channelId);
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    });
                } catch (Throwable ignored) {
                }
            };
            for (int acc = 0; acc < UserConfig.MAX_ACCOUNT_COUNT; acc++) {
                NotificationCenter.getInstance(acc)
                        .addObserver(delegate, NotificationCenter.messagesDeleted);
            }
        });
    }

    // --- закрепление чата ---

    private static volatile boolean wantsPin;

    public static void wantPin() {
        wantsPin = true;
    }

    public static boolean hasPin() {
        return wantsPin;
    }

    /**
     * Чат закрепляют или откепляют. Плагин может это отменить.
     *
     * Это первая дверь не в переписку, а в сам интерфейс: владелец просил
     * уметь менять поведение кнопки закрепления. Названная дверь, а не подмена
     * метода: нажатие приходит сюда, ответ решает, случится ли действие.
     *
     * @return false — не закреплять
     */
    public static boolean pinning(long dialogId, boolean pin) {
        if (!wantsPin) {
            return true;
        }
        try {
            final Object answer = MargeletPluginHost.pythonValue("pinning",
                    new Class<?>[]{long.class, boolean.class}, dialogId, pin);
            return !(answer instanceof Boolean) || (Boolean) answer;
        } catch (Throwable t) {
            FileLog.e(t);
            return true;
        }
    }

    // --- сеть и экран ---

    /**
     * Запрос в сеть, который не может подвесить приложение.
     *
     * Оба первых плагина не от нас писали запрос руками и оба вешали экран:
     * питон ходит в сеть мимо джавы, поэтому обычная андроидовская защита
     * молчит, и подвисание выглядит как «просто тормозит». Дать замену мало —
     * надо, чтобы правильный путь был короче неправильного. Отсюда этот метод:
     * писать его через background и urllib длиннее, чем позвать отсюда.
     *
     * Ответ отдаётся в главный поток. Не получилось — отдаётся null, и это
     * не ошибка плагина: сети может не быть.
     */
    public static void fetch(String url, FetchCallback callback) {
        final Thread worker = new Thread(() -> {
            String result = null;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", MargeletConfig.APP_NAME);
                if (connection.getResponseCode() == 200) {
                    try (java.io.InputStream in = connection.getInputStream()) {
                        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        final byte[] buffer = new byte[8192];
                        int read;
                        // Полтора мегабайта — потолок. Плагину, которому нужно
                        // больше, нужен не этот метод, а своя работа в фоне.
                        while ((read = in.read(buffer)) > 0 && out.size() <= 1536 * 1024) {
                            out.write(buffer, 0, read);
                        }
                        result = out.toString("UTF-8");
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final String delivered = result;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    callback.onResult(delivered);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }, "margelet-plugin-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /** Ответ на запрос. Зовётся в главном потоке; text — null, если не вышло. */
    public interface FetchCallback {
        void onResult(String text);
    }

    /**
     * Текущий экран приложения.
     *
     * Без него плагин, которому нужно что-нибудь показать, лезет во внутренние
     * поля приложения по имени — как пришлось делать мне же в плагине с
     * играми. Пусть лучше будет названный способ.
     */
    public static android.app.Activity activity() {
        try {
            return org.telegram.ui.LaunchActivity.instance;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Показать окно с тем, что плагин собрал сам.
     *
     * Рамку — заголовок, кнопку «Закрыть», тему — берёт на себя приложение,
     * поэтому окно плагина выглядит как окно приложения, а не как чужая
     * вставка.
     */
    public static void window(String title, android.view.View view) {
        AndroidUtilities.runOnUIThread(() -> {
            final android.app.Activity activity = activity();
            if (activity == null || view == null) {
                return;
            }
            try {
                new org.telegram.ui.ActionBar.AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setView(view)
                        .setNegativeButton(org.telegram.messenger.LocaleController
                                .getString(org.telegram.messenger.R.string.Close), null)
                        .show();
            } catch (Throwable t) {
                FileLog.e(t);
                MargeletPluginHost.log("margelet", "окно не открылось: " + t, true);
            }
        });
    }

    /** Цвет для андроида: там он знаковый, а из питона приходит без знака. */
    public static int color(long argb) {
        return (int) argb;
    }

    // --- настройки плагина ---

    /**
     * Плагин заявляет, из чего состоит его экран настроек. Заявка хранится
     * рядом с его памятью, а не в оперативной: экран настроек надо уметь
     * открыть и у выключенного плагина, который сейчас не выполняется.
     */
    public static void declare(String pluginId, String json) {
        MargeletPluginHost.set(pluginId, "__settings", json);
    }

    public static String declared(String pluginId) {
        return MargeletPluginHost.get(pluginId, "__settings", null);
    }

    public static boolean hasSettings(String pluginId) {
        final String json = declared(pluginId);
        return json != null && json.length() > 2;
    }

    /** Человек поменял настройку. Плагин узнаёт об этом сразу, без перезапуска. */
    public static void settingsChanged(String pluginId, String key, String value) {
        MargeletPluginHost.post(() -> {
            try {
                MargeletPluginHost.python("settingsChanged",
                        new Class<?>[]{String.class, String.class, String.class},
                        pluginId, key, value);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }
}
