# -*- coding: utf-8 -*-
"""Слой совместимости: file_utils экстеры."""
from java import jclass

_Loader = jclass("org.telegram.messenger.ApplicationLoader")


def get_cache_dir():
    return str(_Loader.applicationContext.getCacheDir().getAbsolutePath())


def get_files_dir():
    return str(_Loader.applicationContext.getFilesDir().getAbsolutePath())
