# -*- coding: utf-8 -*-
"""Прослойка между приложением и плагинами Margelet.

Каждый плагин исполняется здесь: печать перехватывается и уходит в консоль
приложения, ошибки не роняют ни другие плагины, ни само приложение.

Плагину доступен модуль margelet — через него он и живёт: пишет в консоль,
хранит свои настройки, вешается на события. Всё, что он делает, видно в коде
самого плагина: код едет исходником и не пакуется, это условие форума.
"""

import importlib.util
import io
import json
import sys
import traceback

from java import dynamic_proxy, jarray, jclass
from java.lang import Runnable

_Host = jclass("org.telegram.margelet.MargeletPluginHost")
_Hooks = jclass("org.telegram.margelet.MargeletHooks")
_Engine = jclass("org.telegram.margelet.MargeletHookEngine")
_HookCall = jclass("org.telegram.margelet.MargeletHookCall")
_Files = jclass("org.telegram.margelet.MargeletFiles")
_Picked = jclass("org.telegram.margelet.MargeletFiles$Picked")
_Fetch = jclass("org.telegram.margelet.MargeletHooks$FetchCallback")
_Android = jclass("org.telegram.messenger.AndroidUtilities")

# Ответ, по которому приложение понимает «не отправляй это сообщение».
# Такой же строки нет в MargeletHooks.CANCEL по случайности: она там же и
# записана, и обычным текстом её не набрать.
_CANCEL = "\u0000margelet-cancel"

# Места, куда плагин может поставить свою строчку. Те же слова, что и в
# MargeletHooks: список один на два языка, и расходиться им нельзя.
_CHAT = "chat"
_PLACES = (_CHAT, "profile", "message", "drawer")


class _Task(dynamic_proxy(Runnable)):
    """Питоновская работа, которую можно отдать андроиду.

    Повторяющаяся сама ставит себя заново: так плагину не приходится знать
    про очереди и обёртки, ему достаточно margelet.every.
    """

    def __init__(self, call, repeat_ms=None, name="плагин"):
        super().__init__()
        self._call = call
        self._repeat = repeat_ms
        self._name = name
        self.cancelled = False

    def run(self):
        if self.cancelled:
            return
        try:
            self._call()
        except Exception:
            _Host.log(self._name, traceback.format_exc(), True)
            return          # сломанное не повторяем бесконечно
        if self._repeat and not self.cancelled:
            _Android.runOnUIThread(self, self._repeat)

# Загруженные плагины: номер -> модуль. Нужен, чтобы второй запуск не плодил
# копии одного и того же.
_loaded = {}


class _Console:
    """Печать плагина уходит в консоль приложения, а не в никуда."""

    def __init__(self, name, error=False):
        self._name = name
        self._error = error
        self._buffer = ""

    def write(self, text):
        self._buffer += text
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            if line:
                _Host.log(self._name, line, self._error)

    def flush(self):
        if self._buffer:
            _Host.log(self._name, self._buffer, self._error)
            self._buffer = ""


