# -*- coding: utf-8 -*-
"""Слой совместимости: то, что у exteraGram зовётся client_utils.

Их client_utils — обёртки вокруг обычных классов телеграма. Те же классы
доступны и нам: питон в приложении настоящий, java видна как есть. Поэтому
здесь не эмуляция, а те же самые вызовы под их именами.
"""
from java import dynamic_proxy, jclass

_Connections = jclass("org.telegram.tgnet.ConnectionsManager")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_Launch = jclass("org.telegram.ui.LaunchActivity")
_Send = jclass("org.telegram.messenger.SendMessagesHelper")
_Hooks = jclass("org.telegram.margelet.MargeletHooks")


def get_selected_account():
    return _UserConfig.selectedAccount


def get_last_fragment():
    return _Launch.getLastFragment()


def get_account_instance(account=None):
    return jclass("org.telegram.messenger.AccountInstance").getInstance(
        get_selected_account() if account is None else account)


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_user_config(account=None):
    return _UserConfig.getInstance(
        get_selected_account() if account is None else account)


def send_request(request, callback=None, account=None):
    """Запрос к серверу. Ответ приходит в callback(ответ, ошибка), как у них."""
    номер = get_selected_account() if account is None else account

    class Ответ(dynamic_proxy(_RequestDelegate)):
        def run(self, ответ, ошибка):
            if callback is not None:
                callback(ответ, ошибка)

    return _Connections.getInstance(номер).sendRequest(request, Ответ())


def run_on_queue(work, delay=0):
    """Долгая работа в стороне от рисующего потока."""
    _Hooks.background(_Работа(work))


def send_message(params):
    """Отправить сообщение. Им хватает словаря с message и peer."""
    текст = params.get("message") if isinstance(params, dict) else getattr(params, "message", None)
    куда = params.get("peer") if isinstance(params, dict) else getattr(params, "peer", None)
    if текст is None or куда is None:
        raise ValueError("нужны message и peer")
    _Hooks.send(int(куда), str(текст))


def send_document(path, peer, caption=None):
    """Своей двери для файлов у Margy нет — говорим об этом вслух."""
    raise NotImplementedError(
        "отправка файлов из плагина в Margy пока не открыта; "
        "текст отправляется через send_message")


def send_photo(path, peer, caption=None):
    raise NotImplementedError(
        "отправка картинок из плагина в Margy пока не открыта; "
        "текст отправляется через send_message")


class _Работа(dynamic_proxy(jclass("java.lang.Runnable"))):
    def __init__(self, call):
        super().__init__()
        self.call = call

    def run(self):
        self.call()
