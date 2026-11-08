package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityAppBrandingBinding;
import com.fongmi.android.tv.setting.AppBranding;
import com.fongmi.android.tv.ui.base.BaseActivity;

public class AppBrandingActivity extends BaseActivity {

    private ActivityAppBrandingBinding mBinding;
    private int selectedIconMode;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AppBrandingActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAppBrandingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        selectedIconMode = AppBranding.getIconMode(this);
        updateSelection();
        mBinding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    protected void initEvent() {
        mBinding.iconCurrent.setOnClickListener(v -> selectIcon(AppBranding.ICON_CURRENT));
        mBinding.iconHistory.setOnClickListener(v -> selectIcon(AppBranding.ICON_HISTORY));
        mBinding.cancel.setOnClickListener(v -> finish());
        mBinding.save.setOnClickListener(v -> save());
    }

    private void selectIcon(int mode) {
        selectedIconMode = mode;
        updateSelection();
    }

    private void updateSelection() {
        setSelected(mBinding.iconCurrent, mBinding.iconCurrentText, selectedIconMode == AppBranding.ICON_CURRENT);
        setSelected(mBinding.iconHistory, mBinding.iconHistoryText, selectedIconMode == AppBranding.ICON_HISTORY);
    }

    private void setSelected(LinearLayoutCompat container, TextView label, boolean selected) {
        container.setSelected(selected);
        label.setTextColor(selected ? getColor(R.color.display_option_bg_selected) : getColor(R.color.white));
    }

    private void save() {
        AppBranding.putIconMode(selectedIconMode);
        AppBranding.applyLauncherIcon(this);
        setResult(RESULT_OK);
        try {
            // 两个 launcher alias 已在 Manifest 声明；即使个别 ROM 上组件状态异常，
            // 也只降级为「不重启」，绝不把 ActivityNotFoundException 抛给用户。
            Intent intent = AppBranding.launcherIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // 图标偏好已保存，下次冷启动由 AppBranding.applyLauncherIcon 生效。
        }
        finish();
    }
}
