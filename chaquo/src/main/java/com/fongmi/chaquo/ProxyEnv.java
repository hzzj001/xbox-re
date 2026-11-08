package com.fongmi.chaquo;

import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

/**
 * 宿主直接改写进程内 CPython 的 os.environ，让 py 脚本的 requests / urllib
 * 立即按代理总闸走，不必重载脚本、不必往 ext 里塞字段、不绑 jar 类名。
 *
 * 与 Loader 同模块，Python.start() 由 Loader 构造时负责；这里只在已启动时生效，
 * 避免 Application 阶段为了设环境变量提前拉起 Python。
 */
public final class ProxyEnv {

    private static final String[] KEYS = {
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
            "http_proxy", "https_proxy", "all_proxy"
    };

    private ProxyEnv() {
    }

    public static void apply(String url) {
        if (!Python.isStarted()) return;
        try {
            PyObject environ = Python.getInstance().getModule("os").get("environ");
            if (TextUtils.isEmpty(url)) {
                for (String key : KEYS) {
                    try {
                        environ.callAttr("__delitem__", key);
                    } catch (Throwable ignored) {
                        // 键本来就不存在
                    }
                }
            } else {
                String value = url.trim();
                for (String key : KEYS) environ.callAttr("__setitem__", key, value);
            }
        } catch (Throwable ignored) {
            // Python 未就绪或 ABI 差异时静默跳过，不影响宿主其它链路
        }
    }
}
