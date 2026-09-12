# -*- coding: utf-8 -*-
"""Проверка того, что во встроенном прокси можно проверить без телефона.

Сам прокси без сети не проверишь: ему нужны живые дата-центры. Но две его
части — расчёт обфускации и кадры websocket — чистая арифметика, и они же
самые опасные: ошибка в них выглядит как «нет связи», а не как падение.

Обе части написаны без единой зависимости от андроида нарочно, чтобы их
можно было выполнить обычной java прямо здесь.

  python3 tools/proxy_check.py
"""
import os
import shutil
import subprocess
import sys
import tempfile


КОРЕНЬ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ИСХОДНИКИ = os.path.join(КОРЕНЬ, "java", "margelet")
ПРОВЕРКИ = os.path.join(КОРЕНЬ, "tools", "java")

ЧТО = (
    ("ObfuscationCheck", ("MargeletObfuscation.java",)),
    ("SocketCheck", ("MargeletSocket.java",)),
)


def выполнить(*доводы, **где):
    # JAVA_TOOL_OPTIONS в этой среде печатает свою строку в каждый запуск и
    # мешает читать вывод; здесь он не нужен.
    среда = dict(os.environ, JAVA_TOOL_OPTIONS="")
    return subprocess.run(доводы, capture_output=True, text=True, env=среда, **где)


def main():
    if shutil.which("javac") is None:
        print("нет javac — проверить нечем")
        return 2

    сломано = 0
    for имя, файлы in ЧТО:
        двор = tempfile.mkdtemp(prefix="margelet-" + имя + "-")
        пакет = os.path.join(двор, "org", "telegram", "margelet")
        os.makedirs(пакет)
        for файл in файлы:
            shutil.copy(os.path.join(ИСХОДНИКИ, файл), пакет)
        shutil.copy(os.path.join(ПРОВЕРКИ, имя + ".java"), двор)

        собрано = выполнить("javac", "-d", ".",
                            *[os.path.join("org/telegram/margelet", ф) for ф in файлы],
                            имя + ".java", cwd=двор)
        if собрано.returncode != 0:
            print("не собралось:", имя)
            print(собрано.stderr.strip()[:2000])
            сломано += 1
            continue

        вышло = выполнить("java", "-Dstdout.encoding=UTF-8", "-cp", ".", имя, cwd=двор)
        print(вышло.stdout.strip())
        if вышло.returncode != 0:
            print(вышло.stderr.strip()[:2000])
            сломано += 1
        shutil.rmtree(двор, ignore_errors=True)

    return 1 if сломано else 0


if __name__ == "__main__":
    sys.exit(main())
