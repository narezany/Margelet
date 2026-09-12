package org.telegram.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.margelet.MargeletConfig;
import org.telegram.margelet.MargeletPlane3D;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Корень своего раздела: ветки, а не свалка переключателей. Пока веток одна,
 * «Поле ввода», плюс две ссылки — канал и форум.
 *
 * Строки рисуются тем же классом, что и на главном экране настроек
 * (SettingsActivity.SettingCell): цветная плашка со значком, название,
 * подпись под ним. Первая версия была собрана на старых ячейках, и владелец
 * сразу заметил, что раздел выглядит из прошлой версии приложения.
 */
public class MargeletSettingsActivity extends UniversalFragment {

    private static final int ID_INPUT = 1;
    private static final int ID_SOUND = 2;
    private static final int ID_CHANNEL = 3;
    private static final int ID_FORUM = 4;
    private static final int ID_CONVENIENCES = 5;
    private static final int ID_UPDATES = 17;
    private static final int ID_STREAMER = 6;
    private static final int ID_GIFTS = 7;
    private static final int ID_SOURCE = 8;
    private static final int ID_PROFILES = 9;
    private static final int ID_DONATE = 11;
    private static final int ID_MARKUP = 12;
    private static final int ID_HELP = 13;
    private static final int ID_STICKERS = 14;
    private static final int ID_PLUGINS = 15;
    private static final int ID_FEEDBACK = 18;
    private static final int ID_MY_PROFILE = 19;
    private static final int ID_PROXY = 20;

    /** Объёмный значок в шапке. Пользы ноль, и в этом вся мысль. */
    private FrameLayout header;

    @Override
    protected CharSequence getTitle() {
        // Имя берём из одного места на всё приложение: зашитое здесь
        // пережило переименование и осталось старым.
        return org.telegram.margelet.MargeletConfig.APP_NAME;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (header == null && getContext() != null) {
            header = new FrameLayout(getContext());
            // Значок сидит в своём квадрате, а не во всю ширину строки: тогда
            // список тянется пальцем везде, кроме самого значка, и вертикальная
            // прокрутка не спорит с вращением.
            header.addView(new MargeletPlane3D(getContext()),
                    LayoutHelper.createFrame(150, 150, Gravity.CENTER));
        }
        if (header != null) {
            items.add(UItem.asCustomShadow(header, 168));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_INPUT,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.settings_chat, LocaleController.getString(R.string.MargeletInput), LocaleController.getString(R.string.MargeletInputInfo)));
        // Раздел «Звук» появляется, только когда мяуканье уже услышали.
        if (MargeletConfig.meowHeard()) {
            items.add(SettingsActivity.SettingCell.Factory.of(ID_SOUND,
                    IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom,
                    R.drawable.settings_sounds, LocaleController.getString(R.string.MargeletSound), LocaleController.getString(R.string.MargeletSoundInfo)));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_STREAMER,
                IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                R.drawable.settings_privacy, LocaleController.getString(R.string.MargeletStreamer),
                LocaleController.getString(R.string.MargeletStreamerInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_CONVENIENCES,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.settings_folders, LocaleController.getString(R.string.MargeletConveniences),
                LocaleController.getString(R.string.MargeletConveniencesInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_MARKUP,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.settings_language, LocaleController.getString(R.string.MargeletMarkup),
                LocaleController.getString(R.string.MargeletMarkupInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_PROFILES,
                IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                R.drawable.settings_account, LocaleController.getString(R.string.MargeletProfiles),
                LocaleController.getString(R.string.MargeletProfilesInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_GIFTS,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.settings_gift, LocaleController.getString(R.string.MargeletGifts),
                LocaleController.getString(R.string.MargeletGiftsInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_UPDATES,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.settings_devices, LocaleController.getString(R.string.MargeletUpdates),
                LocaleController.getString(R.string.MargeletUpdatesInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_MY_PROFILE,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                R.drawable.msg_openprofile, LocaleController.getString(R.string.MargeletProfileTitle),
                LocaleController.getString(R.string.MargeletProfileInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_PLUGINS,
                IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                R.drawable.settings_devices, LocaleController.getString(R.string.MargeletPlugins),
                LocaleController.getString(R.string.MargeletPluginsInfo)));
        items.add(UItem.asShadow(null));
        // Прокси стоит здесь, а не в глубине настроек, нарочно: он нужен ровно
        // тому, у кого телеграм не открывается, и искать его такому человеку
        // негде и некогда.
        items.add(UItem.asCheck(ID_PROXY, LocaleController.getString(R.string.MargeletProxy))
                .setChecked(MargeletConfig.proxyEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MargeletProxyAbout)));
        items.add(UItem.asButton(ID_STICKERS, LocaleController.getString(R.string.MargeletStickers),
                LocaleController.getString(R.string.MargeletStickersAdd)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MargeletStickersAbout)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_DONATE,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                R.drawable.settings_wallet, LocaleController.getString(R.string.MargeletDonate),
                LocaleController.getString(R.string.MargeletDonateInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_CHANNEL,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                R.drawable.settings_channel, LocaleController.getString(R.string.MargeletChannel), "t.me/margeletter"));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_FORUM,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                R.drawable.settings_group, LocaleController.getString(R.string.MargeletForum), "t.me/margeletforum"));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_FEEDBACK,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                R.drawable.msg_message, LocaleController.getString(R.string.MargeletFeedback),
                LocaleController.getString(R.string.MargeletFeedbackInfo)));
        items.add(SettingsActivity.SettingCell.Factory.of(ID_SOURCE,
                IconBackgroundColors.GRAY.top, IconBackgroundColors.GRAY.bottom,
                R.drawable.settings_features, LocaleController.getString(R.string.MargeletSource),
                LocaleController.getString(R.string.MargeletSourceInfo)));
        items.add(UItem.asShadow(null));
    }

