# -*- coding: utf-8 -*-
"""Слой совместимости: android_utils экстеры."""
from java import dynamic_proxy, jclass

_Hooks = jclass("org.telegram.margelet.MargeletHooks")
_Android = jclass("org.telegram.messenger.AndroidUtilities")
_Runnable = jclass("java.lang.Runnable")
_OnClick = jclass("android.view.View$OnClickListener")


class _Работа(dynamic_proxy(_Runnable)):
    def __init__(self, call):
        super().__init__()
        self.call = call

    def run(self):
        self.call()


def run_on_ui_thread(call, delay=0):
    if delay:
        _Android.runOnUIThread(_Работа(call), int(delay))
    else:
        _Android.runOnUIThread(_Работа(call))


def log(*части):
    print(" ".join(str(ч) for ч in части))


class OnClickListener(dynamic_proxy(_OnClick)):
    def __init__(self, call):
        super().__init__()
        self.call = call

    def onClick(self, view):
        self.call(view)


def get_application_context():
    return jclass("org.telegram.messenger.ApplicationLoader").applicationContext
