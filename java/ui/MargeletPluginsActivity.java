package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.margelet.MargeletConfig;
import org.telegram.margelet.MargeletHooks;
import org.telegram.margelet.MargeletPluginHost;
import org.telegram.margelet.MargeletPlugins;
import org.telegram.margelet.MargeletStore;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.ViewPagerFixed;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Ветка «Плагины»: две вкладки — свои и магазин.
 *
 * Экран честный до неудобства. Плагин выполняется внутри приложения и может
 * всё, что может оно, — значит, так и написано, а не «разрешения защищают
 * вас». Разрешения из манифеста показываются как заявление автора: это то,
 * что он о себе сказал, проверить их приложению нечем.
 *
 * Магазин — вторая вкладка, а не кнопка «библиотека», уводившая наружу. Уводя
 * человека в браузер за плагином, мы отправляли его качать чужой код руками и
 * возвращаться с файлом; теперь список лежит здесь же, а окно установки
 * спрашивает ровно то же самое, что и раньше.
 */
public class MargeletPluginsActivity extends BaseFragment {

    private static final int ID_MASTER = 1;
    private static final int ID_INSTALL = 2;
    private static final int ID_CONSOLE = 3;
    private static final int ID_DOCS = 4;
    private static final int ID_RESTART = 6;
    private static final int ID_HOOKS = 7;
    private static final int ID_STORE_OPEN = 8;
    /** Строки самих плагинов идут отсюда и дальше, по одному номеру на плагин. */
    private static final int ID_PLUGIN = 100;
    /** То же для строк магазина. */
    private static final int ID_STORE = 1000;

    private static final int PICK_FILE = 4831;

    private static final int TAB_MINE = 0;
    private static final int TAB_STORE = 1;

    private List<MargeletPlugins.Plugin> plugins = new ArrayList<>();

    /** С какой вкладки открыться. Обычно со своих, но ссылка может звать в магазин. */
    private final int startTab;

    public MargeletPluginsActivity() {
        this(TAB_MINE);
    }

    public MargeletPluginsActivity(int startTab) {
        this.startTab = startTab;
    }

    private ViewPagerFixed pager;
    private ViewPagerFixed.TabsView tabsView;
    private UniversalRecyclerView mineView;
    private UniversalRecyclerView storeView;

