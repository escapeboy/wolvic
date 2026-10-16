package com.igalia.wolvic.stena;

import android.content.Context;
import android.view.Gravity;

import androidx.annotation.NonNull;

import com.igalia.wolvic.BuildConfig;
import com.igalia.wolvic.R;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;

/** The three-step intro after the terms and privacy dialogs: the wall button, "Open as wall" in the menu, Settings. */
public final class StenaIntro {
    private static final int[] TITLES = {R.string.stena_intro_title_1, R.string.stena_intro_title_2, R.string.stena_intro_title_3};
    private static final int[] BODIES = {R.string.stena_intro_body_1, R.string.stena_intro_body_2, R.string.stena_intro_body_3};

    private StenaIntro() {}

    /** On the first start only. The flag is set when the intro ends, however it ends. */
    public static void showIfNeeded(@NonNull Context context) {
        if (BuildConfig.FLAVOR_backend.equals("chromium") && !StenaSettings.get(context).isIntroShown()) {
            show(context);
        }
    }

    public static void show(@NonNull Context context) {
        PromptDialogWidget dialog = new PromptDialogWidget(context);
        dialog.setIcon(R.drawable.ic_stena_wall);
        dialog.setCheckboxVisible(false);
        dialog.setDescriptionVisible(false);
        dialog.setBodyGravity(Gravity.START);
        int[] step = {0};
        render(dialog, step[0]);
        dialog.setButtonsDelegate((index, isChecked) -> {
            boolean last = step[0] == TITLES.length - 1;
            // Skip and Done are the left button, Next the right one.
            if (index == PromptDialogWidget.POSITIVE && !last) {
                step[0]++;
                render(dialog, step[0]);
                return;
            }
            finish(context, dialog);
        });
        // A click outside the dialog counts as skipping; the widget is already hidden then.
        dialog.setDelegate(() -> {
            StenaSettings.get(context).setIntroShown(true);
            dialog.releaseWidget();
        });
        dialog.show(UIWidget.REQUEST_FOCUS);
    }

    private static void render(PromptDialogWidget dialog, int step) {
        dialog.setTitle(TITLES[step]);
        dialog.setBody(BODIES[step]);
        dialog.setButtons(step == TITLES.length - 1
                ? new int[] {R.string.stena_intro_done}
                : new int[] {R.string.stena_intro_skip, R.string.stena_intro_next});
    }

    private static void finish(Context context, PromptDialogWidget dialog) {
        StenaSettings.get(context).setIntroShown(true);
        dialog.hide(UIWidget.REMOVE_WIDGET);
        dialog.releaseWidget();
    }
}
