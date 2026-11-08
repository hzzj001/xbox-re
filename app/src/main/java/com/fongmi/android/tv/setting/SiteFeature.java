package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.LocalScriptSource;
import com.fongmi.chaquo.ProxyEnv;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;



/**
 * 站点级代理 / ext 配置。
 *
 * 参照 AVBox xbox 分支 util/SiteProxy.kt、SiteExt.kt 的判定语义，但按本项目口径
 * 「稍微有些不同」地落到配置本身：
 *   - 代理图标是循环开关：明 = 当前站点配置含代理条件，暗 = 不含；点一下即翻转，
 *     即使原本配置自带代理，也能点暗、再点亮。
 *   - 翻转直接写回配置：本地 json 接口（含 脚本扫描.json）写回文件本身；
 *     在线接口写本地缓存，配置重新解析时回填，绝不改写在线源数据。
 *   - json 写回规则：开启时若现值已是有效代理地址或 "proxy" 占位则保持，
 *     空 / 无参数才写 "proxy":"proxy"；关闭时统一写 ""；
 *     绝不把 app 默认代理地址（2080）写进 json。
 *   - 运行时归一：proxy 字段非空（占位或任意有效地址）一律使用默认代理地址。
 *   - 生效：亮 = 全 app 总闸开（OkProxySelector.setGlobal），所有非本机请求
 *     一律走默认代理地址；暗 = 总闸关。总闸优先于配置自带 / shell 的代理规则。
 *     亮暗仍由上面的老逻辑判定，切站 / 回前台都会按新站点重算。
 */
public final class SiteFeature {

    public static final String DEFAULT_PROXY_URL = "http://127.0.0.1:2080";
    public static final String PROXY_FLAG = "proxy";

    private static final String K_URL = "site_feature_proxy_url";
    private static final String K_PROXY = "site_feature_proxy|";
    private static final String K_EXT = "site_feature_ext|";
    private static final String KEY_WORD = "代理";

    private SiteFeature() {
    }

    /* ---------------- 默认代理地址 ---------------- */

    public static String proxyUrl() {
        String url = Prefers.getString(K_URL, "").trim();
        return TextUtils.isEmpty(url) ? DEFAULT_PROXY_URL : url;
    }

    public static void setProxyUrl(String url) {
        String value = url == null ? "" : url.trim();
        Prefers.put(K_URL, TextUtils.isEmpty(value) ? DEFAULT_PROXY_URL : value);
    }

    /* ---------------- 配置读写 ---------------- */

    private static boolean isLocal() {
        return LocalScriptSource.isLocalConfig(VodConfig.getUrl());
    }

    private static String cacheKey(String field, Site site) {
        return ("proxy".equals(field) ? K_PROXY : K_EXT) + VodConfig.getUrl() + "|" + site.getKey();
    }

    /**
     * 配置重新解析后同步。
     * 本地 json 以文件为权威，清掉旧缓存；在线接口回填本地缓存。
     * 在线接口首次没有缓存时，按 proxy 字段或路径/名称关键字算出初始亮暗并落缓存，
     * 这样「路径含代理」的站点也能被循环开关点暗（写 "" 后显式值优先于关键字）。
     */
    public static void onConfigParsed(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        String proxyKey = cacheKey("proxy", site);
        if (isLocal()) {
            Prefers.getPrefers().edit().remove(proxyKey).remove(cacheKey("ext", site)).apply();
            return;
        }
        boolean has = Prefers.getPrefers().contains(proxyKey);
        String value = has ? Prefers.getString(proxyKey, "")
                : (!TextUtils.isEmpty(site.getProxy()) || matchKeyword(site) ? PROXY_FLAG : "");
        if (!has) Prefers.put(proxyKey, value);
        site.setProxy(value);
        site.setExt(Prefers.getString(cacheKey("ext", site), site.getExt()));
    }

    /** 与 LocalScriptSource.scannedProxy 同一套：远程接口路径 / 名称含「代理」即视为启用。 */
    private static boolean matchKeyword(Site site) {
        return site.getName().contains(KEY_WORD) || site.getApi().contains(KEY_WORD)
                || site.getHomePage().contains(KEY_WORD) || site.getKey().contains(KEY_WORD);
    }