class Margelet:
    """То, что плагин видит под именем margelet."""

    def __init__(self, plugin_id, name, folder):
        self.id = plugin_id
        self.name = name
        self.folder = folder
        self._on_chat_opened = []
        self._on_send = []
        self._on_send_photo = []
        self._on_message = []
        self._on_settings = []
        self._on_deleted = []
        self._on_pin = []
        self._on_request = []
        self._on_answer = []
        self._on_update = []
        self._buttons = {}
        self._actions = {}
        self._cancel_send = False

    def log(self, *parts):
        _Host.log(self.name, " ".join(str(p) for p in parts), False)

    def error(self, *parts):
        _Host.log(self.name, " ".join(str(p) for p in parts), True)

    # --- что умеет плагин, кроме печати ---

    def ui(self, call, delay_ms=0):
        """Выполнить на главном потоке: всё, что трогает экран, только оттуда."""
        task = _Task(call, None, self.name)
        _Android.runOnUIThread(task, delay_ms)
        return task

    def every(self, ms, call):
        """Повторять каждые ms миллисекунд. Остановить — margelet.cancel(...)."""
        task = _Task(call, ms, self.name)
        _Android.runOnUIThread(task, ms)
        return task

    def cancel(self, task=None):
        """Прекратить повтор, поставленный every или ui.

        Аргумент необязателен не по доброте. Автор первого стороннего плагина
        написал margelet.cancel() в обработчике отправки, имея в виду «не
        отправляй это». Вызов падал, ошибка уходила в лог, который автор не
        открывал, а сообщение уходило в чат как есть — то есть в переписку
        улетала сама команда. Название виновато, а не он: теперь без аргумента
        это значит ровно то, за чем к нему потянулись.
        """
        if task is None:
            # Возврата мало: в том самом плагине cancel() вызвали, а результат
            # не вернули — функция закончилась молчанием, и команда всё равно
            # улетала в чат. Поэтому отмену запоминаем, а не только отдаём.
            self._cancel_send = True
            return False
        task.cancelled = True
        return None

    def toast(self, text):
        """Короткая надпись поверх экрана."""
        _Host.toast(str(text))

    def get(self, key, fallback=None):
        """Своя память плагина. Переживает и перезапуск, и обновление плагина."""
        value = _Host.get(self.id, str(key), None)
        return fallback if value is None else value

    def set(self, key, value):
        _Host.set(self.id, str(key), None if value is None else str(value))

    # --- хуки: подмена чужих методов ---

    def hooks_work(self):
        """Работают ли хуки на этом телефоне.

        Спрашивать стоит до того, как на них рассчитывать: движок правит
        внутренности исполнителя java, а те у каждой версии андроида свои.
        Не поднялся — плагин должен уметь обойтись, а не сломаться.
        """
        return bool(_Engine.working())

    def hooks_why(self):
        """Почему не работают, если не работают."""
        if _Engine.working():
            return ""
        if not _Engine.enabled():
            return "выключены в настройках"
        return str(_Engine.failure() or "не поднялись")

    def hook(self, where, method, before=None, after=None, args=None):
        """Подменить чужой метод.

        where  — класс: имя строкой, например "org.telegram.ui.ChatActivity"
        method — имя метода
        args   — типы доводов, если метод перегружен: ["int", "java.lang.String"]
        before — позвать до вызова; может отменить его, вернув значение
        after  — позвать после; может подменить ответ

        Обработчик получает один довод — param, у него есть:
            param.args          доводы вызова, их можно менять
            param.thisObject    у какого объекта позвали
            param.getResult()   ответ (в after)
            param.setResult(x)  подменить ответ; в before это ещё и отмена вызова

        Возвращает False, если подменить не вышло, — и тогда причина уже
        написана в консоли. Молча делать вид, что подменили, нельзя: плагин
        будет думать, что работает, а он не работает.
        """
        if not _Engine.working():
            self.error("хуки не работают:", self.hooks_why())
            return False

        outer = self

        # Наследуем интерфейс, а не абстрактный класс: мост питона умеет
        # подставлять свои объекты только под интерфейсы java. Раньше здесь
        # стоял XC_MethodHook, и первый же плагин, попробовавший хуки, получил
        # «is not a Java interface». То есть возможность была выпущена и ни
        # разу не выполнилась — ровно та ошибка, про которую у нас записано
        # правило, и я наступил на неё второй раз.
        class Hook(dynamic_proxy(_HookCall)):
            def before(self, param):
                if before is not None:
                    try:
                        before(param)
                    except Exception:
                        _Host.log(outer.name, traceback.format_exc(), True)

            def after(self, param):
                if after is not None:
                    try:
                        after(param)
                    except Exception:
                        _Host.log(outer.name, traceback.format_exc(), True)

        try:
            types = jarray(jclass("java.lang.Object"))(list(args or []))
            if _Engine.hook(str(where), str(method), types, Hook()):
                return True
            self.error("метод не подменился:", where, method)
            return False
        except Exception:
            _Host.log(self.name, traceback.format_exc(), True)
            return False

    def pick_file(self, call, types=None):
        """Спросить у человека файл и получить путь к нему.

        call(путь) — позовут, когда выберут. Передумали или не вышло — путь
        придёт None; обработчик зовут в любом случае, чтобы плагин не остался
        ждать ответа, которого не будет.

        types — что показывать в проводнике: "image/*", "text/plain". Пусто —
        любые файлы.

        Выбранное копируется в папку этого плагина, и путь — на копию.
        Проводник отдаёт не путь, а адрес с временным правом на чтение: оно
        живёт до перезапуска и из питона не открывается. Копия — то, чем
        плагин сможет пользоваться дальше.

        Собирать Intent руками не надо и не выйдет: мост питона выбирает
        конструктор отражением и добирается до скрытого Intent(Parcel),
        которого на деле нет. Эта дверь и сделана затем, чтобы туда не лезть.
        """
        outer = self

        class Answer(dynamic_proxy(_Picked)):
            def onPicked(self, path):
                try:
                    call(str(path) if path is not None else None)
                except Exception:
                    _Host.log(outer.name, traceback.format_exc(), True)

        try:
            _Files.pick(self.id, str(types or ""), Answer())
            return True
        except Exception:
            _Host.log(self.name, traceback.format_exc(), True)
            return False

    def background(self, call):
        """Сделать что-то долгое в стороне от экрана.

        Всё, что ходит в сеть или читает большой файл, должно жить здесь.
        Прямо в обработчике отправки этого делать нельзя: пока он думает,
        телефон не рисует ничего, а привычной андроидовской защиты «полез в
        сеть с главного потока — упади» тут нет. Питон ходит в сеть мимо
        джавы, и охранник его не видит: приложение не падает, оно замирает.
        """
        _Hooks.background(_Task(call, None, self.name))

    def send(self, chat, text):
        """Отправить сообщение в переписку. Так плагин отвечает на команду.

        Обычный порядок для команды: увидел её в on_send, вернул False, ушёл
        в margelet.background, а когда ответ пришёл — отправил его отсюда.
        """
        _Hooks.send(int(chat), str(text))

    def dont_send(self):
        """Не отправлять то, что человек набрал. То же, что вернуть False.

        Заведено потому, что за этим тянулись рукой к margelet.cancel — а
        cancel про другое, он останавливает повтор. Слово нашлось раньше,
        чем правило, значит слово и надо было дать.
        """
        self._cancel_send = True
        return False

    def fetch(self, url, then):
        """Сходить в сеть и позвать then(текст). Экран при этом не замирает.

        Не получилось — придёт None. Это не ошибка плагина: сети может не
        быть, и обрабатывать это должен сам плагин.

        Писать то же самое через background и urllib длиннее, и потому здесь
        есть этот способ: правильный путь должен быть короче неправильного.
        """
        name = self.name

        class Answer(dynamic_proxy(_Fetch)):
            def onResult(self, text):
                try:
                    then(text)
                except Exception:
                    _Host.log(name, traceback.format_exc(), True)

        _Hooks.fetch(str(url), Answer())

    def activity(self):
        """Текущий экран приложения. Нужен, чтобы строить свои виды."""
        return _Hooks.activity()

    def window(self, title, view):
        """Показать окно с тем, что плагин собрал сам.

        Заголовок, кнопку «Закрыть» и тему берёт на себя приложение, поэтому
        окно плагина выглядит как окно приложения.
        """
        _Hooks.window(str(title), view)

    def color(self, argb):
        """Цвет для андроида.

        В java цвет — знаковое 32-битное число, и всё непрозрачное в нём
        отрицательное. Питон считает 0xFFFFFFFF просто большим числом, и мост
        отказывается его превращать — «value too large to convert to int32_t».
        Падает это на первом же цвете, то есть не появляется вообще ничего.
        """
        return _Hooks.color(int(argb))

    def flag(self, key, fallback=False):
        """Прочитать переключатель с экрана настроек как да/нет."""
        value = _Host.get(self.id, str(key), None)
        return fallback if value is None else value == "1"

    # --- события ---

    def on_chat_opened(self, call):
        """Позвать, когда человек открыл переписку. Передаётся сам экран чата."""
        self._on_chat_opened.append(call)

    def on_send(self, call):
        """Позвать перед отправкой текста: call(text, dialog_id).

        Что вернуть:
          строку — она и уйдёт вместо набранного;
          False  — не отправлять вовсе;
          ничего — оставить как есть.

        Это единственное событие, которого приложение ждёт: пока обработчик
        думает, человек смотрит на неотправленное сообщение. Долгую работу
        отсюда надо уносить в margelet.ui или margelet.every.
        """
        self._on_send.append(call)
        _Hooks.wantSend()

    def on_send_photo(self, call):
        """Позвать перед отправкой картинки: call(path, caption, dialog_id).

        path — файл на диске, caption — подпись, как её набрали.

        Что вернуть:
          строку — картинка не уйдёт, вместо неё уйдёт этот текст;
          False  — не отправлять ни картинку, ни текст;
          ничего — оставить как есть, картинка уйдёт обычным путём.

        Текст можно обернуть в тройные кавычки — тогда он уйдёт моноширинным
        блоком. Для рисунков из знаков это не украшение: в обычном шрифте буквы
        разной ширины, и любая картинка из них разъезжается.

        Зовут не из главного потока, поэтому долгая работа здесь допустима —
        в отличие от on_send, где человек смотрит на неотправленное сообщение.
        """
        self._on_send_photo.append(call)
        _Hooks.wantMedia()

    def on_message(self, call):
        """Позвать, когда пришло сообщение: call(text, dialog_id, message_id, outgoing).

        Приходят и свои отправленные — на то и outgoing, чтобы их отличить.
        Ответ ни на что не влияет: сообщение уже пришло.
        """
        self._on_message.append(call)
        _Hooks.wantMessage()

    def button(self, title, call, key=None):
        """Своя строчка в меню чата (три точки). Нажали — зовём call(fragment).

        Короткая запись для margelet.menu("chat", ...): меню чата было первым
        и остаётся самым частым.
        """
        return self.menu(_CHAT, title, call, key)

    def menu(self, where, title, call, key=None):
        """Своя строчка в меню приложения.

        where — где стоять:
          "chat"     три точки в шапке переписки; call(экран)
          "profile"  три точки на экране человека, группы или канала;
                     call(экран, номер) — номер того, чей это профиль
          "message"  долгое нажатие на сообщение; call(экран, сообщение)
          "drawer"   боковое меню, которое выезжает слева; call(экран)

        Что придёт вторым доводом — зависит от места, и по-другому не выйдет:
        в профиле предмет есть, в боковом меню его нет. Обработчик пишется под
        своё место, а не под все сразу.
        """
        where = str(where)
        if where not in _PLACES:
            self.error("не знаю такого меню:", where,
                       "— бывают", ", ".join(sorted(_PLACES)))
            return
        key = str(key or title)
        self._buttons[where + "\u0000" + key] = (call, where)
        _Hooks.addMenuItem(self.id, key, str(title), where)

    def on_request(self, call):
        """Позвать перед каждым запросом к серверу: call(запрос).

        Запрос — тот самый объект TL, что уйдёт на сервер. Что вернуть:
          False   — не отправлять его вовсе;
          объект  — отправить его вместо;
          ничего  — оставить как есть.

        Через эту дверь идёт ВСЁ, что клиент говорит серверу, — десятки
        запросов в минуту, и обработчик думает прямо на потоке сети. Значит:
        сначала посмотреть, тот ли это запрос, и только потом что-то делать.
        Долгую работу уносить в margelet.background.
        """
        self._on_request.append(call)
        _Hooks.wantRequest()

    def on_answer(self, call):
        """Позвать на ответ сервера: call(запрос, ответ, ошибка).

        Ошибка — None, если её не было. Возвращать можно то же, что и в
        on_request: объект вместо ответа, False — считать, что ответа нет,
        ничего — оставить как есть.
        """
        self._on_answer.append(call)
        _Hooks.wantAnswer()

    def on_update(self, call):
        """Позвать на каждое обновление с сервера: call(обновление).

        Это то, из чего приложение узнаёт вообще обо всём: новые сообщения,
        правки, прочтения, кто печатает. Поток плотный, и правила те же, что
        у on_request.
        """
        self._on_update.append(call)
        _Hooks.wantUpdate()

    def on_deleted(self, call):
        """Позвать, когда сообщения удалили: call(номера, чат).

        Номера — список, чат — номер канала или ноль для обычной переписки.
        Само сообщение к этому времени уже пропало: если плагин хочет его
        сохранить, он должен был запомнить его раньше, в on_message.
        """
        self._on_deleted.append(call)
        _Hooks.wantDeleted()

    def on_pin(self, call):
        """Позвать, когда чат закрепляют или открепляют: call(чат, закрепляют).

        Вернуть False — не закреплять. Это первая дверь не в переписку, а в
        сам интерфейс приложения.

        Приложение ждёт ответа, как и на отправке: пока обработчик думает,
        человек смотрит на нажатую кнопку. Долгую работу отсюда уносить в
        margelet.background.
        """
        self._on_pin.append(call)
        _Hooks.wantPin()

    def on_settings(self, call):
        """Позвать, когда человек поменял настройку: call(key, value)."""
        self._on_settings.append(call)

    # --- из чего собрать свой экран настроек ---

    def header(self, title):
        """Заголовок раздела."""
        return {"kind": "header", "title": str(title)}

    def note(self, text):
        """Пояснение серым под предыдущими строками."""
        return {"kind": "note", "title": str(text)}

    def switch(self, key, title, default=False, about=None):
        """Переключатель. Читается через margelet.flag(key)."""
        row = {"kind": "switch", "key": str(key), "title": str(title),
               "default": "1" if default else "0"}
        if about:
            row["about"] = str(about)
        return row

    def text(self, key, title, default="", about=None):
        """Строка, которую человек вписывает сам. Читается через margelet.get(key)."""
        row = {"kind": "text", "key": str(key), "title": str(title),
               "default": str(default)}
        if about:
            row["about"] = str(about)
        return row

    def choice(self, key, title, options, default=None):
        """Выбор одного из нескольких. Читается через margelet.get(key)."""
        options = [str(o) for o in options]
        return {"kind": "choice", "key": str(key), "title": str(title),
                "options": options,
                "default": str(default if default is not None
                               else (options[0] if options else ""))}

    def action(self, title, call, key=None, danger=False):
        """Кнопка, которая просто что-то делает: сброс, очистка, проверка."""
        key = str(key or title)
        self._actions[key] = call
        return {"kind": "action", "key": key, "title": str(title),
                "danger": bool(danger)}

    def settings(self, *rows):
        """Заявить свой экран настроек. Зовётся один раз, при запуске.

        Заявка уходит в память плагина, а не остаётся в оперативной: экран
        настроек нужно уметь открыть и у выключенного плагина, который сейчас
        не выполняется. Значения по умолчанию проставляем сразу — иначе
        первое чтение вернёт пустоту, хотя человек ничего не менял.
        """
        rows = [r for r in rows if r]
        for row in rows:
            key = row.get("key")
            if key and "default" in row and _Host.get(self.id, key, None) is None:
                _Host.set(self.id, key, row["default"])
        _Hooks.declare(self.id, json.dumps(rows, ensure_ascii=False))
        return rows


