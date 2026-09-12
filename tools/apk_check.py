# -*- coding: utf-8 -*-
"""Проверка собранного апк — того, что уедет людям, а не того, что в исходниках.

Каждая проверка здесь стоит потому, что соответствующая ошибка уже случалась
и в исходниках была не видна:

  подпись   — апк с чужой подписью не встанет поверх установленного;
  номер     — сборка со старым APP_VERSION вечно предлагает обновиться сама
              на себя, а расхождение с version.json даёт круг без конца;
  имена     — R8 переименовывает методы классов, которые зовут строкой, и
              плагины падают уже на телефоне;
  языки     — китайский компилировался и отбрасывался при упаковке, потому
              что в фильтре языков не было zh.

  python3 tools/apk_check.py <апк> [номер]
"""
import os
import re
import subprocess
import sys
import zipfile


ПОДПИСЬ = "a08d7dc323ddf71ef3201944397e0d3cce7d40847263e11f328b68bbe19229ab"
ЯЗЫКИ = ("ru", "uk", "zh")
# Только редкие имена. Первая попытка искала среди прочего "requesting" и
# "updating" — и находила их в старом апк, где этих методов ещё не было: это
# обычные английские слова, они лежат в чужих строках. Проверка, которая
# срабатывает сама по себе, не проверяет ничего. По той же причине выпало
# "addMenuItem": такой метод есть в чужих библиотеках меню.
КЛАССЫ = ("Lorg/telegram/margelet/MargeletHooks;",
          "Lorg/telegram/margelet/MargeletPython;",
          "Lorg/telegram/margelet/MargeletPluginHost;")
ИМЕНА = ("menuClicked", "wantRequest", "hasAnswer", "answering")

СРЕДСТВА = "/opt/android-sdk/build-tools/35.0.0"
сломано = []


def равно(что, чему, про):
    if что != чему:
        сломано.append("%s: ждали %r, вышло %r" % (про, чему, что))


def есть(что, где, про):
    if что not in где:
        сломано.append("%s: нет %r" % (про, что))


def запустить(*доводы):
    return subprocess.run(доводы, capture_output=True, text=True).stdout


def проверить(апк, номер=None):
    значки = запустить(os.path.join(СРЕДСТВА, "aapt2"), "dump", "badging", апк)
    имя = re.search(r"versionName='([^']+)'", значки)
    код = re.search(r"versionCode='([^']+)'", значки)
    print("версия апк:", имя.group(1) if имя else "?",
          "код:", код.group(1) if код else "?")

    # Подпись. Чужая означает, что поверх установленного оно не встанет.
    сертификаты = запустить(os.path.join(СРЕДСТВА, "apksigner"),
                            "verify", "--print-certs", апк)
    отпечаток = re.search(r"SHA-256 digest:\s*([0-9a-f]{64})", сертификаты)
    равно(отпечаток.group(1) if отпечаток else None, ПОДПИСЬ, "подпись")

    # Номер внутри — тот, по которому клиент решает, обновляться ли ему.
    with zipfile.ZipFile(апк) as архив:
        dex = b"".join(архив.read(и) for и in архив.namelist()
                       if и.endswith(".dex"))
    внутри = sorted(set(re.findall(rb"0\.99\.\d+", dex)))
    print("APP_VERSION внутри:", [з.decode() for з in внутри])
    if номер:
        равно([з.decode() for з in внутри], [номер], "APP_VERSION внутри апк")

    # Имена, которые зовут строкой. R8 их переименует — и всё скомпилируется.
    for имя_класса in КЛАССЫ:
        есть(имя_класса.encode(), dex, "класс съеден R8")
    for слово in ИМЕНА:
        есть(слово.encode(), dex, "имя съедено R8")

    # Языки. Проверять надо упакованное, а не собранное.
    настройки = запустить(os.path.join(СРЕДСТВА, "aapt2"),
                          "dump", "configurations", апк)
    для_языков = set(re.findall(r"^([a-z]{2})(?:-[a-zA-Z0-9+]+)?$",
                                настройки, re.M))
    for язык in ЯЗЫКИ:
        есть(язык, для_языков, "язык не упакован")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    проверить(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    if сломано:
        print("СЛОМАНО:")
        for строка in сломано:
            print(" -", строка)
        sys.exit(1)
    print("апк цел")
