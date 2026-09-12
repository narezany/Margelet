# -*- coding: utf-8 -*-
"""Слой совместимости: строки экрана настроек экстеры.

Это просто описания — их читает base_plugin и превращает в margelet.settings.
Ничего не рисуют сами, как и у них.
"""


class _Строка:
    def __init__(self, key=None, text="", subtext=None, default=None, **прочее):
        self.key = key
        self.text = text
        self.subtext = subtext
        self.default = default
        for имя, значение in прочее.items():
            setattr(self, имя, значение)


class Header(_Строка):
    def __init__(self, text="", **прочее):
        super().__init__(text=text, **прочее)


class Divider(_Строка):
    pass


class Switch(_Строка):
    pass


class Input(_Строка):
    pass


class Text(_Строка):
    pass


class Selector(_Строка):
    def __init__(self, key=None, text="", items=None, default=0, **прочее):
        super().__init__(key=key, text=text, default=default, **прочее)
        self.items = items or []
