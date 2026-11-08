package com.github.catvod.net;

import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.DebugEventLimiter;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class OkProxySelector extends ProxySelector {

    private static final long DEBUG_LOG_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private final DebugEventLimiter debugLogLimiter;
    private boolean authSet;
    /** 全 app 总闸：亮时所有非本机请求强制走它，优先于下方所有规则。clear() 不清空。 */
    private volatile List<java.net.Proxy> global = List.of();

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        system = ProxySelector.getDefault();
        debugLogLimiter = new DebugEventLimiter(64);
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    /**
     * 升格为整个进程的默认 ProxySelector：jar / WebView 拦截 / js 桥里所有
     * 未显式指定 Proxy 的 HttpURLConnection（openConnection(null)）也会命中 global。
     * system 字段在构造时已保存系统原默认，fallback() 走它，不会递归回自己。
     */
    public synchronized void installAsDefault() {
        if (ProxySelector.getDefault() != this) ProxySelector.setDefault(this);
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        Authenticator.setDefault(new ProxyAuthenticator(this));
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
        SpiderDebug.log("proxy", "selector add rules=%s total=%s", items.size(), proxy.size());
    }

    public synchronized void remove(String name) {
        int before = proxy.size();
        proxy.removeIf(item -> item.getName().equals(name));
        int removed = before - proxy.size();
        if (removed > 0) SpiderDebug.log("proxy", "selector remove name=%s removed=%s total=%s", name, removed, proxy.size());
    }


    /**
     * 设置 / 关闭全 app 总闸。图标亮时由 SiteFeature.apply() 传入默认代理地址，
     * 暗时传空串。地址非法会记入 proxy 日志并置为关闭，不抛异常、不静默谎报成功。
     */
    public synchronized void setGlobal(String url) {
        global = List.of();
        if (url == null || url.trim().isEmpty()) {
            SpiderDebug.log("proxy", "global off");
            return;
        }
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme();
            String host = u.getHost();
            int port = u.getPort();
            if (scheme == null || host == null || port <= 0) {
                SpiderDebug.log("proxy", "global invalid url rejected");
                return;
            }
            java.net.Proxy.Type type;
            if (scheme.startsWith("http")) type = java.net.Proxy.Type.HTTP;
            else if (scheme.startsWith("socks")) type = java.net.Proxy.Type.SOCKS;
            else {
                SpiderDebug.log("proxy", "global unsupported scheme rejected");
                return;
            }
            global = List.of(new java.net.Proxy(type, InetSocketAddress.createUnresolved(host, port)));
            SpiderDebug.log("proxy", "global on scheme=%s host=%s port=%s", scheme, OkHttpLogPolicy.redactHost(host), port);
        } catch (Throwable e) {
            SpiderDebug.log("proxy", "global parse failed error=%s", e.getMessage());
        }
    }

    public synchronized void clear() {
        proxy.clear();
        // 认证器常驻：selector 已是进程默认，clear() 不再清掉 ProxyAuthenticator
        // global 不清：页面销毁 / 重建时总闸状态保持，由 App.onCreate 与 onResume 重算
        SpiderDebug.log("proxy", "selector clear");
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : List.of(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        String host = uri.getHost();
        // 总闸最优先：图标亮时所有非本机请求一律走代理，配置自带 / shell 规则不插队
        List<java.net.Proxy> forced = global;
        if (!forced.isEmpty()) {
            if (host == null) return fallback(uri, "no-host");
            if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) return fallback(uri, "local-target");
            logSelection(uri, "global", host, "*", "global", forced.size());
            return forced;
        }
        if (proxy.isEmpty()) return fallback(uri, "no-rule");
        if (host == null) return fallback(uri, "no-host");
        if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) return fallback(uri, "local-target");
        for (Proxy item : proxy) {
            for (String rule : item.getHosts()) {
                if (!matches(host, rule)) continue;
                List<java.net.Proxy> selected = item.getProxies().isEmpty() ? fallback(uri, "empty-proxy") : item.getProxies();
                logSelection(uri, "hit", host, rule, item.getName(), selected.size());
                return selected;
            }
        }
        return fallback(uri, "no-match");
    }

    private List<java.net.Proxy> fallback(URI uri, String reason) {
        List<java.net.Proxy> selected = fallback(uri);
        logFallback(uri, reason, selected.size());
        return selected;
    }

    private boolean matches(String host, String rule) {
        return "*".equals(rule) || Util.containOrMatch(host, rule);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        logConnectFailed(uri, socketAddress, e);
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }

    private void logSelection(URI uri, String reason, String host, String rule, String name, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("select", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select hit uri=%s host=%s ruleHash=%s nameHash=%s proxyCount=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), OkHttpLogPolicy.redactHost(host), OkHttpLogPolicy.redactHost(rule), OkHttpLogPolicy.redactHost(name), count, decision.suppressedCount());
    }

    private void logFallback(URI uri, String reason, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("fallback", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select fallback reason=%s uri=%s proxyCount=%s suppressed=%s", reason, OkHttpLogPolicy.redactUri(uri), count, decision.suppressedCount());
    }

    private void logConnectFailed(URI uri, SocketAddress address, IOException error) {
        if (!SpiderDebug.isEnabled()) return;
        String addressType = address == null ? "none" : address.getClass().getSimpleName();
        DebugEventLimiter.Decision decision = acquire("failure", addressType, uri, error);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "connectFailed uri=%s addressType=%s error=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), addressType, OkHttpLogPolicy.errorChain(error), decision.suppressedCount());
    }

    private DebugEventLimiter.Decision acquire(String event, String detail, URI uri, Throwable error) {
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        String errorType = error == null ? "" : error.getClass().getName();
        String key = event + '|' + detail + '|' + scheme + '|' + host + '|' + errorType;
        long nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        return debugLogLimiter.acquire(key, nowMs, DEBUG_LOG_INTERVAL_MS);
    }
}