    @Override
    public View createView(android.content.Context context) {
        header = null;      // прошлый экран уносит с собой своё окно
        final View view = super.createView(context);
        // Знак вопроса в шапке: короткий рассказ о том, что это вообще такое.
        // Свой обработчик ставится после родительского и потому заменяет его —
        // значит, «назад» надо обслужить самому.
        actionBar.createMenu().addItem(ID_HELP, R.drawable.outline_question_mark);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_HELP) {
                    new AlertDialog.Builder(getContext())
                            .setTitle(LocaleController.getString(R.string.MargeletAboutTitle))
                            .setMessage(LocaleController.getString(R.string.MargeletAboutText))
                            .setPositiveButton(LocaleController.getString(R.string.Close), null)
                            .show();
                }
            }
        });
        // Скруглённые карточки — так выглядят нынешние экраны настроек.
        // Без этой строки список рисуется сплошной лентой, как в прошлой
        // версии приложения: владелец это заметил сразу.
        listView.setSections();
        return view;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_PROXY) {
            final boolean on = !MargeletConfig.proxyEnabled();
            org.telegram.margelet.MargeletProxy.enable(on);
            // Спрашиваем настройку заново, а не верим своему «on»: включение
            // может и не удаться, и тогда галочка обязана это показать.
            ((org.telegram.ui.Cells.TextCheckCell) view)
                    .setChecked(MargeletConfig.proxyEnabled());
        } else if (item.id == ID_STICKERS) {
            Browser.openUrl(getContext(), MargeletConfig.STICKERS_URL);
        } else if (item.id == ID_PLUGINS) {
            presentFragment(new MargeletPluginsActivity());
        } else if (item.id == ID_MARKUP) {
            presentFragment(new MargeletMarkupActivity());
        } else if (item.id == ID_DONATE) {
            presentFragment(new MargeletDonateActivity());
        } else if (item.id == ID_INPUT) {
            presentFragment(new MargeletInputActivity());
        } else if (item.id == ID_SOUND) {
            presentFragment(new MargeletSoundActivity());
        } else if (item.id == ID_CHANNEL) {
            Browser.openUrl(getContext(), MargeletConfig.CHANNEL_URL);
        } else if (item.id == ID_PROFILES) {
            presentFragment(new MargeletProfilesActivity());
        } else if (item.id == ID_GIFTS) {
            presentFragment(new MargeletGiftsActivity());
        } else if (item.id == ID_STREAMER) {
            presentFragment(new MargeletStreamerActivity());
        } else if (item.id == ID_UPDATES) {
            presentFragment(new MargeletUpdatesActivity());
        } else if (item.id == ID_CONVENIENCES) {
            presentFragment(new MargeletConveniencesActivity());
        } else if (item.id == ID_SOURCE) {
            Browser.openUrl(getContext(), MargeletConfig.SOURCE_URL);
        } else if (item.id == ID_MY_PROFILE) {
            presentFragment(new MargeletProfileActivity());
        } else if (item.id == ID_FEEDBACK) {
            Browser.openUrl(getContext(), MargeletConfig.FEEDBACK_URL);
        } else if (item.id == ID_FORUM) {
            Browser.openUrl(getContext(), MargeletConfig.FORUM_URL);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

}
