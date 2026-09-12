# -*- coding: utf-8 -*-
"""Слой совместимости: окна экстеры.

AlertDialogBuilder у них — обёртка над обычным AlertDialog.Builder телеграма.
Здесь то же самое, только тоньше: поддержаны заголовок, текст и две кнопки,
потому что на этом держится почти всё, что плагины показывают.
"""
from java import dynamic_proxy, jclass

_Builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")
_OnClick = jclass("android.content.DialogInterface$OnClickListener")


class _Нажали(dynamic_proxy(_OnClick)):
    def __init__(self, call):
        super().__init__()
        self.call = call

    def onClick(self, окно, что):
        if self.call is not None:
            self.call(окно, что)


class AlertDialogBuilder:
    def __init__(self, context, *прочее):
        self._строитель = _Builder(context)
        self._окно = None

    def set_title(self, текст):
        self._строитель.setTitle(str(текст))
        return self

    def set_message(self, текст):
        self._строитель.setMessage(str(текст))
        return self

    def set_positive_button(self, текст, call=None):
        self._строитель.setPositiveButton(str(текст), _Нажали(call))
        return self

    def set_negative_button(self, текст, call=None):
        self._строитель.setNegativeButton(str(текст), _Нажали(call))
        return self

    def set_neutral_button(self, текст, call=None):
        self._строитель.setNeutralButton(str(текст), _Нажали(call))
        return self

    def set_cancelable(self, можно):
        self._строитель.setCancelable(bool(можно))
        return self

    def create(self):
        self._окно = self._строитель.create()
        return self._окно

    def show(self):
        if self._окно is None:
            self.create()
        self._окно.show()
        return self._окно

    def dismiss(self):
        if self._окно is not None:
            self._окно.dismiss()
