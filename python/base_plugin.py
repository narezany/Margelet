# -*- coding: utf-8 -*-
"""Слой совместимости: плагины, написанные под exteraGram, в Margy.

Их плагин — это класс, унаследованный от BasePlugin, и набор обёрток вокруг
той же самой java, что доступна и нам: у них и у нас питон живёт в одном и том
же приложении телеграма через Chaquopy. Поэтому переводить плагин целиком не
надо — достаточно положить рядом модули с их именами, которые делают то же
самое через margelet.

Что здесь честно эмулируется, а что нет — написано у каждой части. Там, где
сделать нельзя, стоит внятная ошибка, а не тихое «ничего не произошло»:
плагин, который молча не работает, хуже плагина, который сразу сказал почему.
"""

_мост = None          # объект margelet того плагина, который сейчас поднимают
_классы = []          # все BasePlugin-наследники, объявленные в файле плагина
_живые = []           # их поднятые образцы


class AppEvent:
    START = "start"
    STOP = "stop"
    PAUSE = "pause"
    RESUME = "resume"


class HookStrategy:
    DEFAULT = 0
    CANCEL = 1
    MODIFY = 2
    MODIFY_FINAL = 3


class HookResult:
    def __init__(self, strategy=HookStrategy.DEFAULT, params=None):
        self.strategy = strategy
        self.params = params


class MenuItemType:
    """Их места меню — наши же, просто под другими именами.

    Ровно четыре, потому что ровно столько умеет margelet.menu. Их пятое
    место, CHAT_ATTACH_MENU, у нас не открыто, и притворяться нечем.
    """
    CHAT_ACTION_MENU = "chat"
    PROFILE_ACTION_MENU = "profile"
    MESSAGE_CONTEXT_MENU = "message"
    DRAWER_MENU = "drawer"


class MenuItemData:
    def __init__(self, menu_type, text, on_click=None, icon=None, subtext=None,
                 priority=0, condition=None):
        self.menu_type = menu_type
        self.text = text
        self.on_click = on_click
        self.icon = icon
        self.subtext = subtext
        self.priority = priority
        self.condition = condition


class Параметры:
    """То, что их обработчик отправки ждёт под именем params.

    У них это объект запроса к серверу с полем message. У нас на этом месте
    просто текст и номер переписки, поэтому объект собираем сами — иначе их
    код, читающий params.message, не заработал бы вовсе.
    """

    def __init__(self, message, peer):
        self.message = message
        self.peer = peer


