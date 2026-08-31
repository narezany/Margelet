# -*- coding: utf-8 -*-
"""Проверка движка плагинов без телефона.

Питон в приложении настоящий, а джава вокруг — нет. Значит всё, что можно
проверить на столе, надо проверять на столе: движок поднимается с
поддельной джавой, на нём запускается наш же пример, и дальше в него
стучатся ровно теми вызовами, которыми стучится приложение.

Ловит то, ради чего это и написано: обработчик, которого нет; довод,
который не доехал; ответ, который приложение поймёт не так. Всё это до сих
пор ловилось только сборкой и телефоном.

  python3 tools/plugin_api_check.py
"""
import io
import json
import os
import sys
import types
import zipfile


КОРЕНЬ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ХОСТ = os.path.join(КОРЕНЬ, "python", "margelet_host.py")
ПРИМЕР = os.path.join(КОРЕНЬ, "docs", "margelet_example.marp")

сломано = []


def равно(что, чему, про):
    if что != чему:
        сломано.append("%s: ждали %r, вышло %r" % (про, чему, что))


class Заглушка:
    """Любой джавовый класс: помнит вызовы, на всё отвечает такой же заглушкой.

    Ответы, которые движку важны по существу, задаются отдельно — остальное
    ему безразлично, и притворяться умнее, чем нужно, здесь вредно.
    """

    def __init__(self, имя="java"):
        self._имя = имя
        self.вызовы = []
        self.память = {}
        self.ошибки = []

    def __getattr__(self, имя):
        def позвали(*доводы):
            self.вызовы.append((имя, доводы))
            # Память плагина: движок кладёт и берёт настройки через неё.
            if имя == "get":
                return self.память.get((доводы[0], доводы[1]), доводы[2] if len(доводы) > 2 else None)
            if имя == "set":
                self.память[(доводы[0], доводы[1])] = доводы[2]
                return None
            if имя == "log":
                # Третий довод — «это ошибка». Ошибка движка молча уходит в
                # консоль приложения, поэтому здесь она и ловится.
                if len(доводы) > 2 and доводы[2]:
                    self.ошибки.append(доводы[1])
                return None
            if имя == "post":
                доводы[0].run() if hasattr(доводы[0], "run") else доводы[0]()
                return None
            if имя == "working":
                return False
            return None
        return позвали

    def звали(self, имя):
        return [д for и, д in self.вызовы if и == имя]


джава = Заглушка()


def поднять_движок():
    """Загрузить margelet_host.py, подсунув ему поддельный модуль java."""
    поддельный = types.ModuleType("java")
    поддельный.dynamic_proxy = lambda *_: object
    поддельный.jclass = lambda имя: джава
    поддельный.jarray = lambda _: (lambda список: list(список))
    java_lang = types.ModuleType("java.lang")
    java_lang.Runnable = object
    sys.modules["java"] = поддельный
    sys.modules["java.lang"] = java_lang

    источник = open(ХОСТ, encoding="utf-8").read()
    модуль = types.ModuleType("margelet_host")
    модуль.__file__ = ХОСТ
    sys.modules["margelet_host"] = модуль
    exec(compile(источник, ХОСТ, "exec"), модуль.__dict__)
    return модуль


def распаковать_пример(куда):
    with zipfile.ZipFile(ПРИМЕР) as архив:
        архив.extractall(куда)
    return json.load(open(os.path.join(куда, "manifest.json"), encoding="utf-8"))


def проверить():
    движок = поднять_движок()

    import tempfile
    папка = tempfile.mkdtemp(prefix="margelet-пример-")
    манифест = распаковать_пример(папка)

    движок.run_plugin(манифест["id"], манифест["name"], папка)
    равно(манифест["id"] in движок._loaded, True, "пример не запустился")

    # Все двери примера включаем: выключенные не проверяются ничем.
    for ключ in ("greet", "sign", "count", "traffic"):
        джава.память[(манифест["id"], ключ)] = "1"
    движок._loaded.clear()
    движок._margelets.clear()
    джава.вызовы.clear()
    джава.ошибки.clear()
    движок.run_plugin(манифест["id"], манифест["name"], папка)

    # --- меню ---
    места = {}
    for доводы in джава.звали("addMenuItem"):
        места.setdefault(доводы[3], []).append(доводы[1])
    равно(sorted(места), ["chat", "drawer", "message", "profile"],
          "плагин заявил не все меню")

    class Сообщение:
        def getId(self):
            return 42

    движок.menu_clicked(манифест["id"], "Сколько насчитал", "chat", None, None)
    движок.menu_clicked(манифест["id"], "Сколько насчитал", "drawer", None, None)
    движок.menu_clicked(манифест["id"], "Чей это профиль", "profile", None, 777)
    движок.menu_clicked(манифест["id"], "Что за сообщение", "message", None, Сообщение())
    равно(len(джава.звали("toast")), 4, "нажали четыре строчки, а надписей не четыре")

    # Чужой ключ не должен ничего звать и не должен ронять движок.
    было = len(джава.звали("toast"))
    движок.menu_clicked(манифест["id"], "такого нет", "chat", None, None)
    равно(len(джава.звали("toast")), было, "нажалась строчка, которой нет")

    # --- разговор с сервером ---
    class Запрос:
        def getClass(self):
            return self

        def getSimpleName(self):
            return "TL_users_getUsers"

    запрос = Запрос()
    равно(движок.requesting(запрос), None, "запрос никто не трогал, а движок это скрыл")
    равно(движок.updating(запрос), None, "обновление никто не трогал, а движок это скрыл")
    равно(движок.answering(запрос, запрос, None), None, "ответ никто не трогал, а движок это скрыл")
    равно(джава.память.get((манифест["id"], "запросов")), "1", "запрос не посчитан")
    равно(джава.память.get((манифест["id"], "ответов")), "1", "ответ не посчитан")
    равно(джава.память.get((манифест["id"], "обновлений")), "1", "обновление не посчитано")

    # --- ответы, которые приложение читает по-особому ---
    margelet = движок._margelets[манифест["id"]]

    margelet._on_request.append(lambda _: False)
    равно(движок.requesting(запрос), False, "отмена запроса не доехала")
    margelet._on_request.pop()

    подмена = Запрос()
    margelet._on_request.append(lambda _: подмена)
    равно(движок.requesting(запрос) is подмена, True, "подмена запроса не доехала")
    margelet._on_request.pop()

    margelet._on_update.append(lambda _: False)
    равно(движок.updating(запрос), False, "отмена обновления не доехала")
    margelet._on_update.pop()

    margelet._on_answer.append(lambda з, о, ош: False)
    равно(движок.answering(запрос, запрос, None), False, "отмена ответа не доехала")
    margelet._on_answer.pop()

    # --- старые двери не сломались ---
    равно(движок.sending("привет", 1), "привет 🌿", "подпись при отправке отвалилась")
    движок.received("а", 1, 2, False)
    равно(джава.память.get((манифест["id"], "counted")), "1", "входящее не посчитано")

    равно(джава.ошибки, [], "движок ругался в консоль")


if __name__ == "__main__":
    проверить()
    if сломано:
        print("СЛОМАНО:")
        for строка in сломано:
            print(" -", строка)
        sys.exit(1)
    print("движок плагинов цел")
