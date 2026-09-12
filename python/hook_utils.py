# -*- coding: utf-8 -*-
"""Слой совместимости: hook_utils экстеры."""
from java import jclass


def find_class(имя, loader=None):
    """У них это поиск класса по имени. У нас — тот же jclass."""
    return jclass(str(имя))