class BasePlugin:
    def __init_subclass__(cls, **прочее):
        super().__init_subclass__(**прочее)
        _классы.append(cls)

    # --- то, что переопределяет сам плагин ---

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type):
        pass

    def create_settings(self):
        return []

    # --- то, чем плагин пользуется ---

    @property
    def id(self):
        return _мост.id

    def get_setting(self, key, default=None):
        значение = _мост.get(key, None)
        if значение is None:
            return default
        # У них переключатель хранится как да/нет, у нас всё строкой.
        if isinstance(default, bool):
            return значение == "1"
        if isinstance(default, int) and not isinstance(default, bool):
            try:
                return int(значение)
            except ValueError:
                return default
        return значение

    def set_setting(self, key, value, reload_settings=False):
        if isinstance(value, bool):
            value = "1" if value else "0"
        _мост.set(key, value)

    def log(self, *части):
        _мост.log(*части)

    def add_menu_item(self, data):
        место = getattr(data, "menu_type", None)
        if место not in ("chat", "profile", "message", "drawer"):
            _мост.error("не знаю такого меню:", место)
            return

        обработчик = data.on_click

        def нажали(экран, предмет=None):
            if обработчик is None:
                return
            # У них обработчик берёт один довод — словарь с тем, на чём
            # открыли меню. Собираем его из того, что даёт margelet.
            обработчик({
                "fragment": экран,
                "peer": предмет if место == "profile" else None,
                "message": предмет if место == "message" else None,
            })

        if место in ("profile", "message"):
            _мост.menu(место, data.text, нажали)
        else:
            _мост.menu(место, data.text, lambda экран: нажали(экран))

    def add_on_send_message_hook(self, priority=0):
        if not hasattr(self, "on_send_message_hook"):
            _мост.error("плагин попросил дверь отправки, но обработчика "
                        "on_send_message_hook у него нет")
            return

        def отправляют(текст, куда):
            ответ = self.on_send_message_hook(
                _аккаунт(), Параметры(текст, куда))
            if ответ is None:
                return None
            стратегия = getattr(ответ, "strategy", HookStrategy.DEFAULT)
            if стратегия == HookStrategy.CANCEL:
                return False
            if стратегия in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
                параметры = getattr(ответ, "params", None)
                return getattr(параметры, "message", None)
            return None

        _мост.on_send(отправляют)

    def add_hook(self, name, match_substring=False, priority=0):
        """Перехват чужих запросов и обновлений по имени класса TL.

        У них имя — это строка вроде «messages.sendMessage». У нас двери
        общие, поэтому подписываемся на все и отбираем по имени сами.
        """
        имя = str(name)

        def подходит(предмет):
            try:
                своё = предмет.getClass().getSimpleName()
            except Exception:
                return False
            return имя in своё if match_substring else своё.endswith(
                имя.split(".")[-1])

        if hasattr(self, "pre_request_hook"):
            def запрос(предмет):
                if not подходит(предмет):
                    return None
                return _ответ(self.pre_request_hook(_аккаунт(), предмет), предмет)
            _мост.on_request(запрос)

        if hasattr(self, "post_request_hook"):
            def ответ(запрос_, ответ_, ошибка):
                if not подходит(запрос_):
                    return None
                return _ответ(self.post_request_hook(
                    _аккаунт(), запрос_, ответ_, ошибка), ответ_)
            _мост.on_answer(ответ)

        if hasattr(self, "on_update_hook") or hasattr(self, "on_updates_hook"):
            обработчик = getattr(self, "on_update_hook", None) \
                or getattr(self, "on_updates_hook")

            def обновление(предмет):
                if not подходит(предмет):
                    return None
                return _ответ(обработчик(_аккаунт(), предмет), предмет)
            _мост.on_update(обновление)


def _аккаунт():
    try:
        from client_utils import get_selected_account
        return get_selected_account()
    except Exception:
        return 0


def _ответ(результат, исходное):
    """Их HookResult — в то, что понимает margelet."""
    if результат is None:
        return None
    стратегия = getattr(результат, "strategy", HookStrategy.DEFAULT)
    if стратегия == HookStrategy.CANCEL:
        return False
    if стратегия in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
        подмена = getattr(результат, "params", None)
        return подмена if подмена is not None else None
    return None


def старт(margelet):
    """Поднять плагин экстеры. Зовётся из хвоста, который дописал конвертер."""
    global _мост
    _мост = margelet

    for класс in list(_классы):
        try:
            образец = класс()
            _живые.append(образец)
            строки = образец.create_settings()
            if строки:
                _настройки(margelet, образец, строки)
            образец.on_plugin_load()
        except Exception:
            import traceback
            margelet.error(traceback.format_exc())


def _настройки(margelet, образец, строки):
    """Их экран настроек — нашим margelet.settings."""
    наши = []
    for строка in строки:
        вид = type(строка).__name__
        ключ = getattr(строка, "key", None)
        название = getattr(строка, "text", None) or getattr(строка, "title", "")
        про = getattr(строка, "subtext", None)
        if вид == "Header":
            наши.append(margelet.header(название))
        elif вид == "Divider":
            наши.append(margelet.note(про or ""))
        elif вид == "Switch":
            наши.append(margelet.switch(ключ, название,
                                        default=bool(getattr(строка, "default", False)),
                                        about=про))
        elif вид in ("Input", "Text"):
            наши.append(margelet.text(ключ, название,
                                      default=str(getattr(строка, "default", "") or ""),
                                      about=про))
        elif вид == "Selector":
            наши.append(margelet.choice(ключ, название,
                                        list(getattr(строка, "items", []) or [])))
        else:
            # Неизвестную строку показываем подписью, а не выбрасываем:
            # человек должен видеть, что настройка есть, но не переведена.
            наши.append(margelet.note(str(название)))
    if наши:
        margelet.settings(*наши)