# Кому раздавать события: номер плагина -> его объект margelet.
_margelets = {}


def chat_opened(fragment):
    """Человек открыл переписку. Разносим по подписавшимся плагинам.

    Ошибка одного плагина не должна отменить остальных: каждый зовётся
    отдельно, и упавший получает свой разбор в консоли.
    """
    for plugin_id, margelet in list(_margelets.items()):
        for call in list(margelet._on_chat_opened):
            try:
                call(fragment)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)


def sendingMedia(path, caption, dialog_id):
    """Перед отправкой картинки. Первый, кто взялся, её и забирает.

    В отличие от текста, здесь обработчики не выстраиваются в цепочку:
    заменить картинку можно только один раз, и делить её между двумя плагинами
    не на что.
    """
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_send_photo):
            margelet._cancel_send = False
            try:
                answer = call(path, caption, dialog_id)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)
                continue
            if answer is False or margelet._cancel_send:
                return _CANCEL
            if isinstance(answer, str):
                return answer
    return None


def sending(text, dialog_id):
    """Перед отправкой. Обработчики зовутся по очереди, каждый видит текст
    после предыдущего: так два плагина не отменяют работу друг друга.

    Упавший обработчик пропускаем — сломанный плагин не должен запирать
    человеку отправку сообщений.
    """
    result = text
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_send):
            margelet._cancel_send = False
            try:
                answer = call(result, dialog_id)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)
                continue
            # Отмена засчитывается и когда её вернули, и когда о ней просто
            # сказали: margelet.cancel() или margelet.dont_send() внутри
            # обработчика значат то же самое, что return False.
            if answer is False or margelet._cancel_send:
                return _CANCEL
            if isinstance(answer, str):
                result = answer
    return result


