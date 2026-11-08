package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterScriptItemBinding;
import com.fongmi.android.tv.databinding.DialogScriptManageBinding;
import com.fongmi.android.tv.databinding.DialogScriptUrlBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.custom.CustomRecyclerView;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.LocalScriptSource;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本管理：外观与按钮风格参照「登录态学习窗口」，两栏站点交换参照 AVBox xbox 的
 * SiteManagePage。支持多个脚本文件夹、分类筛选、上下栏全选/反选、上移/下移、
 * 位置互换、在线 html 录入与编辑（名称 / url / 代理）。
 *
 * 所有改动即时写回目录下「脚本扫描.json」，脚本文件本身一个字节不动。
 */
public final class ScriptManageDialog extends BaseAlertDialog {

    private DialogScriptManageBinding binding;
    private Runnable callback;
    private String currentDir = "";
    private String filter = "all";
    private boolean dirty;
    private List<JSONObject> sites = new ArrayList<>();
    private List<JSONObject> bac = new ArrayList<>();
    private final Set<String> keepSel = new LinkedHashSet<>();
    private final Set<String> bacSel = new LinkedHashSet<>();
    private final List<MaterialButton> filterChips = new ArrayList<>();
    private final String[] filters = {"all", "py", "js", "html", "url"};
    private ItemAdapter keepAdapter;
    private ItemAdapter bacAdapter;

