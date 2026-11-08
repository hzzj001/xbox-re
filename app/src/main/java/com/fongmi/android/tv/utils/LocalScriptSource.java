package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Pair;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地脚本目录当点播订阅源。
 *
 * 目录根生成「脚本扫描.json」：sites（生效）、sites_bac（回收站）、lives（占位）。
 * 递归扫描 .py / .js / .html；路径或文件名含「代理」→ "proxy":"proxy"。
 * 配置地址就是该 json 文件（file:/...），VodConfig.load 前会重扫并回写，
 * 因此手动更新、重启、切换接口都会按目录内容刷新。
 *
 * 本轮只生成 proxy 字段，Site 解析与单站代理生效留到以后（按要求）。
 */
public final class LocalScriptSource {

    public static final String STORE_FILE = "脚本扫描.json";
    public static final String PROXY_KEY = "代理";
    public static final String PROXY_FLAG = "proxy";
    public static final String HTML_API = "Csp_Nostr";

    private static final String KEY_DIRS = "local_script_dirs";
    private static final String[] EXTS = {".py", ".js", ".html"};
    private static final int MAX_FILES = 2000;

    private LocalScriptSource() {
    }

    /* ---------------- 目录登记（支持多个文件夹） ---------------- */

    public static List<String> dirs() {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY_DIRS, "");
            if (TextUtils.isEmpty(raw)) return out;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String dir = array.optString(i, "");
                if (!TextUtils.isEmpty(dir) && new File(dir).isDirectory()) out.add(dir);
            }
        } catch (Throwable ignored) {
            // 登记数据异常时当作「没有文件夹」，绝不影响 App 启动
            return new ArrayList<>();
        }
        return out;
    }

    private static void saveDirs(List<String> list) {
        JSONArray array = new JSONArray();
        for (String dir : list) array.put(dir);
        Prefers.put(KEY_DIRS, array.toString());
    }

    public static boolean addDir(String dir) {
        if (TextUtils.isEmpty(dir) || !new File(dir).isDirectory()) return false;
        List<String> list = dirs();
        if (list.contains(dir)) return false;
        list.add(dir);
        saveDirs(list);
        load(dir);
        return true;
    }

    public static void removeDir(String dir) {
        List<String> list = dirs();
        if (list.remove(dir)) saveDirs(list);
    }

    public static File storeFile(String dir) {
        return new File(dir, STORE_FILE);
    }

    /** 与 ConfigDialog 选本地文件时的格式保持一致：file:/ + 相对 Path.rootPath() 的路径 */
    public static String configUrl(String dir) {
        String path = storeFile(dir).getAbsolutePath();
        String root = Path.rootPath();
        String rel = path.startsWith(root) ? path.substring(root.length()) : path;
        return "file:/" + rel;
    }

    public static boolean isStoreUrl(String url) {
        return !TextUtils.isEmpty(dirOfUrl(url));
    }

    public static String dirOfUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            for (String dir : dirs()) if (configUrl(dir).equals(url)) return dir;
        } catch (Throwable ignored) {
        }
        return "";
    }

    /* ---------------- 扫描 ---------------- */

    public static List<File> scripts(String dir) {
        List<File> out = new ArrayList<>();
        File root = new File(dir);
        if (!root.isDirectory()) return out;
        collect(root, out);
        // 首次生成 json 的次序：py -> js -> html -> url（url 恒排最后，见 insertPos()）；
        // 组内按绝对路径。已有 json 的次序由 load() 保留，不在这里重排。
        out.sort((a, b) -> {
            int byExt = extRank(a) - extRank(b);
            return byExt != 0 ? byExt : a.getAbsolutePath().compareTo(b.getAbsolutePath());
        });
        return out;
    }

    private static int extRank(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".py")) return 0;
        if (name.endsWith(".js")) return 1;
        if (name.endsWith(".html")) return 2;
        return 3;
    }

    private static void collect(File dir, List<File> out) {
        if (out.size() >= MAX_FILES) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (out.size() >= MAX_FILES) return;
            if (file.isDirectory()) collect(file, out);
            else if (isScript(file)) out.add(file);
        }
    }

    private static boolean isScript(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : EXTS) if (name.endsWith(ext)) return true;
        return false;
    }

    private static String extOf(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : EXTS) if (name.endsWith(ext)) return ext;
        return "";
    }

    private static String labelOf(String ext) {
        switch (ext) {
            case ".py": return "[py]";
            case ".js": return "[js]";
            case ".html": return "[html]";
            default: return "[url]";
        }
    }

    private static String keyOf(File root, File file) {
        String rel = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (rel.startsWith(base)) rel = rel.substring(base.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        String ext = extOf(file);
        int len = Math.max(0, rel.length() - ext.length());
        return rel.substring(0, len) + labelOf(ext);
    }

    private static String proxyOf(File root, File file) {
        String rel = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (rel.startsWith(base)) rel = rel.substring(base.length());
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        return rel.contains(PROXY_KEY) || file.getName().contains(PROXY_KEY) || parent.contains(PROXY_KEY)
                ? PROXY_FLAG : "";
    }

    private static JSONObject newItem(File root, File file) {
        JSONObject item = new JSONObject();
        String ext = extOf(file);
        String label = labelOf(ext);
        try {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            item.put("key", keyOf(root, file));
            item.put("name", (dot > 0 ? name.substring(0, dot) : name) + label);
            item.put("type", 3);
            if (".html".equals(ext)) {
                item.put("api", HTML_API);
                item.put("homePage", fileHomePage(file));
            } else {
                item.put("api", "file://" + file.getAbsolutePath());
            }
            item.put("ext", "");
            item.put("proxy", proxyOf(root, file));
            item.put("searchable", 1);
            item.put("changeable", 1);
            item.put("quickSearch", 1);
        } catch (Exception ignored) {
        }
        return item;
    }

    /** 本地 html 页面地址：file:///绝对路径（127.0.0.1/file 路由格式不被解析）。 */
    private static String fileHomePage(File file) {
        return "file://" + file.getAbsolutePath();
    }

    /** 旧 json 里的 127.0.0.1/file 地址迁移成 file:// 绝对路径，保留 #代理 尾部。 */
    private static boolean migrateHome(JSONObject item) {
        String home = item.optString("homePage");
        if (!home.contains("127.0.0.1")) return false;
        int idx = home.indexOf("/file/");
        if (idx < 0) return false;
        String rel = home.substring(idx + 6);
        int hash = rel.indexOf('#');
        String tail = hash >= 0 ? rel.substring(hash) : "";
        if (hash >= 0) rel = rel.substring(0, hash);
        String abs = new File(Path.rootPath(), rel).getAbsolutePath();
        try {
            item.put("homePage", "file://" + abs + tail);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isRemoteItem(JSONObject item) {
        String home = item.optString("homePage");
        return (home.startsWith("http://") || home.startsWith("https://")) && !home.contains("127.0.0.1");
    }

    /* ---------------- store 读写 ---------------- */

    private static String readText(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(text.getBytes("UTF-8"));
        out.flush();
        out.close();
    }

    private static JSONObject readStore(String dir) {
        File file = storeFile(dir);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(readText(file));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static List<JSONObject> toList(JSONArray array) {
        List<JSONObject> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) out.add(item);
        }
        return out;
    }

    private static JSONArray toArray(List<JSONObject> list) {
        JSONArray array = new JSONArray();
        for (JSONObject item : list) array.put(item);
        return array;
    }

    /**
     * 把 ext / proxy 等字段永久写回本地 json 配置文件。
     * 覆盖脚本导入生成的 脚本扫描.json，也覆盖用户手选的本地 json 接口；
     * 在线地址（非 file:）直接返回 false，由调用方改存本地缓存。
     */
    public static boolean updateSiteField(String configUrl, String siteKey, String field, String value) {
        try {
            File file = configFile(configUrl);
            if (file == null || !file.isFile()) return false;
            JSONObject store = new JSONObject(readText(file));
            for (String name : new String[]{"sites", "sites_bac"}) {
                JSONArray array = store.optJSONArray(name);
                if (array == null) continue;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null && siteKey.equals(item.optString("key"))) item.put(field, value);
                }
            }
            writeText(file, store.toString(2));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** configUrl 形如 file:/相对 Path.rootPath() 的路径。 */
    private static File configFile(String configUrl) {
        if (TextUtils.isEmpty(configUrl) || !configUrl.startsWith("file:")) return null;
        String rel = configUrl.substring("file:".length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        return new File(Path.rootPath(), rel);
    }

    /** 本地 json 接口（含脚本导入生成的 脚本扫描.json）以文件本身为权威。 */
    public static boolean isLocalConfig(String configUrl) {
        try {
            File file = configFile(configUrl);
            return file != null && file.isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void save(String dir, List<JSONObject> sites, List<JSONObject> bac) {
        dedupNames(sites, bac);
        JSONObject store = readStore(dir);
        try {
            store.put("sites", toArray(sites));
            store.put("sites_bac", toArray(bac));
            if (!store.has("lives")) {
                JSONArray lives = new JSONArray();
                lives.put(new JSONObject());
                store.put("lives", lives);
            }
            writeText(storeFile(dir), store.toString(2));
        } catch (Exception ignored) {
        }
    }

    /** 扫描目录 + 读 json，按 5 条规则合并；有变更时回写。 */
    public static Pair<List<JSONObject>, List<JSONObject>> load(String dir) {
        File root = new File(dir);
        List<JSONObject> sites = new ArrayList<>();
        List<JSONObject> bac = new ArrayList<>();
        if (!root.isDirectory()) return new Pair<>(sites, bac);

        JSONObject store = readStore(dir);
        sites = toList(store.optJSONArray("sites"));
        bac = toList(store.optJSONArray("sites_bac"));

        List<File> found = scripts(dir);
        Set<String> foundPaths = new HashSet<>();
        for (File file : found) foundPaths.add(file.getAbsolutePath());

        // key 集合必须在清除之前算，否则刚被清掉的条目会被当成新文件加回来
        Set<String> liveKeys = new HashSet<>();
        for (JSONObject item : sites) liveKeys.add(item.optString("key"));
        for (JSONObject item : bac) liveKeys.add(item.optString("key"));

        // 规则 4：两边都清掉已不存在的本地文件条目（远程条目永不自动删除）
        int before = sites.size() + bac.size();
        purgeGone(sites, foundPaths);
        purgeGone(bac, foundPaths);
        boolean purged = before != sites.size() + bac.size();

        // 旧版生成的 127.0.0.1/file 地址不被解析，加载时自动迁移并回写 json
        boolean migrated = false;
        for (JSONObject item : sites) migrated |= migrateHome(item);
        for (JSONObject item : bac) migrated |= migrateHome(item);

        // 规则 3：新增脚本按 py -> js -> html -> url 插入对应分组尾部；
        // 已有条目的相对次序一律不动（用户改过 / 排过的 json 保持现状）。
        boolean changed = false;
        for (File file : found) {
            String key = keyOf(root, file);
            if (liveKeys.contains(key)) continue;
            JSONObject item = newItem(root, file);
            sites.add(insertPos(sites, item), item);
            liveKeys.add(key);
            changed = true;
        }
        boolean first = !storeFile(dir).isFile();
        if (changed || purged || first || migrated) save(dir, sites, bac);
        return new Pair<>(sites, bac);
    }

    private static void purgeGone(List<JSONObject> list, Set<String> foundPaths) {
        list.removeIf(item -> {
            if (isRemoteItem(item)) return false;
            String api = item.optString("api");
            if (api.startsWith("file://")) {
                return !foundPaths.contains(api.substring("file://".length()));
            }
            String home = item.optString("homePage");
            if (home.startsWith("file://")) {
                return !foundPaths.contains(home.substring("file://".length()));
            }
            int idx = home.indexOf("/file/");
            if (idx < 0) return false;
            String rel = home.substring(idx + 6);
            return !foundPaths.contains(Path.rootPath() + "/" + rel);
        });
    }

    /** 交给解析器用的 json：只输出 sites，sites_bac 是管理用的，app 不识别 */
    public static String buildJson(String dir) {
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        JSONObject root = new JSONObject();
        try {
            root.put("warningText", "本地脚本目录：" + dir);
            root.put("sites", parseSites(pair.first));
            JSONArray lives = new JSONArray();
            lives.put(new JSONObject());
            root.put("lives", lives);
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    /**
     * 解析用副本：磁盘上的 json 一个字节不改（key / name / 次序 / sites_bac 都保持现状），
     * 只保证每条都能被 VodConfig 解析、并且一定留在站点切换列表里。
     *   - key 为空 / 重复 → 补唯一副本 key：Site.equals 按 key 去重，空 key 会让多条
     *     解析失败的站点被 .distinct() 吞成一条，表现为「工作区有、站点列表没有」。
     *   - name 为空 → 用 key 兜底：否则列表项空白、搜索也匹配不到。
     *   - hide 强制 0：旧数据里残留 hide=1 时 SiteBlockSetting.filter 会直接丢掉该站点。
     *   - ext / proxy / api / homePage 等统一成字符串：非 primitive 会让 ExtAdapter /
     *     Gson 解析抛异常，objectFrom 的 catch 返回空 Site，同样被去重吞掉。
     * 输出条数记入 proxy 日志，便于实机对照「工作区条数 vs 站点列表条数」。
     */
    private static JSONArray parseSites(List<JSONObject> sites) {
        JSONArray out = new JSONArray();
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (JSONObject item : sites) {
            try {
                JSONObject copy = new JSONObject(item.toString());
                String key = copy.optString("key").trim();
                if (key.isEmpty()) key = "script_" + index;
                if (!keys.add(key)) key = key + "#" + index;
                copy.put("key", key);
                if (copy.optString("name").trim().isEmpty()) copy.put("name", key);
                copy.put("hide", 0);
                String[] fields = {"ext", "proxy", "api", "homePage", "jar", "click", "playUrl", "chromeMode"};
                for (String field : fields) {
                    Object value = copy.opt(field);
                    if (value != null && !(value instanceof String)) copy.put(field, value.toString());
                }
                out.put(copy);
                index++;
            } catch (Exception ignored) {
            }
        }
        SpiderDebug.log("script", "buildJson sites in=%s out=%s", sites.size(), out.length());
        return out;
    }

    /**
     * 刷新 = 重新扫描文件夹内所有脚本文件，对比「脚本扫描.json」的 sites / sites_bac：
     * 新增文件追加到 sites；已删除的文件在两个参数下都移除；远程 URL 条目保留。
     * 结果无条件回写 json，保证 json 里的脚本数据与文件夹一一对应。
     * 所有脚本文件夹接口的刷新按钮都走这里。
     */
    public static void refresh(String dir) {
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        save(dir, pair.first, pair.second);
    }

    /* ---------------- 分类 ---------------- */

    /** 生成 / 新增条目时的类型次序：py(0) -> js(1) -> html(2) -> url(3)。 */
    private static int insertPos(List<JSONObject> sites, JSONObject item) {
        int rank = typeRank(item);
        for (int i = 0; i < sites.size(); i++) if (typeRank(sites.get(i)) > rank) return i;
        return sites.size();
    }

    private static int typeRank(JSONObject item) {
        switch (kindOf(item)) {
            case "py": return 0;
            case "js": return 1;
            case "html": return 2;
            default: return 3;
        }
    }

    public static String kindOf(JSONObject item) {
        String key = item.optString("key");
        if (key.endsWith("[url]")) return "url";
        if (key.endsWith("[py]")) return "py";
        if (key.endsWith("[js]")) return "js";
        if (key.endsWith("[html]")) return "html";
        String api = item.optString("api");
        if (api.endsWith(".py")) return "py";
        if (api.endsWith(".js")) return "js";
        if (!item.optString("homePage").isEmpty()) return "html";
        return "url";
    }

    public static boolean isEditable(JSONObject item) {
        return "url".equals(kindOf(item));
    }

    /* ---------------- 两栏交换 / 排序 ---------------- */

    /** 两侧被选中的条目同时移到对面尾部，保持各自相对顺序；脚本文件本身不动 */
    public static boolean swap(String dir, Set<String> fromSites, Set<String> fromBac) {
        if (fromSites.isEmpty() && fromBac.isEmpty()) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        List<JSONObject> sites = pair.first;
        List<JSONObject> bac = pair.second;
        List<JSONObject> a = new ArrayList<>();
        List<JSONObject> b = new ArrayList<>();
        for (JSONObject item : sites) if (fromSites.contains(item.optString("key"))) a.add(item);
        for (JSONObject item : bac) if (fromBac.contains(item.optString("key"))) b.add(item);
        if (a.isEmpty() && b.isEmpty()) return false;
        sites.removeAll(a);
        bac.removeAll(b);
        for (JSONObject item : a) bac.add(item);
        for (JSONObject item : b) sites.add(item);
        save(dir, sites, bac);
        return true;
    }

    /** 分类筛选下只在可见序列内与相邻可见项交换，避免「点了没反应」 */
    public static boolean reorder(String dir, Set<String> keys, boolean up, boolean inBac, Set<String> visibleKeys) {
        if (keys.isEmpty()) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        List<JSONObject> list = inBac ? pair.second : pair.first;
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) if (visibleKeys.contains(list.get(i).optString("key"))) pos.add(i);
        if (pos.isEmpty()) return false;
        List<JSONObject> vis = new ArrayList<>();
        for (int i : pos) vis.add(list.get(i));
        boolean changed = false;
        if (up) {
            for (int i = 1; i < vis.size(); i++) {
                if (keys.contains(vis.get(i).optString("key")) && !keys.contains(vis.get(i - 1).optString("key"))) {
                    JSONObject t = vis.get(i);
                    vis.set(i, vis.get(i - 1));
                    vis.set(i - 1, t);
                    changed = true;
                }
            }
        } else {
            for (int i = vis.size() - 2; i >= 0; i--) {
                if (keys.contains(vis.get(i).optString("key")) && !keys.contains(vis.get(i + 1).optString("key"))) {
                    JSONObject t = vis.get(i);
                    vis.set(i, vis.get(i + 1));
                    vis.set(i + 1, t);
                    changed = true;
                }
            }
        }
        if (!changed) return false;
        for (int i = 0; i < pos.size(); i++) list.set(pos.get(i), vis.get(i));
        save(dir, pair.first, pair.second);
        return true;
    }

    /* ---------------- 远程 html URL ---------------- */

    private static String stripProxy(String url) {
        String out = url == null ? "" : url.trim();
        String suffix = "#" + PROXY_KEY;
        while (out.endsWith(suffix)) out = out.substring(0, out.length() - suffix.length());
        return out;
    }

    private static String withLabel(String name) {
        String out = name == null ? "" : name.trim();
        return out.endsWith("[url]") ? out : out + "[url]";
    }

    /** 新增：勾选代理 → url 尾部加 #代理 + "proxy":"proxy" */
    public static boolean addUrl(String dir, String name, String url, boolean proxy) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(name)) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        String clean = stripProxy(url);
        if (clean.isEmpty()) return false;
        if (duplicate(pair.first, pair.second, name, clean, "")) return false;
        String full = proxy ? clean + "#" + PROXY_KEY : clean;
        JSONObject item = new JSONObject();
        try {
            item.put("key", "url_" + Integer.toHexString(full.hashCode()) + "[url]");
            item.put("name", withLabel(TextUtils.isEmpty(name) ? clean : name));
            item.put("type", 3);
            item.put("api", HTML_API);
            item.put("homePage", full);
            item.put("ext", "");
            item.put("proxy", proxy ? PROXY_FLAG : "");
            item.put("searchable", 1);
            item.put("changeable", 1);
            item.put("quickSearch", 1);
        } catch (Exception ignored) {
        }
        pair.first.add(insertPos(pair.first, item), item);
        save(dir, pair.first, pair.second);
        return true;
    }

    /** 改远程 html URL：名称、url、代理 */
    public static boolean updateUrl(String dir, String key, String name, String url, boolean proxy) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(name)) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        JSONObject target = null;
        for (JSONObject item : pair.first) if (key.equals(item.optString("key"))) target = item;
        if (target == null) for (JSONObject item : pair.second) if (key.equals(item.optString("key"))) target = item;
        if (target == null || !isEditable(target)) return false;
        String clean = stripProxy(url);
        if (clean.isEmpty()) return false;
        if (duplicate(pair.first, pair.second, name, clean, key)) return false;
        try {
            target.put("name", withLabel(name));
            target.put("homePage", proxy ? clean + "#" + PROXY_KEY : clean);
            target.put("proxy", proxy ? PROXY_FLAG : "");
            target.put("api", HTML_API);
        } catch (Exception ignored) {
        }
        save(dir, pair.first, pair.second);
        return true;
    }

    /**
     * 重置 = 回到「初始扫描的状态」，三件事一起做：
     *  1. sites_bac 里所有条目移回 sites，回收站清空（脚本文件一个字节不动）；
     *  2. 所有条目的 proxy 按扫描规则重算：本地脚本看路径 / 文件名 / 父目录
     *     是否含关键字「代理」，远程 URL 看 url 尾部（如 #代理）是否含「代理」；
     *     撤销手动勾选/取消过的代理状态；
     *  3. 不调整 json 次序：非首次（文件已存在）时可能已被修改或排序，保持现状。
     *     首次生成才按 py/js/html/url 次序（见 scripts()）。
     *
     * 只写 json，不动脚本文件；脚本文件一个字节不动。
     * 已经不存在的本地脚本条目仍由 load() 的 purgeGone 清掉，不会在这里复活。
     */
    public static boolean reset(String dir) {
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        List<JSONObject> all = new ArrayList<>(pair.first);
        all.addAll(pair.second); // 回收站全部移回工作区
        if (all.isEmpty()) return false;
        File root = new File(dir);
        for (JSONObject item : all) {
            String want = scannedProxy(root, item);
            if (!want.equals(item.optString("proxy"))) {
                try {
                    item.put("proxy", want);
                } catch (Exception ignored) {
                }
            }
        }
        // 不重排：all 已按 sites 原顺序 + sites_bac 原顺序，保持用户改过/排过的次序
        save(dir, all, new ArrayList<>());
        return true;
    }

    /** 按初始扫描时的同一套规则判定 proxy，避免手改 key / name 造成误判 */
    private static String scannedProxy(File root, JSONObject item) {
        String api = item.optString("api");
        String home = item.optString("homePage");
        File file = null;
        if (api.startsWith("file://")) {
            file = new File(api.substring("file://".length()));
        } else if (home.startsWith("file://")) {
            file = new File(home.substring("file://".length()));
        } else if (home.contains("127.0.0.1")) {
            // 本地 html 的 homePage 由 fileHomePage() 生成，路径相对 Path.rootPath()
            int idx = home.indexOf("/file/");
            if (idx >= 0) file = new File(Path.rootPath(), home.substring(idx + 6));
        }
        if (file != null && file.getAbsolutePath().startsWith(root.getAbsolutePath()))
            return proxyOf(root, file);
        // 远程 URL：尾部 #代理 或任意处含关键字「代理」→ "proxy":"proxy"
        return (home.contains(PROXY_KEY) || item.optString("key").contains(PROXY_KEY)
                || item.optString("name").contains(PROXY_KEY)) ? PROXY_FLAG : "";
    }

    /** 删除远程 html URL 条目（仅 url 类型），两栏都找；脚本文件不动 */
    public static boolean removeUrl(String dir, String key) {
        if (TextUtils.isEmpty(key)) return false;
        Pair<List<JSONObject>, List<JSONObject>> pair = load(dir);
        boolean changed = pair.first.removeIf(item ->
                key.equals(item.optString("key")) && isEditable(item));
        changed |= pair.second.removeIf(item ->
                key.equals(item.optString("key")) && isEditable(item));
        if (changed) save(dir, pair.first, pair.second);
        return changed;
    }

    public static JSONObject find(List<JSONObject> list, String key) {
        for (JSONObject item : list) if (key.equals(item.optString("key"))) return item;
        return null;
    }

    /* ---------------- 同名 / 同 url ---------------- */

    /** 拆出「基础名 + 类型标签」，并剥掉已有的 |N 序号，保证去重幂等。 */
    private static String[] splitName(String name) {
        String label = "";
        int i = name.lastIndexOf('[');
        if (i > 0 && name.endsWith("]")) {
            label = name.substring(i);
            name = name.substring(0, i);
        }
        int p = name.lastIndexOf('|');
        if (p >= 0) {
            String tail = name.substring(p + 1);
            boolean digits = !tail.isEmpty();
            for (int k = 0; k < tail.length(); k++)
                if (!Character.isDigit(tail.charAt(k))) { digits = false; break; }
            if (digits) name = name.substring(0, p);
        }
        return new String[]{name, label};
    }

    private static String baseName(String name) {
        String[] p = splitName(name);
        return p[0] + p[1];
    }

    /** 同名条目的 name 写成 文件名|1[py]，key 不动；显示层与 json 都调它。 */
    public static void dedupNames(List<JSONObject> sites, List<JSONObject> bac) {
        List<List<JSONObject>> lists = new ArrayList<>();
        lists.add(sites);
        lists.add(bac);
        Map<String, Integer> seen = new HashMap<>();
        for (List<JSONObject> list : lists) {
            for (JSONObject item : list) {
                String base = baseName(item.optString("name"));
                int n = seen.containsKey(base) ? seen.get(base) : 0;
                seen.put(base, n + 1);
                String[] p = splitName(item.optString("name"));
                String want = n == 0 ? p[0] + p[1] : p[0] + "|" + n + p[1];
                if (!want.equals(item.optString("name"))) {
                    try {
                        item.put("name", want);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /** 显示层双重保护：相同 url（去掉 #代理 后）只保留第一个。 */
    public static void dedupUrls(List<JSONObject> sites, List<JSONObject> bac) {
        List<List<JSONObject>> lists = new ArrayList<>();
        lists.add(sites);
        lists.add(bac);
        Set<String> seen = new HashSet<>();
        for (List<JSONObject> list : lists) {
            list.removeIf(item -> {
                if (!isEditable(item)) return false;
                String url = stripProxy(item.optString("homePage"));
                return !url.isEmpty() && !seen.add(url);
            });
        }
    }

    private static boolean sameName(String a, String b) {
        return baseName(a).equals(baseName(b));
    }

    /** 录入/编辑时检查：同名或相同 url 都拒绝，selfKey 为编辑中的条目自身。 */
    private static boolean duplicate(List<JSONObject> sites, List<JSONObject> bac,
                                     String name, String cleanUrl, String selfKey) {
        List<List<JSONObject>> lists = new ArrayList<>();
        lists.add(sites);
        lists.add(bac);
        for (List<JSONObject> list : lists) {
            for (JSONObject item : list) {
                if (!selfKey.isEmpty() && selfKey.equals(item.optString("key"))) continue;
                if (!name.isEmpty() && sameName(item.optString("name"), name)) return true;
                if (!cleanUrl.isEmpty() && cleanUrl.equals(stripProxy(item.optString("homePage")))) return true;
            }
        }
        return false;
    }
}
