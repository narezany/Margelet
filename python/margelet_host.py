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

from java import dynamic_proxy, jclass
from java.lang import Runnable

_Host = jclass("org.telegram.margelet.MargeletPluginHost")
_Hooks = jclass("org.telegram.margelet.MargeletHooks")
_Fetch = jclass("org.telegram.margelet.MargeletHooks$FetchCallback")
_Android = jclass("org.telegram.messenger.AndroidUtilities")
_MargeletHook = jclass("org.telegram.margelet.hook.MargeletHook")
_IHookCallback = jclass("org.telegram.margelet.hook.IHookCallback")

# Движок хуков пока ни к одному методу приложения не подключён: хук
# сохраняется, но точек, где приложение его спросило бы, в коде нет. Один
# раз говорим об этом вслух, чтобы автор плагина не ждал перехвата.
_hook_warning_shown = False


def _warn_hooks_once(name):
    global _hook_warning_shown
    if not _hook_warning_shown:
        _hook_warning_shown = True
        _Host.log("margelet",
                  "движок хуков сохраняет хуки, но ещё не вызывает их: "
                  "в приложении нет ни одной точки вызова. Перехват заработает не сейчас",
                  True)

# Ответ, по которому приложение понимает «не отправляй это сообщение».
# Такой же строки нет в MargeletHooks.CANCEL по случайности: она там же и
# записана, и обычным текстом её не набрать.
_CANCEL = "\u0000margelet-cancel"


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
        self._on_message = []
        self._on_deleted = []
        self._on_settings = []
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

    def on_message(self, call):
        """Позвать, когда пришло сообщение: call(text, dialog_id, message_id, outgoing).

        Приходят и свои отправленные — на то и outgoing, чтобы их отличить.
        Ответ ни на что не влияет: сообщение уже пришло.
        """
        self._on_message.append(call)
        _Hooks.wantMessage()

    def on_deleted(self, call):
        """Позвать, когда собеседник или сервер удалил сообщение: call(message_id, channel_id)."""
        self._on_deleted.append(call)
        _Hooks.wantMessage()

    def button(self, title, call, key=None):
        """Своя строчка в меню чата (три точки). Нажали — зовём call(fragment)."""
        key = str(key or title)
        self._buttons[key] = call
        _Hooks.addButton(self.id, key, str(title))

    def on_settings(self, call):
        """Позвать, когда человек поменял настройку: call(key, value)."""
        self._on_settings.append(call)

    # --- Динамические хуки Java-методов (MargeletHook Engine) ---

    def hook(self, target_class, method_name, before=None, after=None, priority=50):
        """Хук Java-метода через MargeletHook Engine.

        :param target_class: имя класса (строка) или Java-класс
        :param method_name: имя метода
        :param before: callback(param) перед выполнением метода
        :param after: callback(param) после выполнения метода
        """
        name = self.name
        _warn_hooks_once(name)
        plugin_id = self.id

        if isinstance(target_class, str):
            clazz = _MargeletHook.findClass(target_class, None)
        else:
            clazz = target_class

        if clazz is None:
            _Host.log(name, f"Класс {target_class} не найден для хука {method_name}", True)
            return None

        method = _MargeletHook.findMethod(clazz, str(method_name), None)
        if method is None:
            _Host.log(name, f"Метод {method_name} не найден в {target_class}", True)
            return None

        class _PyHookCallback(dynamic_proxy(_IHookCallback)):
            def beforeHookedMethod(self, param):
                if before is not None:
                    try:
                        before(param)
                    except Exception:
                        _Host.log(name, traceback.format_exc(), True)

            def afterHookedMethod(self, param):
                if after is not None:
                    try:
                        after(param)
                    except Exception:
                        _Host.log(name, traceback.format_exc(), True)

        return _MargeletHook.hookMethod(plugin_id, method, _PyHookCallback())

    def before_method(self, target_class, method_name, priority=50):
        """Декоратор для вызова перед методом."""
        def decorator(func):
            self.hook(target_class, method_name, before=func, priority=priority)
            return func
        return decorator

    def after_method(self, target_class, method_name, priority=50):
        """Декоратор для вызова после метода."""
        def decorator(func):
            self.hook(target_class, method_name, after=func, priority=priority)
            return func
        return decorator

    def hook_method(self, target_class, method_name, priority=50):
        """Декоратор для перехвата метода (по умолчанию before)."""
        return self.before_method(target_class, method_name, priority)

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


def received(text, dialog_id, message_id, out):
    """Пришло сообщение."""
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_message):
            try:
                call(text, dialog_id, message_id, out)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)


def deleted(message_id, channel_id):
    """Удалено сообщение."""
    for margelet in list(_margelets.values()):
        for call in list(margelet._on_deleted):
            try:
                call(message_id, channel_id)
            except Exception:
                _Host.log(margelet.name, traceback.format_exc(), True)


def button_clicked(plugin_id, key, fragment):
    """Нажали строчку плагина в меню чата."""
    margelet = _margelets.get(plugin_id)
    if margelet is None:
        return
    call = margelet._buttons.get(key)
    if call is None:
        return
    try:
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