    /**
     * 写回一个字段：本地 json 接口写文件；在线接口只写缓存。
     * 两种接口都同步内存中的 Site，保证图标与本次访问立即生效。
     */
    private static void write(String field, Site site, String value) {
        if (isLocal()) LocalScriptSource.updateSiteField(VodConfig.getUrl(), site.getKey(), field, value);
        Prefers.put(cacheKey(field, site), value);
        if ("proxy".equals(field)) site.setProxy(value);
        else site.setExt(value);
    }

    /* ---------------- 代理：循环开关 ---------------- */

    /** 明暗直接反映当前站点配置：proxy 非空即明，空即暗。 */
    public static boolean isOn(Site site) {
        return site != null && !TextUtils.isEmpty(site.getProxy());
    }

    /** 运行时归一：非空（占位或任意有效代理地址）一律取默认代理地址；app 后台替换，不写回 json。 */
    public static String resolve(Site site) {
        return isOn(site) ? proxyUrl() : "";
    }

    /**
     * 循环开关：明 → 暗、暗 → 明。
     * 开启：现值为空 / 无参数 → 写 "proxy"；现值已是有效地址或 "proxy" → 保持不动。
     * 关闭：无论 "proxy" 还是有效地址，统一写 ""。
     * json 层面绝不写入 2080 地址。
     */
    public static boolean toggle(Site site) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return false;
        boolean on = !isOn(site);
        String current = site.getProxy();
        String next = on ? (TextUtils.isEmpty(current) ? PROXY_FLAG : current) : "";
        write("proxy", site, next);
        apply();
        return on;
    }

    /* ---------------- ext ---------------- */

    /** 弹窗预填值：本地 json 接口就是文件里的值，在线接口是回填后的缓存值。 */
    public static String ext(Site site) {
        return site == null ? "" : site.getExt();
    }

    /**
     * 改过才写：有 ext 参数改值，无参数 / 空值则新增参数与值（JSONObject.put 自动新增）。
     * 本地 json 写回文件，在线接口写缓存，语义一致。
     */
    public static void setExt(Site site, String value) {
        if (site == null || TextUtils.isEmpty(site.getKey())) return;
        String text = value == null ? "" : value.trim();
        if (text.equals(site.getExt())) return;
        write("ext", site, text);
    }

    /* ---------------- 生效：全 app 总闸 ---------------- */

    /**
     * 总闸：图标亮（当前站点配置含代理条件）→ 全 app 非本机请求一律走代理。
     * 亮 / 暗由老的 per-site 逻辑决定（onConfigParsed / toggle / json 写回全保留），
     * 这里只把结果翻译成 selector 的全局强制规则，不再另挂会被排序挤到最后的通配规则。
     * 切站由 VodConfig.setHome 重算，回前台由 VodFragment.onResume 重算，
     * 默认代理地址保存后由 SiteFeatureDialog 重算 —— 每次都覆盖写，必然「重新算」。
     * global 分支在 select() 最前面返回，配置自带规则 / shell 规则一律不插队。
     * 本机 127.0.0.1 仍直连（app 自己的 9980 服务），但服务内部走 OkHttp.client()
     * 会命中总闸，所以本地 py/js 脚本链路仍生效。
     * 地址非法由 setGlobal 记入 proxy 日志，不再静默吞异常后仍显示亮。
     */
    /** 当前总闸地址：图标亮取默认代理地址，暗为空串。py 侧与 selector 共用同一口径。 */
    public static String globalUrl() {
        return isOn(VodConfig.get().getHome()) ? proxyUrl() : "";
    }

    public static void apply() {
        String url = globalUrl();
        OkHttp.selector().setGlobal(url);
        OkHttp.closeIdleConnections();
        // py 是进程内 CPython，requests 不读 JVM ProxySelector；宿主直接改写它的 os.environ。
        // Python 未启动时跳过，PyLoader 首次加载脚本时会再应用一次。
        ProxyEnv.apply(url);
    }

}