def _through(handlers_of, thing):
    """Прогнать предмет через обработчики всех плагинов.

    Один порядок на все три двери сети: каждый следующий видит то, что вернул
    предыдущий; False — не пропускать; упавший обработчик пропускаем, потому
    что сломанный плагин не должен отрезать приложение от сервера.
    """
    result = thing
    for margelet in list(_margelets.values()):
        for call in list(handlers_of(margelet)):
            try:
                answer = call(result)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)
                continue
            if answer is False:
                return False
            if answer is not None:
                result = answer
    # Не тронули — отвечаем молчанием: джава по нему и понимает, что вмешательства
    # не было, и не тратится на обратное превращение объекта.
    return None if result is thing else result


def requesting(request):
    """Запрос к серверу, пока он ещё не ушёл."""
    return _through(lambda m: m._on_request, request)


def answering(request, response, error):
    """Ответ сервера, пока его ещё не увидело приложение."""
    result = response
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_answer):
            try:
                answer = call(request, result, error)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)
                continue
            if answer is False:
                return False
            if answer is not None:
                result = answer
    return None if result is response else result


def updating(update):
    """Обновление с сервера, пока его ещё не разобрало приложение."""
    return _through(lambda m: m._on_update, update)


def received(text, dialog_id, message_id, out):
    """Пришло сообщение."""
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_message):
            try:
                call(text, dialog_id, message_id, out)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)


