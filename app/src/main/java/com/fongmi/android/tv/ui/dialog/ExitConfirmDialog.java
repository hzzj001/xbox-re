package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 退出确认：直接用项目现成的 BaseAlertDialog + MaterialAlertDialogBuilder。
 * 不再用自定义卡片布局 —— 描述精简为只有标题、按钮一次点击即响应、
 * 手机与 TV 共用，样式跟随 app 主题。
 */
public final class ExitConfirmDialog extends BaseAlertDialog {

    private Runnable onConfirm;

    public static ExitConfirmDialog create(Runnable onConfirm) {
        ExitConfirmDialog dialog = new ExitConfirmDialog();
        dialog.onConfirm = onConfirm;
        return dialog;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof ExitConfirmDialog) return;
        }
        show(activity.getSupportFragmentManager(), ExitConfirmDialog.class.getSimpleName());
    }

    @Override
    protected ViewBinding getBinding() {
        // 用 MaterialAlertDialog 自带按钮，不需要自定义 content view
        return null;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                .setTitle(R.string.exit_confirm_title)
                .setNegativeButton(R.string.dialog_negative, (dialog, which) -> dismiss())
                .setPositiveButton(R.string.exit_confirm_positive, (dialog, which) -> {
                    Runnable confirm = onConfirm;
                    dismissAllowingStateLoss();
                    if (confirm != null) confirm.run();
                });
    }

    @Override
    protected void setupWindowBeforeShow(Dialog dialog) {
        if (dialog != null) dialog.setCanceledOnTouchOutside(false);
        super.setupWindowBeforeShow(dialog);
        if (dialog == null) return;
        // 默认焦点给「取消」，TV 上按一次确认就取消，不会误退出
        dialog.setOnShowListener(di -> {
            if (!(di instanceof Dialog)) return;
            Dialog d = (Dialog) di;
            View cancel = d.getWindow() == null ? null : d.getWindow().findViewById(android.R.id.button2);
            if (cancel != null) cancel.requestFocus();
        });
    }
}
