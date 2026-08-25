package org.telegram.margelet.drawer;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MargeletSettingsActivity;
import org.telegram.ui.MargeletPluginsActivity;
import org.telegram.ui.SettingsActivity;

public class DrawerMenuView extends ScrollView {
    private final LinearLayout container;
    private Runnable onItemClick;

    public DrawerMenuView(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, AndroidUtilities.dp(8.0f), 0, AndroidUtilities.dp(8.0f) + AndroidUtilities.navigationBarHeight);
        addView(container, new FrameLayout.LayoutParams(-1, -2));
    }

    public void setOnItemClick(Runnable runnable) {
        this.onItemClick = runnable;
    }

    public void rebuildMenu(int currentAccount, BaseFragment fragment) {
        container.removeAllViews();
        if (fragment == null) {
            // Экран ещё не готов (запуск, поворот) — показывать пустое меню
            // не надо: лучше спрятать список и собрать его при открытии.
            final LaunchActivity activity = LaunchActivity.instance;
            fragment = activity != null ? activity.getLastFragment() : null;
        }
        if (fragment == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        addItem(R.drawable.msg_groups, LocaleController.getString(R.string.NewGroup), () -> {
            fragment.presentFragment(new GroupCreateActivity(new android.os.Bundle()));
        });
        addItem(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> {
            fragment.presentFragment(new ContactsActivity(null));
        });
        addItem(R.drawable.msg_calls, LocaleController.getString(R.string.Calls), () -> {
            fragment.presentFragment(new CallLogActivity());
        });
        addItem(R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), () -> {
            fragment.presentFragment(ChatActivity.of(UserConfig.getInstance(currentAccount).getClientUserId()));
        });

        addDivider();

        addItem(R.drawable.settings_features, "Margelet", () -> {
            fragment.presentFragment(new MargeletSettingsActivity());
        });
        addItem(R.drawable.settings_devices, LocaleController.getString(R.string.MargeletPlugins), () -> {
            fragment.presentFragment(new MargeletPluginsActivity());
        });
        addItem(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> {
            fragment.presentFragment(new SettingsActivity());
        });
    }

    private void addItem(int iconRes, CharSequence text, Runnable onClick) {
        DrawerMenuItemView item = new DrawerMenuItemView(getContext());
        item.setMenuItem(iconRes, text);
        item.setOnClickListener(v -> {
            if (onItemClick != null) onItemClick.run();
            if (onClick != null) onClick.run();
        });
        container.addView(item);
    }

    private void addDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) (1.0f * AndroidUtilities.density))
        );
        lp.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        container.addView(divider, lp);
    }

    public void updateColors() {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof DrawerMenuItemView) {
                ((DrawerMenuItemView) child).updateColors();
            } else {
                child.setBackgroundColor(Theme.getColor(Theme.key_divider));
            }
        }
    }
}