def deleted(ids, chat):
    """Сообщения удалили. Ответ ни на что не влияет: их уже нет."""
    numbers = [int(i) for i in ids]
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_deleted):
            try:
                call(numbers, chat)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)


def pinning(chat, pin):
    """Чат закрепляют. Любой обработчик может это отменить, вернув False."""
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_pin):
            try:
                if call(chat, pin) is False:
                    return False
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)
    return True


def menu_clicked(plugin_id, key, where, fragment, target):
    """Нажали строчку плагина в одном из меню.

    Доводов у обработчика столько, сколько есть смысла: в меню чата и в
    боковом меню предмета нет, и подсовывать туда None значило бы заставить
    каждого автора писать лишний довод ради пустоты.
    """
    margelet = _margelets.get(plugin_id)
    if margelet is None:
        return
    found = margelet._buttons.get(str(where) + "\u0000" + key)
    if found is None:
        return
    call, place = found
    try:
        if place in ("profile", "message"):
            call(fragment, target)
        else:
            call(fragment)
    except Exception:
        _Host.log(margelet.name, traceback.format_exc(), True)


def settings_changed(plugin_id, key, value):
    """Человек тронул настройку. Сначала кнопки-действия, потом подписчики."""
    margelet = _margelets.get(plugin_id)
    if margelet is None:
        return
    action = margelet._actions.get(key)
    if action is not None:
        try:
            action()
        except Exception:
            _Host.log(margelet.name, traceback.format_exc(), True)
        return
    for call in list(margelet._on_settings):
        try:
            call(key, value)
        except Exception:
            _Host.log(margelet.name, traceback.format_exc(), True)


