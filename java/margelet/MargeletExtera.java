package org.telegram.margelet;

import android.content.Context;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Плагин exteraGram, переложенный в наш формат.
 *
 * Переложить получается потому, что питон у них и у нас — один и тот же питон
 * в одном и том же телеграме. Их плагин зовёт не какое-то своё волшебство, а
 * обычные классы приложения через модули-обёртки: base_plugin, client_utils,
 * ui.alert и прочие. Эти модули лежат у нас рядом с margelet_host, и потому
 * их файл едет почти как есть: меняется упаковка и дописывается хвост,
 * который поднимает их класс.
 *
 * Чего здесь нарочно нет — тихого «сконвертировано». Если в плагине есть то,
 * чего у нас не будет, человек читает об этом ДО установки, а не выясняет по
 * молчащему плагину.
 */
public class MargeletExtera {

    /** Больше этого файл плагином уже не бывает, а читать в память — бывает. */
    private static final int LIMIT = 4 * 1024 * 1024;

    /** Модули, которые у нас лежат рядом и работают. */
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "base_plugin", "client_utils", "android_utils", "hook_utils",
            "file_utils", "ui", "ui.settings", "ui.alert", "ui.bulletin",
            "extera_utils", "extera_utils.classes", "java", "margelet"));

    /** То, что у нас не заработает, и почему. Показываем до установки. */
    private static final String[][] BROKEN = {
            {"java_subclass", "наследование java-классов"},
            {"send_document", "отправка файлов из плагина"},
            {"send_photo", "отправка картинок из плагина"},
            {"CHAT_ATTACH_MENU", "меню скрепки"},
            {"__requirements__", "установка пакетов пипом"},
    };

    public static final class Parsed {
        public final String id;
        public final String name;
        public final String version;
        public final String author;
        public final String description;
        /** Чего не будет: готовые строчки для человека. */
        public final List<String> missing;

        Parsed(String id, String name, String version, String author,
               String description, List<String> missing) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
            this.missing = missing;
        }
    }

    private static String meta(String source, String key) {
        final Matcher m = Pattern.compile(
                "^" + key + "\\s*=\\s*[\"']([^\"']*)[\"']",
                Pattern.MULTILINE).matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Разобрать файл. Возвращает null, если это не плагин экстеры: у них
     * обязательны __id__ и __name__, и по ним же отличают свой файл.
     */
    public static Parsed parse(String source) {
        final String id = meta(source, "__id__");
        final String name = meta(source, "__name__");
        if (id == null || name == null) {
            return null;
        }
        final List<String> missing = new ArrayList<>();

        // Чужие модули. Свои, лежащие рядом, знаем в лицо; org.telegram и
        // android — это сама java, она доступна как есть; всё прочее питон
        // либо найдёт у себя, либо не найдёт, и тогда лучше сказать заранее.
        final Matcher imports = Pattern.compile(
                "^\\s*(?:from|import)\\s+([A-Za-z_][\\w.]*)", Pattern.MULTILINE)
                .matcher(source);
        final Set<String> said = new HashSet<>();
        while (imports.find()) {
            final String module = imports.group(1);
            final String root = module.contains(".")
                    ? module.substring(0, module.indexOf('.')) : module;
            if (KNOWN.contains(module) || KNOWN.contains(root)
                    || "org".equals(root) || "android".equals(root)
                    || "androidx".equals(root) || "kotlin".equals(root)) {
                continue;
            }
            if (isStandard(root) || !said.add(root)) {
                continue;
            }
            missing.add(LocaleController.formatString(
                    R.string.MargeletExteraUnknownModule, root));
        }

        for (String[] pair : BROKEN) {
            if (source.contains(pair[0])) {
                missing.add(pair[1]);
            }
        }

        return new Parsed(id, name,
                first(meta(source, "__version__"), "1.0"),
                first(meta(source, "__author__"), ""),
                first(meta(source, "__description__"), ""),
                missing);
    }

    private static String first(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Обычная библиотека питона: такие модули есть и у нас. */
    private static boolean isStandard(String root) {
        return Arrays.asList("os", "sys", "re", "json", "time", "math",
                "random", "base64", "hashlib", "traceback", "datetime",
                "threading", "typing", "collections", "itertools", "functools",
                "struct", "zipfile", "io", "urllib", "html", "string",
                "dataclasses", "enum", "abc", "copy", "shutil", "tempfile",
                "textwrap", "uuid", "binascii", "unicodedata")
                .contains(root);
    }

    /** Собрать .marp: манифест, тот же код и хвост, который его поднимает. */
    public static byte[] pack(Parsed plugin, String source) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ZipOutputStream zip = new ZipOutputStream(out);

        final StringBuilder manifest = new StringBuilder();
        manifest.append("{\n");
        manifest.append("  \"id\": \"extera.").append(escape(plugin.id)).append("\",\n");
        manifest.append("  \"name\": \"").append(escape(plugin.name)).append("\",\n");
        manifest.append("  \"version\": \"").append(escape(plugin.version)).append("\",\n");
        manifest.append("  \"author\": \"").append(escape(plugin.author)).append("\",\n");
        manifest.append("  \"description\": \"").append(escape(plugin.description)).append("\"\n");
        manifest.append("}\n");

        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        zip.putNextEntry(new ZipEntry("main.py"));
        zip.write(source.getBytes(StandardCharsets.UTF_8));
        zip.write(TAIL.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();

        zip.close();
        return out.toByteArray();
    }

    /**
     * Хвост, который дописывается к коду плагина.
     *
     * Их плагин ничего не запускает сам: он только объявляет класс, а поднимает
     * его их приложение. У нас точка входа — on_start, поэтому она и пишется,
     * прямо в исходнике и с объяснением: человек, открывший плагин, должен
     * видеть, откуда взялись эти строки.
     */
    private static final String TAIL =
            "\n\n"
            + "# --- дописано при переводе из exteraGram ---\n"
            + "# Их плагин только объявляет класс; поднимает его приложение.\n"
            + "# У Margy точка входа — on_start, поэтому она здесь и стоит.\n"
            + "def on_start():\n"
            + "    import base_plugin\n"
            + "    base_plugin.старт(margelet)\n";

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /**
     * Нажали на файл .plugin в переписке. Открывать его системе нечем, а мы
     * знаем, что это такое, — поэтому предлагаем перевести.
     */
    public static boolean offerConvert(Context context, File file) {
        try {
            if (file.length() > LIMIT) {
                return false;
            }
            final byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    final int step = in.read(bytes, read, bytes.length - read);
                    if (step <= 0) {
                        break;
                    }
                    read += step;
                }
            }
            final String source = new String(bytes, StandardCharsets.UTF_8);
            final Parsed plugin = parse(source);
            if (plugin == null) {
                return false;
            }
            ask(context, plugin, source);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static void ask(Context context, Parsed plugin, String source) {
        final StringBuilder text = new StringBuilder();
        text.append(LocaleController.formatString(
                R.string.MargeletExteraAbout, plugin.name));
        if (!plugin.missing.isEmpty()) {
            text.append("\n\n");
            text.append(LocaleController.getString(R.string.MargeletExteraMissing));
            for (String line : plugin.missing) {
                text.append("\n• ").append(line);
            }
        }

        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MargeletExteraTitle))
                .setMessage(text.toString())
                .setPositiveButton(
                        LocaleController.getString(R.string.MargeletExteraConvert),
                        (dialog, which) -> convert(context, plugin, source))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void convert(Context context, Parsed plugin, String source) {
        try {
            MargeletPlugins.askInstall(context,
                    new ByteArrayInputStream(pack(plugin, source)), null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