    private final ActivityResultLauncher<Intent> folderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                onFolderPicked(FileChooser.getPathFromUri(uri));
            });

    public static void show(Fragment fragment) {
        show(fragment.requireActivity(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments())
            if (f instanceof ScriptManageDialog && f.isAdded() && !f.isRemoving()) return;
        ScriptManageDialog dialog = new ScriptManageDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), "ScriptManageDialog");
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogScriptManageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(getBinding().getRoot());
    }

    /** 窗口参数在 show 之前定好，避免首帧居中后跳位（A 类 bug） */
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        configureWindow(dialog);
        return dialog;
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        boolean land = ResUtil.isLand(requireContext());
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * 0.96f);
        params.height = (int) (ResUtil.getScreenHeight(requireContext()) * (land ? 0.96f : 0.90f));
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(requireContext(), R.color.transparent)));
        window.getDecorView().setPadding(0, 0, 0, 0);
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        if (binding != null) {
            ViewGroup.LayoutParams lp = binding.getRoot().getLayoutParams();
            if (lp == null) lp = new ViewGroup.LayoutParams(params.width, params.height);
            lp.width = params.width;
            lp.height = params.height;
            binding.getRoot().setLayoutParams(lp);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog()); // 幂等兜底，同值不产生可见跳变
        applyColumnLayout();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureWindow(getDialog());
        applyColumnLayout();
    }

    /** 始终左右两列；横竖屏只由窗口尺寸与图标密度适配，不再切上下布局。 */
    private void applyColumnLayout() {
        if (binding == null) return;
        binding.columns.setOrientation(LinearLayoutCompat.HORIZONTAL);
        LinearLayoutCompat.LayoutParams cp =
                (LinearLayoutCompat.LayoutParams) binding.columns.getLayoutParams();
        cp.height = 0;
        cp.weight = 1f;
        binding.columns.setLayoutParams(cp);
        for (LinearLayoutCompat col : new LinearLayoutCompat[]{binding.keepColumn, binding.bacColumn}) {
            LinearLayoutCompat.LayoutParams lp = (LinearLayoutCompat.LayoutParams) col.getLayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 1f;
            col.setLayoutParams(lp);
        }
        for (CustomRecyclerView rv : new CustomRecyclerView[]{binding.keepRecycler, binding.bacRecycler}) {
            LinearLayoutCompat.LayoutParams lp = (LinearLayoutCompat.LayoutParams) rv.getLayoutParams();
            lp.height = 0;
            lp.weight = 1f;
            rv.setLayoutParams(lp);
            rv.setMaxHeight(0);
        }
    }

    @Override
    protected void initView() {
        keepAdapter = new ItemAdapter(false);
        bacAdapter = new ItemAdapter(true);
        binding.keepRecycler.setItemAnimator(null);
        binding.bacRecycler.setItemAnimator(null);
        binding.keepRecycler.setAdapter(keepAdapter);
        binding.bacRecycler.setAdapter(bacAdapter);
        buildRows();
        applyColumnLayout();
        bindState();
    }

    @Override
    protected void initEvent() {
        binding.reset.setOnClickListener(v -> onTool(1));
        binding.refresh.setOnClickListener(v -> {
            if (TextUtils.isEmpty(currentDir)) return;
            dirty = true;
            Task.execute(() -> {
                LocalScriptSource.refresh(currentDir);
                App.post(this::bindState);
            });
        });
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
    }

    /* ---------------- 顶部若干行按钮 ---------------- */

    private void buildRows() {
        // 第 4 行：分类 + 在线html录入（外圈、分散对齐）
        String[] labels = {getString(R.string.script_all), "PY", "JS", "HTML", "URL"};
        for (int i = 0; i < filters.length; i++) {
            MaterialButton chip = makeChip(labels[i], i == 0, true);
            int idx = i;
            chip.setOnClickListener(v -> {
                filter = filters[idx];
                render();
            });
            chip.setTextSize(11);
            chip.setPadding(dp(3), dp(6), dp(3), dp(6));
            filterChips.add(chip);
            binding.filterRow.addView(chip);
        }
        MaterialButton add = makeChip(getString(R.string.script_add_url_chip), false, true);
        add.setTextSize(11);
        add.setPadding(dp(3), dp(6), dp(3), dp(6));
        add.setCheckable(false);
        add.setOnClickListener(v -> onTool(0));
        binding.filterRow.addView(add);

        // 第 5 行：固定 264dp 一行；「全」「反」分列栏名两侧，图标与文字同尺寸
        addText(binding.actionLeft, "全", v -> onSelect(0), 0);
        addLabel(binding.actionLeft, R.string.script_work_label, 2);
        addText(binding.actionLeft, "反", v -> onSelect(1), 2);
        addIcon(binding.actionCenter, R.drawable.ic_player_button_up, v -> onTool(3), 0);
        addIcon(binding.actionCenter, R.drawable.ic_script_swap, v -> onTool(2), 6);
        addIcon(binding.actionCenter, R.drawable.ic_player_button_down, v -> onTool(4), 6);
        addText(binding.actionRight, "全", v -> onSelect(2), 0);
        addLabel(binding.actionRight, R.string.script_bin_label, 2);
        addText(binding.actionRight, "反", v -> onSelect(3), 2);
    }

    /** 图标按钮：固定 24dp，与文字按钮同尺寸；gapDp 由调用方给定，不动态算。 */
    private void addIcon(LinearLayoutCompat row, int icon, View.OnClickListener click, int gapDp) {
        AppCompatImageView view = new AppCompatImageView(requireContext());
        view.setImageResource(icon);
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        view.setPadding(dp(3), dp(3), dp(3), dp(3));
        view.setFocusable(true);
        view.setColorFilter(ContextCompat.getColor(requireContext(), R.color.dialog_text_button));
        view.setOnClickListener(click);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(dp(24), dp(24));
        lp.setMarginStart(dp(gapDp));
        row.addView(view, lp);
    }

    /** 「全」「反」纯文字按钮：14sp、固定 24dp，无任何文字提示。 */
    private void addText(LinearLayoutCompat row, String text, View.OnClickListener click, int gapDp) {
        android.widget.TextView view = new android.widget.TextView(requireContext());
        view.setText(text);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(14);
        view.setTextColor(ContextCompat.getColor(requireContext(), R.color.dialog_text_button));
        view.setFocusable(true);
        view.setOnClickListener(click);
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(dp(24), dp(24));
        lp.setMarginStart(dp(gapDp));
        row.addView(view, lp);
    }

    /** 段标签：13sp、宽度自适应、不省略；左右段由 weight 撑满，文字完整显示。 */
    private void addLabel(LinearLayoutCompat row, int text, int gapDp) {
        android.widget.TextView view = new android.widget.TextView(requireContext());
        view.setText(text);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(13);
        view.setTextColor(ContextCompat.getColor(requireContext(), R.color.dialog_outlined_button_text));
        LinearLayoutCompat.LayoutParams lp = new LinearLayoutCompat.LayoutParams(
                LinearLayoutCompat.LayoutParams.WRAP_CONTENT, dp(24));
        lp.setMarginStart(dp(gapDp));
        row.addView(view, lp);
    }

    /** 按钮样式完全照 app 现成的 MaterialButton 写法（见 dialog_shell_proxy.xml /
     *  mobile SiteDialog.getGroupView）：OutlinedButton style + dialog_* 色表。
     *  选中态用现成的 dialog_toggle_button_bg / text（含 state_checked），
     *  不再自造 TextView、不再写死字号、不再用加粗硬凑对比度。 */
    private MaterialButton makeChip(String text, boolean selected, boolean fill) {
        MaterialButton view = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(text);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER);
        view.setAllCaps(false);
        view.setMinWidth(0);
        view.setMinHeight(0);
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
        view.setInsetTop(0);
        view.setInsetBottom(0);
        view.setPadding(dp(6), dp(7), dp(6), dp(7));
        view.setCheckable(true);
        view.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_toggle_button_text));
        view.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.dialog_toggle_button_bg));
        view.setStrokeColor(ContextCompat.getColorStateList(requireContext(), R.color.dialog_outlined_button_stroke));
        applyChipStyle(view, selected);
        LinearLayoutCompat.LayoutParams lp = fill
                ? new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(4));
        view.setLayoutParams(lp);
        return view;
    }

    private void applyChipStyle(MaterialButton view, boolean selected) {
        view.setChecked(selected);
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }

    /* ---------------- 文件夹切换 ---------------- */

    private void renderDirs() {
        binding.dirList.removeAllViews();
        List<String> dirs = LocalScriptSource.dirs();
        Map<String, Integer> seen = new HashMap<>();
        for (String dir : dirs) {
            String name = new File(dir).getName();
            int n = seen.containsKey(name) ? seen.get(name) : 0;
            seen.put(name, n + 1);
            String label = n == 0 ? name : name + "|" + n;
            MaterialButton chip = makeChip(label, dir.equals(currentDir), false);
            chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.setMaxWidth(dp(180));
            chip.setOnClickListener(v -> {
                currentDir = dir;
                keepSel.clear();
                bacSel.clear();
                bindState();
            });
            binding.dirList.addView(chip);
        }
        MaterialButton add = makeChip("+ " + getString(R.string.script_add_folder), false, false);
        add.setOnClickListener(v -> FileChooser.from(folderLauncher).showDirectory());
        binding.dirList.addView(add);
    }

    private void onFolderPicked(String path) {
        if (TextUtils.isEmpty(path) || !new File(path).isDirectory()) return;
        if (!LocalScriptSource.addDir(path)) {
            Notify.show(R.string.script_folder_exists);
            return;
        }
        // 登记成点播接口，这样点播左上角接口列表里就能切换到这个文件夹的 json
        Config.create(0, LocalScriptSource.configUrl(path), new File(path).getName());
        int count = LocalScriptSource.scripts(path).size();
        Notify.show(getString(R.string.script_folder_added, count));
        currentDir = path;
        dirty = true;
        bindState();
    }

    /* ---------------- 数据绑定 ---------------- */

    private void bindState() {
        if (binding == null) return;
        List<String> dirs = LocalScriptSource.dirs();
        if (dirs.isEmpty()) currentDir = "";
        else if (TextUtils.isEmpty(currentDir) || !dirs.contains(currentDir)) currentDir = dirs.get(0);
        renderDirs();
        final String dir = currentDir;
        Task.execute(() -> {
            List<JSONObject> s = new ArrayList<>();
            List<JSONObject> b = new ArrayList<>();
            if (!TextUtils.isEmpty(dir)) {
                Pair<List<JSONObject>, List<JSONObject>> pair = LocalScriptSource.load(dir);
                s = pair.first;
                b = pair.second;
            }
            List<JSONObject> fs = s;
            List<JSONObject> fb = b;
            App.post(() -> {
                sites = fs;
                bac = fb;
                render();
            });
        });
    }

    private List<JSONObject> visible(List<JSONObject> list) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : list) {
            if (!"all".equals(filter) && !filter.equals(LocalScriptSource.kindOf(item))) continue;
            try {
                out.add(new JSONObject(item.toString()));
            } catch (Exception ignored) {
                out.add(item);
            }
        }
        return out;
    }

    private Set<String> keysOf(List<JSONObject> list) {
        Set<String> out = new HashSet<>();
        for (JSONObject item : list) out.add(item.optString("key"));
        return out;
    }

    private void render() {
        if (binding == null) return;
        List<JSONObject> keep = visible(sites);
        List<JSONObject> gone = visible(bac);
        // 显示层双重保护：同名加 |N 序号、相同 url 只留第一个
        LocalScriptSource.dedupNames(keep, gone);
        LocalScriptSource.dedupUrls(keep, gone);
        binding.summary.setText(TextUtils.isEmpty(currentDir)
                ? getString(R.string.script_empty) : currentDir);
        keepAdapter.setItems(keep);
        bacAdapter.setItems(gone);
        for (int i = 0; i < filterChips.size(); i++)
            applyChipStyle(filterChips.get(i), filters[i].equals(filter));
    }

    /* ---------------- 操作 ---------------- */

    private void onTool(int idx) {
        if (TextUtils.isEmpty(currentDir)) return;
        switch (idx) {
            case 0:
                showUrlDialog(null);
                return;
            case 1:
                runOp(() -> LocalScriptSource.reset(currentDir));
                break;
            case 2:
                runOp(() -> {
                    if (LocalScriptSource.swap(currentDir, keepSel, bacSel)) {
                        keepSel.clear();
                        bacSel.clear();
                    }
                });
                break;
            case 3:
                runOp(() -> reorder(true));
                break;
            case 4:
                runOp(() -> reorder(false));
                break;
            default:
                break;
        }
    }

    private void reorder(boolean up) {
        if (!keepSel.isEmpty())
            LocalScriptSource.reorder(currentDir, keepSel, up, false, keysOf(visible(sites)));
        if (!bacSel.isEmpty())
            LocalScriptSource.reorder(currentDir, bacSel, up, true, keysOf(visible(bac)));
    }

    private void onSelect(int idx) {
        List<JSONObject> list = idx < 2 ? visible(sites) : visible(bac);
        Set<String> target = idx < 2 ? keepSel : bacSel;
        boolean invert = idx == 1 || idx == 3;
        Set<String> keys = keysOf(list);
        if (invert) {
            Set<String> next = new LinkedHashSet<>(keys);
            next.removeAll(target);
            target.clear();
            target.addAll(next);
        } else {
            target.clear();
            target.addAll(keys);
        }
        keepAdapter.notifyDataSetChanged();
        bacAdapter.notifyDataSetChanged();
    }

    private void runOp(Runnable op) {
        Task.execute(() -> {
            op.run();
            App.post(() -> {
                dirty = true;
                bindState();
            });
        });
    }

    private void toggle(boolean inBac, String key) {
        Set<String> sel = inBac ? bacSel : keepSel;
        if (!sel.remove(key)) sel.add(key);
        keepAdapter.notifyDataSetChanged();
        bacAdapter.notifyDataSetChanged();
    }

    /* ---------------- 远程 html URL ---------------- */

    private void showUrlDialog(@Nullable JSONObject editing) {
        if (TextUtils.isEmpty(currentDir)) return;
        DialogScriptUrlBinding b = DialogScriptUrlBinding.inflate(getLayoutInflater());
        b.title.setText(editing == null ? R.string.script_add_url : R.string.script_edit_url);
        if (editing != null) {
            String name = editing.optString("name");
            if (name.endsWith("[url]")) name = name.substring(0, name.length() - 5);
            b.name.setText(name);
            String url = editing.optString("homePage");
            b.url.setText(url);
            b.proxy.setChecked("proxy".equals(editing.optString("proxy"))
                    || url.endsWith("#" + LocalScriptSource.PROXY_KEY));
        }
        Dialog dialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(b.getRoot()).create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(requireContext(), R.color.transparent)));
        b.delete.setVisibility(editing == null ? View.GONE : View.VISIBLE);
        b.negative.setOnClickListener(v -> dialog.dismiss());
        b.delete.setOnClickListener(v -> {
            dialog.dismiss();
            final String deleteKey = editing == null ? "" : editing.optString("key");
            if (deleteKey.isEmpty()) return;
            Task.execute(() -> {
                LocalScriptSource.removeUrl(currentDir, deleteKey);
                App.post(() -> {
                    dirty = true;
                    Notify.show(R.string.script_deleted);
                    bindState();
                });
            });
        });
        b.positive.setOnClickListener(v -> {
            String name = b.name.getText().toString().trim();
            String url = b.url.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Notify.show(R.string.script_name_required);
                return;
            }
            if (url.isEmpty()) {
                Notify.show(R.string.script_url_required);
                return;
            }
            boolean proxy = b.proxy.isChecked();
            dialog.dismiss();
            String key = editing == null ? "" : editing.optString("key");
            Task.execute(() -> {
                boolean ok = editing == null
                        ? LocalScriptSource.addUrl(currentDir, name, url, proxy)
                        : LocalScriptSource.updateUrl(currentDir, key, name, url, proxy);
                App.post(() -> {
                    if (!ok) {
                        Notify.show(R.string.script_url_duplicate);
                        return;
                    }
                    dirty = true;
                    Notify.show(R.string.script_url_saved);
                    bindState();
                });
            });
        });
        dialog.show();
    }

    private void onPositive() {
        boolean changed = dirty;
        dismiss();
        if (callback != null) callback.run();
        if (changed && LocalScriptSource.isStoreUrl(VodConfig.getUrl())) {
            VodConfig.load(VodConfig.get().getConfig(), new Callback() {
                @Override
                public void success() {
                    Notify.show(R.string.script_reloaded);
                }
            });
        }
    }

    /* ---------------- 两栏列表 ---------------- */

    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

        private final boolean inBac;
        private List<JSONObject> items = new ArrayList<>();

        ItemAdapter(boolean inBac) {
            this.inBac = inBac;
        }

        void setItems(List<JSONObject> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterScriptItemBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JSONObject item = items.get(position);
            String key = item.optString("key");
            boolean selected = (inBac ? bacSel : keepSel).contains(key);
            holder.binding.getRoot().setSelected(selected);
            holder.binding.name.setText(item.optString("name"));
            holder.binding.edit.setVisibility(LocalScriptSource.isEditable(item) ? View.VISIBLE : View.GONE);
            holder.binding.getRoot().setOnClickListener(v -> toggle(inBac, key));
            holder.binding.edit.setOnClickListener(v -> showUrlDialog(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final AdapterScriptItemBinding binding;

            Holder(AdapterScriptItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