    /** Что лежит в канале. Пусто и «ещё не спросили» — разное, отсюда два поля. */
    private final List<MargeletStore.Item> store = new ArrayList<>();
    private boolean storeAsked;
    private boolean storeLoading;
    private String storeProblem;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MargeletPlugins));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (tabsView != null) {
                    final float y = tabsView.getMeasuredHeight();
                    canvas.drawLine(0, y, getWidth(), y, Theme.dividerPaint);
                }
            }
        };
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        pager = new ViewPagerFixed(context);
        pager.setAdapter(new Pages());

        tabsView = pager.createTabsView(true, 8);
        tabsView.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefault));
        root.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48,
                Gravity.TOP | Gravity.FILL_HORIZONTAL));
        root.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 48, 0, 0));
        if (startTab != TAB_MINE) {
            // Только после того, как список вкладок готов: перелистывание
            // подсвечивает вкладку, а подсвечивать пока нечего.
            pager.setPosition(startTab);
        }

        return fragmentView = root;
    }

    private class Pages extends ViewPagerFixed.Adapter {
        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public View createView(int viewType) {
            if (viewType == TAB_STORE) {
                storeView = new UniversalRecyclerView(MargeletPluginsActivity.this,
                        MargeletPluginsActivity.this::fillStore,
                        MargeletPluginsActivity.this::clickStore, null);
                storeView.setSections();
                return storeView;
            }
            mineView = new UniversalRecyclerView(MargeletPluginsActivity.this,
                    MargeletPluginsActivity.this::fillMine,
                    MargeletPluginsActivity.this::clickMine,
                    MargeletPluginsActivity.this::longClickMine);
            // Скруглённые карточки — как на всех прочих экранах форка.
            // Здесь их не было по единственной причине: этот экран не
            // UniversalFragment, а свой листатель из двух списков, и строчку
            // setSections каждый экран зовёт себе сам. На экранах настроек её
            // написали, а здесь забыли — оттого плагины годами выглядели
            // квадратными среди скруглённого.
            mineView.setSections();
            return mineView;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            if (position == TAB_STORE) {
                askStore();
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public CharSequence getItemTitle(int position) {
            return LocaleController.getString(position == TAB_STORE
                    ? R.string.MargeletStoreTab : R.string.MargeletPluginsMineTab);
        }
    }

    private void refresh() {
        if (mineView != null && mineView.adapter != null) {
            mineView.adapter.update(true);
        }
        if (storeView != null && storeView.adapter != null) {
            storeView.adapter.update(true);
        }
    }

    // --- вкладка «свои» ---

    private void fillMine(ArrayList<UItem> items, UniversalAdapter adapter) {
        plugins = MargeletPlugins.installed();

        items.add(UItem.asCheck(ID_MASTER, LocaleController.getString(R.string.MargeletPlugins))
                .setChecked(MargeletConfig.pluginsEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MargeletPluginsAbout)));

        // Хуки идут сразу за главным выключателем, потому что это второе по
        // важности решение на экране, а не мелочь в конце списка: они пускают
        // чужой код глубже, чем всё остальное здесь.
        items.add(UItem.asCheck(ID_HOOKS, LocaleController.getString(R.string.MargeletHooks))
                .setChecked(org.telegram.margelet.MargeletHookEngine.enabled()));
        items.add(UItem.asShadow(LocaleController.getString(
                org.telegram.margelet.MargeletHookEngine.brokeLastTime()
                        ? R.string.MargeletHooksBroke : R.string.MargeletHooksAbout)));

        if (!plugins.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MargeletPluginsInstalled)));
            for (int i = 0; i < plugins.size(); i++) {
                final MargeletPlugins.Plugin plugin = plugins.get(i);
                items.add(MargeletPluginCell.Factory.of(ID_PLUGIN + i, plugin, plugin.enabled()));
            }
            items.add(UItem.asShadow(LocaleController.getString(
                    MargeletConfig.pluginsEnabled()
                            ? R.string.MargeletPluginsHint
                            : R.string.MargeletPluginsOffHint)));
        }

        // Перезапуск прямо здесь: включённый плагин поднимается только на
        // старте, а выключенный доживает до него. Раньше человеку приходилось
        // закрывать телеграм самому и догадываться, что это вообще нужно.
        items.add(UItem.asButton(ID_RESTART, LocaleController.getString(R.string.MargeletPluginsRestart)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MargeletPluginsRestartAbout)));
        items.add(UItem.asButton(ID_INSTALL, LocaleController.getString(R.string.MargeletPluginInstall)));
        items.add(UItem.asButton(ID_CONSOLE, LocaleController.getString(R.string.MargeletPluginConsole)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(ID_DOCS, LocaleController.getString(R.string.MargeletPluginDocs)));
        items.add(UItem.asShadow(null));
    }

    private void clickMine(UItem item, View view, Integer position, Float x, Float y) {
        if (item.id == ID_MASTER) {
            toggleMaster();
        } else if (item.id == ID_HOOKS) {
            toggleHooks();
        } else if (item.id == ID_RESTART) {
            MargeletPlugins.restart(getParentActivity());
        } else if (item.id == ID_INSTALL) {
            pickFile();
        } else if (item.id == ID_CONSOLE) {
            presentFragment(new MargeletPluginConsoleActivity());
        } else if (item.id == ID_DOCS) {
            Browser.openUrl(getContext(), MargeletConfig.pluginsDocsUrl());
        } else if (item.id >= ID_PLUGIN && item.id < ID_STORE) {
            open(plugin(item.id), view, x);
        }
    }

    private boolean longClickMine(UItem item, View view, Integer position, Float x, Float y) {
        if (item.id >= ID_PLUGIN && item.id < ID_STORE) {
            about(plugin(item.id));
            return true;
        }
        return false;
    }

    // --- вкладка «магазин» ---

    private void fillStore(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.MargeletStoreAbout)));
        if (storeLoading) {
            // Мерцающие заглушки на время ожидания: список из канала едет
            // по сети, и пустой экран на эту секунду выглядел бы как «пусто».
            for (int i = 0; i < 3; i++) {
                items.add(UItem.asFlicker(org.telegram.ui.Components.FlickerLoadingView.USERS_TYPE));
            }
            return;
        }
        if (store.isEmpty()) {
            // Пусто и «не смогли спросить» — разные вещи, и выглядеть они
            // должны по-разному: по чистому экрану не понять, что чинить.
            items.add(UItem.asShadow(LocaleController.getString(storeProblem != null
                    ? R.string.MargeletStoreFailed : R.string.MargeletStoreEmpty)));
        } else {
            for (int i = 0; i < store.size(); i++) {
                items.add(MargeletStoreCell.Factory.of(ID_STORE + i, store.get(i)));
            }
            items.add(UItem.asShadow(null));
        }
        items.add(UItem.asButton(ID_STORE_OPEN,
                LocaleController.getString(R.string.MargeletStoreOpenChannel)));
        items.add(UItem.asShadow(null));
    }

    private void clickStore(UItem item, View view, Integer position, Float x, Float y) {
        if (item.id == ID_STORE_OPEN) {
            Browser.openUrl(getContext(), "https://t.me/" + MargeletStore.CHANNEL);
            return;
        }
        if (item.id < ID_STORE) {
            return;
        }
        final int index = item.id - ID_STORE;
        if (index < 0 || index >= store.size()) {
            return;
        }
        MargeletStore.install(getContext(), store.get(index), () -> {
            refresh();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                    LocaleController.getString(R.string.MargeletPluginInstalled)).show();
        }, () -> BulletinFactory.of(this).createSimpleBulletin(R.raw.error,
                // Не «не удалось прочитать канал»: канал прочитан, список на
                // экране. Сорвалось скачивание одного файла, и говорить надо
                // про него, а не пугать человека тем, чего не было.
                LocaleController.getString(R.string.MargeletStoreNoFile)).show());
    }

    /** Спрашиваем канал один раз за открытие экрана, а не при каждом взгляде. */
    private void askStore() {
        if (storeAsked || storeLoading) {
            return;
        }
        storeAsked = true;
        storeLoading = true;
        refresh();
        MargeletStore.list((items, problem) -> {
            storeLoading = false;
            storeProblem = problem;
            store.clear();
            store.addAll(items);
            refresh();
        });
    }

    // --- общее ---

    private MargeletPlugins.Plugin plugin(int id) {
        final int index = id - ID_PLUGIN;
        return index >= 0 && index < plugins.size() ? plugins.get(index) : null;
    }

    /**
     * Главный выключатель. Включается через предупреждение: это единственное
     * место, где человек решает, пускать ли внутрь приложения чужой код.
     */
    private void toggleMaster() {
        if (MargeletConfig.pluginsEnabled()) {
            MargeletConfig.setPluginsEnabled(false);
            refresh();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(LocaleController.getString(R.string.MargeletPlugins))
                .setMessage(LocaleController.getString(R.string.MargeletPluginsWarn))
                .setPositiveButton(LocaleController.getString(R.string.MargeletSeizureEnable), (d, w) -> {
                    MargeletConfig.setPluginsEnabled(true);
                    MargeletPluginHost.start();
                    refresh();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    /**
     * Нажатие по строке плагина. У строки два смысла, и разводим их по месту
     * нажатия: справа переключатель — значит включить или выключить, слева
     * всё остальное — значит открыть настройки, если плагин их заявил.
     *
     * Нажатие приходит вместе с координатой, так что гадать не приходится.
     * У плагина без настроек строка целиком остаётся выключателем: пустой
     * экран вместо настроек был бы хуже, чем его отсутствие.
     */
    private void open(MargeletPlugins.Plugin plugin, View view, float x) {
        if (plugin == null) {
            return;
        }
        final int edge = org.telegram.messenger.AndroidUtilities.dp(60);
        final boolean onSwitch = LocaleController.isRTL
                ? x < edge
                : x > view.getWidth() - edge;
        if (!onSwitch && MargeletHooks.hasSettings(plugin.id)) {
            presentFragment(new MargeletPluginSettingsActivity(plugin));
            return;
        }
        toggle(plugin);
    }

    /**
     * Хуки включаются с предупреждением и только один раз осознанно.
     *
     * Не из вежливости: кривой хук может уронить приложение при запуске, и
     * тогда выключить его будет негде — настройки внутри того приложения,
     * которое не открывается. Защита от повторного падения стоит, но узнать
     * о такой цене человек должен заранее.
     */
    private void toggleHooks() {
        if (org.telegram.margelet.MargeletHookEngine.enabled()) {
            org.telegram.margelet.MargeletHookEngine.setEnabled(false);
            refresh();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(LocaleController.getString(R.string.MargeletHooks))
                .setMessage(LocaleController.getString(R.string.MargeletHooksWarn))
                .setPositiveButton(LocaleController.getString(R.string.MargeletSeizureEnable), (d, w) -> {
                    org.telegram.margelet.MargeletHookEngine.setEnabled(true);
                    refresh();
                    MargeletPlugins.restart(getParentActivity());
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void toggle(MargeletPlugins.Plugin plugin) {
        if (plugin == null) {
            return;
        }
        final boolean on = !plugin.enabled();
        MargeletConfig.setPluginEnabled(plugin.id, on);
        refresh();
        if (on && MargeletConfig.pluginsEnabled()) {
            // Включение перезапуска не требует: плагин поднимается сразу.
            MargeletPluginHost.launch(plugin);
        } else if (!on) {
            // А вот выключение — требует. Остановить уже работающий питон
            // нечем, и делать вид, что галочка его убила, нельзя. Раньше здесь
            // была подсказка внизу экрана: она честно об этом говорила, но
            // человеку оставалось закрывать телеграм самому. Спрашиваем прямо.
            new AlertDialog.Builder(getContext())
                    .setTitle(plugin.name)
                    .setMessage(LocaleController.getString(R.string.MargeletPluginStopHint))
                    .setPositiveButton(LocaleController.getString(R.string.MargeletPluginsRestart),
                            (d, w) -> MargeletPlugins.restart(getParentActivity()))
                    .setNegativeButton(LocaleController.getString(R.string.MargeletLater), null)
                    .show();
        }
    }

    /** Карточка плагина: кто написал, что заявил, и кнопка «удалить». */
    private void about(MargeletPlugins.Plugin plugin) {
        if (plugin == null) {
            return;
        }
        final SpannableStringBuilder text = new SpannableStringBuilder();
        if (plugin.description.length() > 0) {
            text.append(plugin.description).append("\n\n");
        }
        if (plugin.usesHooks()) {
            text.append(LocaleController.getString(R.string.MargeletPluginUsesHooks)).append("\n\n");
        }
        text.append(LocaleController.getString(R.string.MargeletPluginDeclares));
        if (plugin.permissions.isEmpty()) {
            text.append("\n— ").append(LocaleController.getString(R.string.MargeletPluginPermNone));
        } else {
            for (String permission : plugin.permissions) {
                text.append("\n— ").append(MargeletPlugins.permissionName(permission));
            }
        }
        new AlertDialog.Builder(getContext())
                .setTitle(plugin.name + " " + plugin.version)
                .setMessage(text)
                .setPositiveButton(LocaleController.getString(R.string.Close), null)
                .setNegativeButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    MargeletPlugins.remove(plugin);
                    refresh();
                })
                .show();
    }

    private void pickFile() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        // Своего типа у .marp в системе нет, поэтому просим любой файл.
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_FILE);
        } catch (Exception ignored) {
            // Не на каждом телефоне есть чем открыть выбор файла.
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILE || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        boolean known = false;
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                known = MargeletPlugins.askInstall(getContext(), in, () -> {
                    refresh();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info,
                            LocaleController.getString(R.string.MargeletPluginInstalled)).show();
                });
            }
        } catch (Exception ignored) {
        }
        if (!known) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error,
                    LocaleController.getString(R.string.MargeletPluginBadFile)).show();
        }
    }
}
