# -*- coding: utf-8 -*-
"""Слой совместимости: всплывающие подсказки экстеры.

У них BulletinHelper показывает плашку поверх экрана. Здесь она короче —
обычная системная надпись: она видна и тогда, когда открытого экрана нет, а
плашке телеграма нужен живой фрагмент.
"""
from java import jclass

from android_utils import get_application_context, run_on_ui_thread

_Toast = jclass("android.widget.Toast")


def _сказать(текст):
    run_on_ui_thread(lambda: _Toast.makeText(
        get_application_context(), str(текст), _Toast.LENGTH_SHORT).show())


class BulletinHelper:
    @staticmethod
    def show_info(текст, *прочее):
        _сказать(текст)

    @staticmethod
    def show_error(текст, *прочее):
        _сказать(текст)

    @staticmethod
    def show_success(текст, *прочее):
        _сказать(текст)

    @staticmethod
    def show_with_button(текст, *прочее, **ещё):
        _сказать(текст)
