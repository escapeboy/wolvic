package com.igalia.wolvic.ui.widgets.settings;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.databinding.DataBindingUtil;

import com.igalia.wolvic.AppExecutors;
import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserApplication;
import com.igalia.wolvic.databinding.OptionsStenaBinding;
import com.igalia.wolvic.stena.StenaIntro;
import com.igalia.wolvic.stena.StenaLists;
import com.igalia.wolvic.stena.StenaSettings;
import com.igalia.wolvic.ui.views.settings.ButtonSetting;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;

import java.util.ArrayList;
import java.util.List;

/** Diazoma's settings: the extension's options page (apps/extension/src/options/options.ts) for this browser. */
class StenaOptionsView extends SettingsView {

    private OptionsStenaBinding mBinding;
    private StenaSettings mSettings;
    private StenaLists mLists;
    private AppExecutors mExecutors;

    public StenaOptionsView(Context aContext, WidgetManagerDelegate aWidgetManager) {
        super(aContext, aWidgetManager);
        mSettings = StenaSettings.get(aContext);
        mLists = StenaLists.get(aContext);
        mExecutors = ((VRBrowserApplication) aContext.getApplicationContext()).getExecutors();
        updateUI();
    }

    @Override
    protected void updateUI() {
        super.updateUI();

        mBinding = DataBindingUtil.inflate(LayoutInflater.from(getContext()), R.layout.options_stena, this, true);
        mScrollbar = mBinding.scrollbar;
        mBinding.headerLayout.setBackClickListener(view -> onDismiss());
        mBinding.footerLayout.setFooterButtonClickListener(v -> resetOptions());

        mBinding.autoDetectSwitch.setValue(mSettings.isAutoDetect(), false);
        mBinding.autoDetectSwitch.setOnCheckedChangeListener((button, value, apply) -> mSettings.setAutoDetect(value));

        mBinding.inputProfileRadio.setChecked(mBinding.inputProfileRadio.getIdForValue(mSettings.getInputProfile()), false);
        mBinding.inputProfileRadio.setOnCheckedChangeListener((group, checkedId, apply) ->
                mSettings.setInputProfile((String) mBinding.inputProfileRadio.getValueForId(checkedId)));

        mBinding.introAgain.setOnClickListener(v -> StenaIntro.show(getContext()));

        mBinding.listsStatus.setOnClickListener(v -> {
            mBinding.listsStatus.setDescription(getContext().getString(R.string.stena_settings_checking));
            mExecutors.networkIO().execute(() -> {
                mLists.updateAll();
                mExecutors.mainThread().execute(this::renderLists);
            });
        });

        mBinding.addListEdit.setHint1(getContext().getString(R.string.stena_settings_add_list_hint));
        mBinding.addListEdit.setOnSaveClickedListener(v -> {
            String url = mBinding.addListEdit.getFirstText();
            if (url.trim().isEmpty()) {
                return;
            }
            mExecutors.networkIO().execute(() -> {
                String refused = mLists.addCustom(url);
                mExecutors.mainThread().execute(() -> {
                    if (refused != null) {
                        showAlert(getContext().getString(R.string.stena_settings_list_refused), refused);
                    } else {
                        mBinding.addListEdit.setFirstText("");
                    }
                    renderLists();
                });
            });
        });

        renderLists();
        renderSites();
    }

    private void renderLists() {
        StenaLists.Info core = mLists.info(StenaLists.CORE_URL);
        StenaLists.Status status = mLists.status();
        String text = core == null
                ? getContext().getString(R.string.stena_settings_core_list_not_loaded)
                : getContext().getString(R.string.stena_settings_core_list_loaded, core.revision, core.recipes);
        if (status.at > 0) {
            CharSequence when = DateUtils.getRelativeTimeSpanString(status.at);
            text += "\n" + (status.problems == null
                    ? getContext().getString(R.string.stena_settings_last_checked, when)
                    : getContext().getString(R.string.stena_settings_last_check_failed, when, status.problems));
        }
        mBinding.listsStatus.setDescription(text);

        ViewGroup box = mBinding.customLists;
        box.removeAllViews();
        for (String url : mSettings.getCustomLists()) {
            StenaLists.Info info = mLists.info(url);
            ButtonSetting row = new ButtonSetting(getContext(), null);
            row.setDescription(info == null
                    ? getContext().getString(R.string.stena_settings_custom_list_not_loaded, url)
                    : getContext().getString(R.string.stena_settings_custom_list_loaded, url, info.revision, info.recipes));
            row.setButtonText(R.string.stena_settings_remove);
            row.setOnClickListener(v -> {
                mLists.removeCustom(url);
                renderLists();
            });
            box.addView(row);
        }
    }

    private void renderSites() {
        ViewGroup box = mBinding.offSites;
        box.removeAllViews();
        List<String> sites = new ArrayList<>(mSettings.getOffSites());
        java.util.Collections.sort(sites);
        if (sites.isEmpty()) {
            ButtonSetting none = new ButtonSetting(getContext(), null);
            none.setDescription(getContext().getString(R.string.stena_settings_no_off_sites));
            none.setShowAsLabel(true);
            none.setButtonText("");
            box.addView(none);
            return;
        }
        for (String origin : sites) {
            ButtonSetting row = new ButtonSetting(getContext(), null);
            row.setDescription(getContext().getString(R.string.stena_settings_off_site, origin));
            row.setButtonText(R.string.stena_settings_turn_on);
            row.setOnClickListener(v -> {
                mSettings.setOff(origin, false);
                renderSites();
            });
            box.addView(row);
        }
    }

    private void resetOptions() {
        mSettings.setAutoDetect(true);
        mSettings.setInputProfile(StenaSettings.PROFILE_DEFAULT);
        mBinding.autoDetectSwitch.setValue(true, false);
        mBinding.inputProfileRadio.setChecked(mBinding.inputProfileRadio.getIdForValue(StenaSettings.PROFILE_DEFAULT), false);
    }

    @Override
    public void onHidden() {
        if (!isEditing()) {
            super.onHidden();
        }
    }

    @Override
    protected void onDismiss() {
        if (!isEditing()) {
            super.onDismiss();
        }
    }

    @Override
    public boolean isEditing() {
        if (mBinding.addListEdit.isEditing()) {
            mBinding.addListEdit.cancel();
            return true;
        }
        return false;
    }

    @Override
    protected SettingViewType getType() {
        return SettingViewType.STENA;
    }
}