def run_plugin(plugin_id, name, folder):
    """Запускает main.py плагина. Ошибка плагина остаётся ошибкой плагина.

    Второй раз один и тот же плагин не запускается. Проверка эта была
    задумана с самого начала — про неё даже написано у _loaded, — но написана
    не была: словарь заполнялся и не читался никогда. Заметно это стало,
    когда плагин поставили поверх уже стоящего: старый продолжал работать,
    новый запускался рядом, и всё, что плагин делает, начинало делаться
    дважды. Остановить уже работающий питон нечем, поэтому единственный
    честный ответ — не запускать второй раз.
    """
    if plugin_id in _loaded:
        _Host.log(name, "уже запущен, второй раз не поднимаю", False)
        return
    out, err = sys.stdout, sys.stderr
    sys.stdout = _Console(name, False)
    sys.stderr = _Console(name, True)
    try:
        if folder not in sys.path:
            sys.path.insert(0, folder)
        spec = importlib.util.spec_from_file_location(
            "margelet_plugin_" + plugin_id, folder + "/main.py")
        module = importlib.util.module_from_spec(spec)
        module.margelet = Margelet(plugin_id, name, folder)
        _margelets[plugin_id] = module.margelet
        spec.loader.exec_module(module)
        _loaded[plugin_id] = module
        if hasattr(module, "on_start"):
            module.on_start()
        _Host.log(name, "запущен", False)
    except Exception as error:
        # Первый кадр разбора — сам этот файл, автору плагина он ничего не
        # говорит. Показываем только то, что в его коде.
        frames = error.__traceback__
        if frames is not None and frames.tb_next is not None:
            frames = frames.tb_next
        _Host.log(name, "".join(
            traceback.format_exception(type(error), error, frames)), True)
    finally:
        sys.stdout.flush()
        sys.stderr.flush()
        sys.stdout, sys.stderr = out, err
