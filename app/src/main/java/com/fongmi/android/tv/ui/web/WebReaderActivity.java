package com.fongmi.android.tv.ui.web;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.util.List;

/**
 * TVBox 小说阅读器。
 *
 * <p>与 {@link CatWebActivity} 的关键差别：那个直接渲染远程页面、顶部保留地址栏；
 * 这个只加载本地模板 {@code file:///android_asset/reader/index.html}，正文由 Java
 * 单向注入（{@code showNovel}），不挂任何 JS 桥，远程导航一律阻断。
 *
 * <p>漫画不走这里：漫画章节页本身就是 http 页（本机 spider 服务），直接复用
 * {@link CatWebActivity} 打开。分流逻辑集中在 {@link #tryRoute}，两个
 * {@code VideoActivity.setPlayer} 只调这一行。
 */
public class WebReaderActivity extends AppCompatActivity {

    private static final String TAG = "web-reader";
    private static final String READER_URL = "file:///android_asset/reader/index.html";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_CONTENT = "content";

    private WebView webView;
    private ProgressBar progress;
    private View loading;
    private String title;
    private String content;
    private boolean injected;

    public static Intent novel(Context context, String title, String content) {
        return new Intent(context, WebReaderActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONTENT, content);
    }

    /**
     * 起播前分流。命中则打开对应页面并返回 true（调用方应随即 finish 播放页）；
     * 未命中或打开失败返回 false（调用方原样走视频播放，失败时不丢播放）。
     */
    public static boolean tryRoute(Activity activity, Result result) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || result == null) return false;
        String url = result.getUrl().v();
        if (TextUtils.isEmpty(url)) return false;
        try {
            if (url.startsWith("novel://")) {
                String payload = url.substring("novel://".length());
                String title = "章节正文";
                String content = payload;
                try {
                    JsonElement element = JsonParser.parseString(payload);
                    if (element != null && element.isJsonObject()) {
                        JsonObject obj = element.getAsJsonObject();
                        title = optString(obj, "title", title);
                        content = optString(obj, "content", content);
                    }
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, e);
                }
                activity.startActivity(novel(activity, title, content));
                return true;
            }
            if (isComicPage(url)) {
                activity.startActivity(CatWebActivity.intent(activity, url));
                return true;
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, e);
        }
        return false;
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        try {
            if (obj != null && obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsString();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * 只认漫画章节页：本机回环地址 + 路径第一段是 {@code cm}。
     * fqapp 的视频流也是同机服务，但路径是 {@code /fq/<id>/<画质>}，绝不能分流到 WebView。
     */
    private static boolean isComicPage(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return false;
            List<String> segments = uri.getPathSegments();
            return !segments.isEmpty() && "cm".equals(segments.get(0));
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        content = getIntent().getStringExtra(EXTRA_CONTENT);
        if (content == null) {
            finish();
            return;
        }
        if (title == null) title = "章节正文";
        setContentView(R.layout.activity_web_reader);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        webView = findViewById(R.id.web_view);
        progress = findViewById(R.id.progress);
        loading = findViewById(R.id.loading);

        configure();
        SpiderDebug.log(TAG, "open novel title=%s len=%d", title, content.length());
        webView.loadUrl(READER_URL);
    }

    /**
     * 模板与脚本都在本地 assets 里：允许读文件，不开内容访问；远程导航在
     * {@link #client()} 里阻断，所以不挂 JS 桥。
     */
    private void configure() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setBackgroundColor(0xFF101216);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                if (progress == null) return;
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                SpiderDebug.log(TAG, "console [%s] %s (%s:%d)",
                        msg.messageLevel(), msg.message(), msg.sourceId(), msg.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(client());
    }

    private WebViewClient client() {
        return new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                hideLoading();
                if (injected) return;
                injected = true;
                try {
                    String js = "showNovel(" + JSONObject.quote(title) + "," + JSONObject.quote(content) + ")";
                    view.evaluateJavascript(js, null);
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, e);
                }
            }

            /**
             * 只放行本地模板自身的 file 地址；其它任何跳转（包括 http(s)）都拦掉。
             * 阅读器是单页应用，正常使用不产生站外导航。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String target = request.getUrl() == null ? "" : request.getUrl().toString();
                if (target.startsWith("file:///android_asset/")) return false;
                SpiderDebug.log(TAG, "blockScheme url=%s", target);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                SpiderDebug.log(TAG, "resourceError url=%s code=%d desc=%s main=%b",
                        request.getUrl(), error.getErrorCode(), error.getDescription(), request.isForMainFrame());
                if (!request.isForMainFrame()) return;
                hideLoading();
                finish();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                SpiderDebug.log(TAG, "renderProcessGone crashed=%b", detail.didCrash());
                finish();
                return true;
            }
        };
    }

    private void hideLoading() {
        if (loading == null || loading.getVisibility() != View.VISIBLE) return;
        loading.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                android.view.ViewParent parent = webView.getParent();
                if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }
}
