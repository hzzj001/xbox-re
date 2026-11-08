package com.fongmi.android.tv.setting;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.DisplayMetrics;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.bean.ShortDramaConfig;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.OciMirror;
import com.fongmi.android.tv.update.UpdateSource;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class Setting {

    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();

    public static final int LANGUAGE_FOLLOW_SYSTEM = 0;

    public static final int CSP_WARMUP_DISABLED = 0;
    public static final int CSP_WARMUP_DEFAULT = 1;
    public static final int CSP_WARMUP_CUSTOM = 2;

    public static final int UI_SCALE_FOLLOW_SYSTEM = 0;
    public static final int UI_SCALE_STANDARD = 1;
    public static final int UI_SCALE_COMPACT = 2;
    public static final int UI_SCALE_SMALLER = 3;
    public static final int UI_SCALE_MILD_COMPACT = 4;
    public static final int UI_SCALE_MORE_COMPACT = 5;
    private static final int[] UI_SCALE_OPTIONS = {UI_SCALE_FOLLOW_SYSTEM, UI_SCALE_STANDARD, UI_SCALE_MILD_COMPACT, UI_SCALE_COMPACT, UI_SCALE_MORE_COMPACT, UI_SCALE_SMALLER};

    public static final int WALL_CINEMA = 5;
    public static final int WALL_CINEMA_WARM = 6;
    public static final int WALL_CINEMA_MOSS = 7;
    public static final int WALL_CINEMA_BLUE = 8;
    public static final int WALL_CINEMA_CLAY = 9;
    public static final int WALL_AURORA_GLASS = 10;
    public static final int WALL_SUNSET_PRISM = 11;
    public static final int WALL_MINT_GLACIER = 12;
    public static final int WALL_LIQUID_CHROME = 13;
    public static final int WALL_NEON_BERRY = 14;
    public static final int WALL_CHAMPAGNE_MIST = 15;
    public static final int WALL_GLASS_GRADIENT = 16;
    public static final int WALL_DEEP_SPACE_GLASS = 17;
    public static final int WALL_POLAR_LIGHT_GLASS = 18;
    public static final int WALL_NEON_CYBER = 19;
    public static final int WALL_WARM_MOON_GLASS = 20;
    public static final int WALL_CRYSTAL_SKY = 21;
    public static final int WALL_DREAM_PURPLE = 22;
    public static final int WALL_SKY_MINT = 23;
    public static final int WALL_FOREST_MIST = 24;
    public static final int WALL_DAYLIGHT_MINIMAL = 25;
    public static final int WALL_DEEP_SEA = 26;
    public static final int WALL_VIOLET_SMOKE = 27;
    public static final int WALL_ROSE_VEIL = 28;
    public static final int WALL_EMERALD_AURORA = 29;
    public static final int WALL_BLUE_SILK = 30;
    public static final int WALL_PEACH_DAWN = 31;
    public static final int WALL_GRAPHITE_SMOKE = 32;
    public static final int WALL_PASTEL_PRISM = 33;
    public static final int WALL_MIDNIGHT_MOON = 34;
    public static final int WALL_CYAN_CRYSTAL = 35;
    public static final int WALL_LAVENDER_CRYSTAL = 36;
    public static final int WALL_GREEN = 1;

    private static final int[] DEFAULT_WALLS = {
            WALL_DAYLIGHT_MINIMAL, WALL_LAVENDER_CRYSTAL, WALL_PASTEL_PRISM, WALL_ROSE_VEIL, WALL_VIOLET_SMOKE,
            WALL_NEON_BERRY, WALL_MIDNIGHT_MOON, WALL_NEON_CYBER, WALL_DEEP_SPACE_GLASS, WALL_GRAPHITE_SMOKE,
            WALL_DREAM_PURPLE, WALL_SKY_MINT, WALL_POLAR_LIGHT_GLASS, WALL_GLASS_GRADIENT, WALL_CRYSTAL_SKY,
            WALL_BLUE_SILK, WALL_CYAN_CRYSTAL, WALL_MINT_GLACIER, WALL_AURORA_GLASS, WALL_DEEP_SEA,
            WALL_LIQUID_CHROME, WALL_FOREST_MIST, WALL_EMERALD_AURORA, WALL_WARM_MOON_GLASS, WALL_PEACH_DAWN,
            WALL_CHAMPAGNE_MIST, WALL_SUNSET_PRISM
    };

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static String getHotTv() {
        return Prefers.getString("hot_tv");
    }

    public static void putHotTv(String hot) {
        Prefers.put("hot_tv", hot);
    }

    public static String getHotMovie() {
        return Prefers.getString("hot_movie");
    }

    public static void putHotMovie(String hot) {
        Prefers.put("hot_movie", hot);
    }

    public static String getHotVariety() {
        return Prefers.getString("hot_variety");
    }

    public static void putHotVariety(String hot) {
        Prefers.put("hot_variety", hot);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static int getWall() {
        int wall = Prefers.getInt("wall", WALL_DAYLIGHT_MINIMAL);
        return wall == WALL_GREEN || isLegacyColorWall(wall) ? WALL_DAYLIGHT_MINIMAL : wall;
    }

    public static void putWall(int wall) {
        Prefers.put("wall", wall);
    }

    public static int getWallType() {
        return Prefers.getInt("wall_type", 0);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", type);
    }

    public static int nextDefaultWall() {
        int wall = getWall();
        for (int i = 0; i < DEFAULT_WALLS.length; i++) {
            if (DEFAULT_WALLS[i] == wall) return DEFAULT_WALLS[(i + 1) % DEFAULT_WALLS.length];
        }
        return WALL_DAYLIGHT_MINIMAL;
    }

    public static int[] getDefaultWalls() {
        return DEFAULT_WALLS.clone();
    }

    public static int getDefaultWallIndex(int wall) {
        for (int i = 0; i < DEFAULT_WALLS.length; i++) {
            if (DEFAULT_WALLS[i] == wall) return i;
        }
        return -1;
    }

    public static boolean isBuiltInWall(int wall) {
        return isBuiltInDesignWall(wall);
    }

    public static boolean isBuiltInColorWall(int wall) {
        return false;
    }

    private static boolean isLegacyColorWall(int wall) {
        return wall == WALL_CINEMA || wall == WALL_CINEMA_WARM || wall == WALL_CINEMA_MOSS || wall == WALL_CINEMA_BLUE || wall == WALL_CINEMA_CLAY;
    }

    public static boolean isBuiltInDesignWall(int wall) {
        return getDefaultWallIndex(wall) != -1;
    }

    public static int getBuiltInWallColor(int wall) {
        if (wall == WALL_AURORA_GLASS) return 0xFF2B8ECB;
        if (wall == WALL_SUNSET_PRISM) return 0xFFB65B88;
        if (wall == WALL_MINT_GLACIER) return 0xFF55BCA8;
        if (wall == WALL_LIQUID_CHROME) return 0xFF53657F;
        if (wall == WALL_NEON_BERRY) return 0xFF7B42CF;
        if (wall == WALL_CHAMPAGNE_MIST) return 0xFFB47692;
        if (wall == WALL_GLASS_GRADIENT) return 0xFF5E91B3;
        if (wall == WALL_DEEP_SPACE_GLASS) return 0xFF2E2B74;
        if (wall == WALL_POLAR_LIGHT_GLASS) return 0xFF6FA6B8;
        if (wall == WALL_NEON_CYBER) return 0xFF4B2BD8;
        if (wall == WALL_WARM_MOON_GLASS) return 0xFF9E7568;
        if (wall == WALL_CRYSTAL_SKY) return 0xFF7890C5;
        if (wall == WALL_DREAM_PURPLE) return 0xFF7560CA;
        if (wall == WALL_SKY_MINT) return 0xFF6DA6B1;
        if (wall == WALL_FOREST_MIST) return 0xFF4E8750;
        // 雾蓝极简重调色：原 0xFF9DB6CC 偏亮偏蓝、白字发虚且晃眼；
        // 与新的柔和雾蓝渐变壁纸主色对齐（低亮度、低饱和、带灰）。
        if (wall == WALL_DAYLIGHT_MINIMAL) return 0xFF93A9BA;
        if (wall == WALL_DEEP_SEA) return 0xFF2F7290;
        if (wall == WALL_VIOLET_SMOKE) return 0xFF7C4BE2;
        if (wall == WALL_ROSE_VEIL) return 0xFFB27FAE;
        if (wall == WALL_EMERALD_AURORA) return 0xFF27B07D;
        if (wall == WALL_BLUE_SILK) return 0xFF5E9BB3;
        if (wall == WALL_PEACH_DAWN) return 0xFFC27863;
        if (wall == WALL_GRAPHITE_SMOKE) return 0xFF4B5360;
        if (wall == WALL_PASTEL_PRISM) return 0xFF8A84C8;
        if (wall == WALL_MIDNIGHT_MOON) return 0xFF4935B4;
        if (wall == WALL_CYAN_CRYSTAL) return 0xFF168BA6;
        if (wall == WALL_LAVENDER_CRYSTAL) return 0xFF8875D0;
        return 0xFF2B8ECB;
    }

    public static String getBuiltInWallName(int wall) {
        if (wall == WALL_AURORA_GLASS) return "蓝紫流光";
        if (wall == WALL_SUNSET_PRISM) return "珊瑚暮色";
        if (wall == WALL_MINT_GLACIER) return "薄荷星云";
        if (wall == WALL_LIQUID_CHROME) return "银色潮汐";
        if (wall == WALL_NEON_BERRY) return "莓果极光";
        if (wall == WALL_CHAMPAGNE_MIST) return "香槟晨雾";
        if (wall == WALL_GLASS_GRADIENT) return "玻璃渐变风";
        if (wall == WALL_DEEP_SPACE_GLASS) return "深空玻璃风";
        if (wall == WALL_POLAR_LIGHT_GLASS) return "极光轻玻璃风";
        if (wall == WALL_NEON_CYBER) return "暗夜霓虹";
        if (wall == WALL_WARM_MOON_GLASS) return "暖月玻璃风";
        if (wall == WALL_CRYSTAL_SKY) return "冰晶幻彩风";
        if (wall == WALL_DREAM_PURPLE) return "梦幻紫霞";
        if (wall == WALL_SKY_MINT) return "雾青薄荷";
        if (wall == WALL_FOREST_MIST) return "森林雾绿";
        if (wall == WALL_DAYLIGHT_MINIMAL) return "雾蓝极简";
        if (wall == WALL_DEEP_SEA) return "深海月影";
        if (wall == WALL_VIOLET_SMOKE) return "紫雾星旋";
        if (wall == WALL_ROSE_VEIL) return "玫瑰薄雾";
        if (wall == WALL_EMERALD_AURORA) return "翡翠极光";
        if (wall == WALL_BLUE_SILK) return "蓝绸流影";
        if (wall == WALL_PEACH_DAWN) return "暖桃晨光";
        if (wall == WALL_GRAPHITE_SMOKE) return "石墨烟岚";
        if (wall == WALL_PASTEL_PRISM) return "彩虹幻璃";
        if (wall == WALL_MIDNIGHT_MOON) return "午夜月影";
        if (wall == WALL_CYAN_CRYSTAL) return "水晶青蓝";
        if (wall == WALL_LAVENDER_CRYSTAL) return "薰衣水晶";
        return "梦幻紫霞";
    }

    public static String getWallDesc(String desc) {
        return getWallType() == 0 && isBuiltInWall(getWall()) ? getBuiltInWallName(getWall()) : desc;
    }

    public static int getReset() {
        return Prefers.getInt("reset", 0);
    }

    public static void putReset(int reset) {
        Prefers.put("reset", reset);
    }

    public static int getSiteMode() {
        return Prefers.getInt("site_mode");
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", mode);
    }

    public static int getSiteColumn() {
        return Prefers.getInt("site_column", 2);
    }

    public static void putSiteColumn(int column) {
        Prefers.put("site_column", column);
    }

    public static int getSyncMode() {
        return Prefers.getInt("sync_mode");
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", mode);
    }

    public static String getSyncPaths() {
        return Prefers.getString("sync_paths", "TV\nTVBox\nTVData");
    }

    public static void putSyncPaths(String paths) {
        Prefers.put("sync_paths", paths);
    }

    public static String getLoginStatePaths() {
        return Prefers.getString("login_state_paths");
    }

    public static void putLoginStatePaths(String paths) {
        Prefers.put("login_state_paths", paths);
    }

    public static String getLoginStatePendingPaths() {
        return Prefers.getString("login_state_pending_paths");
    }

    public static void putLoginStatePendingPaths(String paths) {
        Prefers.put("login_state_pending_paths", paths);
    }

    public static String getLoginStateSnapshot() {
        return Prefers.getString("login_state_snapshot");
    }

    public static void putLoginStateSnapshot(String snapshot) {
        Prefers.put("login_state_snapshot", snapshot);
    }

    public static String getLoginStateFindings() {
        return Prefers.getString("login_state_findings");
    }

    public static void putLoginStateFindings(String findings) {
        Prefers.put("login_state_findings", findings);
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    /** 语言设置已移除：始终跟随系统，并忽略历史偏好，避免旧数据强制切换地区。 */
    public static int getLanguage() {
        return LANGUAGE_FOLLOW_SYSTEM;
    }

    /** 自动简繁转换已停用：显式置 false，避免 null 回落按系统地区自动转繁体。 */
    public static void applyLanguage() {
        Trans.setTraditional(false);
    }

    public static Context wrapDisplay(Context context) {
        return wrapUiScale(wrapLanguage(context));
    }

    public static Context wrapLanguage(Context context) {
        applyLanguage();
        return context;
    }

    public static int getUiScale() {
        int scale = Prefers.getInt("ui_scale", UI_SCALE_FOLLOW_SYSTEM);
        return isUiScale(scale) ? scale : UI_SCALE_FOLLOW_SYSTEM;
    }

    public static void putUiScale(int scale) {
        Prefers.put("ui_scale", isUiScale(scale) ? scale : UI_SCALE_FOLLOW_SYSTEM);
    }

    public static int getUiScaleIndex() {
        int scale = getUiScale();
        for (int i = 0; i < UI_SCALE_OPTIONS.length; i++) if (UI_SCALE_OPTIONS[i] == scale) return i;
        return UI_SCALE_FOLLOW_SYSTEM;
    }

    public static void putUiScaleIndex(int index) {
        putUiScale(index >= 0 && index < UI_SCALE_OPTIONS.length ? UI_SCALE_OPTIONS[index] : UI_SCALE_FOLLOW_SYSTEM);
    }

    private static boolean isUiScale(int scale) {
        for (int option : UI_SCALE_OPTIONS) if (option == scale) return true;
        return false;
    }

    public static Context wrapUiScale(Context context) {
        int scale = getUiScale();
        if (scale == UI_SCALE_FOLLOW_SYSTEM) return context;
        float factor = getUiScaleFactor(scale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int stableDensity = DisplayMetrics.DENSITY_DEVICE_STABLE > 0 ? DisplayMetrics.DENSITY_DEVICE_STABLE : metrics.densityDpi;
        int densityDpi = Math.max(DisplayMetrics.DENSITY_LOW, Math.round(stableDensity * factor));
        config.densityDpi = densityDpi;
        config.fontScale = 1.0f;
        config.screenWidthDp = pxToDp(metrics.widthPixels, densityDpi);
        config.screenHeightDp = pxToDp(metrics.heightPixels, densityDpi);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        return context.createConfigurationContext(config);
    }

    private static float getUiScaleFactor(int scale) {
        return switch (scale) {
            case UI_SCALE_STANDARD -> 0.8f;
            case UI_SCALE_MILD_COMPACT -> 0.75f;
            case UI_SCALE_COMPACT -> 0.7f;
            case UI_SCALE_MORE_COMPACT -> 0.65f;
            case UI_SCALE_SMALLER -> 0.6f;
            default -> 1.0f;
        };
    }

    private static int pxToDp(int px, int densityDpi) {
        return Math.max(1, Math.round(px * (float) DisplayMetrics.DENSITY_DEFAULT / densityDpi));
    }

    public static boolean isDriveCheck() {
        return Prefers.getBoolean("drive_check", true);
    }

    public static void putDriveCheck(boolean driveCheck) {
        Prefers.put("drive_check", driveCheck);
    }

    public static boolean isCompactEpisodeTitle() {
        return Prefers.getBoolean("compact_episode_title");
    }

    public static void putCompactEpisodeTitle(boolean compact) {
        Prefers.put("compact_episode_title", compact);
    }

    public static boolean isSiteHealthSort() {
        return Prefers.getBoolean("site_health_sort", true);
    }

    public static void putSiteHealthSort(boolean sort) {
        Prefers.put("site_health_sort", sort);
    }

    public static boolean isSiteHealthDialogSort() {
        return Prefers.getBoolean("site_health_dialog_sort");
    }

    public static void putSiteHealthDialogSort(boolean sort) {
        Prefers.put("site_health_dialog_sort", sort);
    }

    public static boolean isWebHomeExtension() {
        return Prefers.getBoolean("web_home_extension", true);
    }

    public static void putWebHomeExtension(boolean extension) {
        Prefers.put("web_home_extension", extension);
    }

    public static boolean isWebHomeFullscreen() {
        return Prefers.getBoolean("web_home_fullscreen", true);
    }

    public static void putWebHomeFullscreen(boolean fullscreen) {
        Prefers.put("web_home_fullscreen", fullscreen);
    }

    public static boolean isPlaybackArtworkWall() {
        return Prefers.getBoolean("playback_artwork_wall", true);
    }

    public static void putPlaybackArtworkWall(boolean artworkWall) {
        Prefers.put("playback_artwork_wall", artworkWall);
    }

    public static boolean isCspWarmup() {
        return getCspWarmupMode() != CSP_WARMUP_DISABLED;
    }

    public static void putCspWarmup(boolean warmup) {
        if (warmup) {
            Prefers.put("csp_warmup", true);
            if (getCspWarmupSelectedMode() == CSP_WARMUP_DISABLED) Prefers.put("csp_warmup_mode", CSP_WARMUP_DEFAULT);
        } else {
            Prefers.put("csp_warmup", false);
        }
    }

    public static int getCspWarmupMode() {
        if (!Prefers.getBoolean("csp_warmup")) return CSP_WARMUP_DISABLED;
        return getCspWarmupSelectedMode();
    }

    public static int getCspWarmupSelectedMode() {
        int mode = Prefers.getInt("csp_warmup_mode", CSP_WARMUP_DEFAULT);
        return mode == CSP_WARMUP_CUSTOM ? CSP_WARMUP_CUSTOM : CSP_WARMUP_DEFAULT;
    }

    public static void putCspWarmupMode(int mode) {
        if (mode == CSP_WARMUP_DISABLED) {
            Prefers.put("csp_warmup", false);
        } else {
            Prefers.put("csp_warmup", true);
            Prefers.put("csp_warmup_mode", mode == CSP_WARMUP_CUSTOM ? CSP_WARMUP_CUSTOM : CSP_WARMUP_DEFAULT);
        }
    }

    public static List<String> getCspWarmupSites() {
        try {
            List<String> keys = App.gson().fromJson(Prefers.getString("csp_warmup_sites", "[]"), STRING_LIST);
            if (keys == null) return Collections.emptyList();
            List<String> result = new ArrayList<>();
            for (String key : keys) if (key != null && !key.trim().isEmpty() && !result.contains(key.trim())) result.add(key.trim());
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public static void putCspWarmupSites(List<String> keys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (keys != null) for (String key : keys) if (key != null && !key.trim().isEmpty()) result.add(key.trim());
        Prefers.put("csp_warmup_sites", App.gson().toJson(result));
    }

    public static int getSearchColumn() {
        return Math.min(Math.max(Prefers.getInt("search_column", 1), 1), 2);
    }

    public static void putSearchColumn(int column) {
        Prefers.put("search_column", column == 2 ? 2 : 1);
    }

    /** 搜索页默认模式：选定搜索（短按首页搜索入口）。 */
    public static String getSearchScope() {
        String scope = Prefers.getString("search_scope", "selected");
        return "all".equals(scope) || "current".equals(scope) || "selected".equals(scope) ? scope : "selected";
    }

    public static void putSearchScope(String scope) {
        Prefers.put("search_scope", "all".equals(scope) || "current".equals(scope) || "selected".equals(scope) ? scope : "selected");
    }

    public static boolean isDebugLog() {
        return DebugLogStore.isEnabled();
    }

    public static void putDebugLog(boolean debugLog) {
        DebugLogStore.setEnabled(debugLog);
        if (debugLog) logDebugEnvironment("enable");
    }

    public static void logDebugEnvironment(String reason) {
        boolean hardwareAccelerated = (App.get().getApplicationInfo().flags & ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
        DebugLogStore.event(new com.github.catvod.crawler.diagnostics.DiagnosticEvent("env.device", "none", "process", 0, 0)
                .observed("reason", reason).observed("appVersion", BuildConfig.VERSION_NAME).observed("versionCode", BuildConfig.VERSION_CODE)
                .observed("buildTime", BuildConfig.BUILD_TIME).observed("buildTag", BuildConfig.BUILD_TAG)
                .observed("gitRevision", BuildConfig.GIT_REVISION).observed("state", BuildConfig.GIT_STATE)
                .observed("fingerprintDigest", com.fongmi.android.tv.player.NativeLibraryDiagnostics.digestText(Build.FINGERPRINT))
                .observed("media3Version", BuildConfig.MEDIA3_VERSION).observed("flavor", BuildConfig.FLAVOR_mode)
                .observed("abi", BuildConfig.FLAVOR_abi).observed("process64Bit", android.os.Process.is64Bit())
                .observed("android", Build.VERSION.RELEASE).observed("api", Build.VERSION.SDK_INT)
                .observed("targetSdk", App.get().getApplicationInfo().targetSdkVersion)
                .observed("manufacturer", Build.MANUFACTURER).observed("model", Build.MODEL)
                .observed("device", Build.DEVICE).observed("hardwareAccelerated", hardwareAccelerated)
                .pin("device"));
        com.fongmi.android.tv.player.NativeLibraryDiagnostics.request();
        com.fongmi.android.tv.player.PlaybackDiagnosticSession.captureActive(android.os.SystemClock.elapsedRealtime());
        SpiderDebug.log("env", "reason=%s app=%s(%s) mode=%s abi=%s debug=%s hardware=%s android=%s sdk=%s incremental=%s manufacturer=%s brand=%s model=%s device=%s product=%s supportedAbis=%s",
                reason,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.FLAVOR_mode,
                BuildConfig.FLAVOR_abi,
                BuildConfig.DEBUG,
                hardwareAccelerated,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.VERSION.INCREMENTAL,
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                String.join(",", Build.SUPPORTED_ABIS));
        WebViewUtil.logProvider("debug-env");
    }

    public static boolean isShellProxy() {
        return Prefers.getBoolean("shell_proxy");
    }

    public static void putShellProxy(boolean shellProxy) {
        Prefers.put("shell_proxy", shellProxy);
        ProxySetting.apply();
    }

    public static String getShellProxyRules() {
        return Prefers.getString("shell_proxy_rules");
    }

    public static void putShellProxyRules(String rules) {
        Prefers.put("shell_proxy_rules", rules);
        ProxySetting.apply();
    }

    public static void putShellProxyConfig(String url, String rules) {
        Prefers.put("shell_proxy_url", url);
        Prefers.put("shell_proxy_rules", rules);
        Prefers.put("shell_proxy_hosts", "*");
        ProxySetting.apply();
    }

    public static String getShellProxyUrl() {
        return Prefers.getString("shell_proxy_url");
    }

    public static void putShellProxyUrl(String url) {
        Prefers.put("shell_proxy_url", url);
        ProxySetting.apply();
    }

    public static String getShellProxyHosts() {
        return Prefers.getString("shell_proxy_hosts", "*");
    }

    public static void putShellProxyHosts(String hosts) {
        Prefers.put("shell_proxy_hosts", hosts);
        ProxySetting.apply();
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static String getUpdateSource() {
        return UpdateSource.normalize(Prefers.getString("update_source", UpdateSource.OCI));
    }

    public static void putUpdateSource(String source) {
        Prefers.put("update_source", UpdateSource.normalize(source));
    }

    public static String getUpdateGithubProxy() {
        return GithubProxy.find(Prefers.getString("update_github_proxy", GithubProxy.DIRECT)).id;
    }

    public static void putUpdateGithubProxy(String proxy) {
        Prefers.put("update_github_proxy", GithubProxy.find(proxy).id);
    }

    public static String getUpdateGithubProxyUrl() {
        return Prefers.getString("update_github_proxy_url");
    }

    public static void putUpdateGithubProxyUrl(String url) {
        Prefers.put("update_github_proxy_url", url == null ? "" : url.trim());
    }

    public static String getUpdateGithubProxyMode() {
        return GithubProxy.normalizeMode(Prefers.getString("update_github_proxy_mode", GithubProxy.MODE_FULL_URL));
    }

    public static void putUpdateGithubProxyMode(String mode) {
        Prefers.put("update_github_proxy_mode", GithubProxy.normalizeMode(mode));
    }

    public static String getUpdateOciMirror() {
        return OciMirror.find(Prefers.getString("update_oci_mirror", OciMirror.DEFAULT)).id;
    }

    public static void putUpdateOciMirror(String mirror) {
        Prefers.put("update_oci_mirror", OciMirror.find(mirror).id);
    }

    public static String getUpdateOciMirrorUrl() {
        return Prefers.getString("update_oci_mirror_url");
    }

    public static void putUpdateOciMirrorUrl(String url) {
        Prefers.put("update_oci_mirror_url", url == null ? "" : url.trim());
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(App.get(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(App.get(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasFileManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + App.get().getPackageName())).resolveActivity(App.get().getPackageManager()) != null || new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(App.get().getPackageManager()) != null;
    }
    public static String getShortDramaConfig() {
        return Prefers.getString("short_drama_config");
    }

    public static void putShortDramaConfig(String value) {
        Prefers.put("short_drama_config", value);
    }

    public static boolean isShortDramaSiteEnabled(String key, String name) {
        return ShortDramaConfig.objectFrom(getShortDramaConfig()).isSiteEnabled(key, name);
    }


    // ③ 个性设置（从上游按方法块 cherry-pick）
    public static final int DETAIL_OPEN_DIRECT = 2;
    public static final int DETAIL_OPEN_ENHANCED = 1;
    public static final int DETAIL_OPEN_FUSION = 0;
    public static final int DETAIL_OPEN_ORIGINAL_ENHANCED = 5;
    public static final int DETAIL_OPEN_PLAYER = 4;
    public static final int DETAIL_STYLE_CINEMA = 1;
    public static final int DETAIL_STYLE_NATIVE = 2;
    public static final int DETAIL_STYLE_PROFILE = 0;
    public static final int LANGUAGE_SIMPLIFIED = 1;
    public static final int GLOBAL_HISTORY_OFF = 0;
    public static final int GLOBAL_HISTORY_AUTO = 1;
    public static final int GLOBAL_HISTORY_SEARCH = 2;
    public static final int LANGUAGE_TRADITIONAL = 2;
    public static String getAiConfig() {
    return Prefers.getString("ai_config");
    }
    public static String getAudioConfig() {
    return Prefers.getString("audio_config");
    }
    public static int getFullscreenMenuKey() {
    return Prefers.getInt("fullscreen_menu_key", 0);
    }
    public static int getGlobalHistoryMode() {
    return clampGlobalHistoryMode(Prefers.getInt("global_history_mode", GLOBAL_HISTORY_OFF));
    }
    public static int getHomeMenuKey() {
    int menuKey = Prefers.getInt("home_menu_key", 0);
    return menuKey < 0 || menuKey > 9 ? 0 : menuKey;
    }
    public static int getInterfaceFailoverMode() {
    return InterfaceFailoverPolicy.clampMode(Prefers.getInt("interface_failover_mode", InterfaceFailoverPolicy.DEFAULT_MODE));
    }
    public static int getSearchResultSort() {
    return Prefers.getInt("search_result_sort", 0);
    }
    public static int getSearchThread() {
    return clampSearchThread(Prefers.getInt("search_thread", 20));
    }
    public static int getSearchUi() {
    return Prefers.getInt("search_ui", 1) == 0 ? 0 : 1;
    }
    public static boolean isAutoBackup() {
    return Prefers.getBoolean("auto_backup", false);
    }
    public static boolean isEpisodeHistory() {
    return Prefers.getBoolean("episode_history", true);
    }
    public static boolean isHomeHistory() {
    return Prefers.getBoolean("home_history", true);
    }
    public static boolean isHomeSiteLock() {
    return Prefers.getBoolean("home_site_lock", false);
    }
    public static boolean isHomeVodAutoLoad() {
    return Prefers.getBoolean("home_vod_auto_load", true);
    }
    public static boolean isPlayBackToDetail() {
    return Prefers.getBoolean("play_back_to_detail");
    }
    public static boolean isPlaybackOverlayEnabled() {
    return Prefers.getBoolean("playback_overlay_enabled", true);
    }
    public static boolean isTouchOptimized() {
    return Util.isLeanback() && Prefers.getBoolean("touch_optimized");
    }
    public static void putAutoBackup(boolean autoBackup) {
    Prefers.put("auto_backup", autoBackup);
    }
    public static void putEpisodeHistory(boolean episodeHistory) {
    Prefers.put("episode_history", episodeHistory);
    }
    public static void putFullscreenMenuKey(int menuKey) {
    Prefers.put("fullscreen_menu_key", menuKey);
    }
    public static void putGlobalHistoryMode(int mode) {
    Prefers.put("global_history_mode", clampGlobalHistoryMode(mode));
    }
    public static void putHomeHistory(boolean homeHistory) {
    Prefers.put("home_history", homeHistory);
    }
    public static void putHomeMenuKey(int menuKey) {
    Prefers.put("home_menu_key", menuKey);
    }
    public static void putHomeSiteLock(boolean homeSiteLock) {
    Prefers.put("home_site_lock", homeSiteLock);
    }
    public static void putHomeVodAutoLoad(boolean autoLoad) {
    Prefers.put("home_vod_auto_load", autoLoad);
    }
    public static void putInterfaceFailoverMode(int mode) {
    int value = InterfaceFailoverPolicy.clampMode(mode);
    Prefers.put("interface_failover_mode", value);
    }
    public static void putPlayBackToDetail(boolean backToDetail) {
    Prefers.put("play_back_to_detail", backToDetail);
    }
    public static void putPlaybackOverlayEnabled(boolean enabled) {
    Prefers.put("playback_overlay_enabled", enabled);
    }
    public static void putSearchResultSort(int sort) {
    Prefers.put("search_result_sort", sort == 0 ? 0 : 1);
    }
    public static void putSearchThread(int thread) {
    Prefers.put("search_thread", clampSearchThread(thread));
    }
    public static void putSearchUi(int ui) {
    Prefers.put("search_ui", ui == 0 ? 0 : 1);
    }
    public static void putTouchOptimized(boolean enabled) {
    Prefers.put("touch_optimized", enabled);
    }

    public static boolean isWebHomeThemeEnabled() {
        return Prefers.getBoolean("web_home_theme_enabled");
    }

    public static void putWebHomeThemeEnabled(boolean enabled) {
        Prefers.put("web_home_theme_enabled", enabled);
    }

    public static String getWebHomeThemeUrl() {
        String legacy = "file:///android_asset/webhome/eclipse.html";
        String current = Prefers.getString("web_home_theme_url", "file:///android_asset/webhome/theme.json");
        if (!legacy.equals(current)) return current;
        current = "file:///android_asset/webhome/theme.json";
        Prefers.put("web_home_theme_url", current);
        return current;
    }

    public static void putWebHomeThemeUrl(String url) {
        Prefers.put("web_home_theme_url", url);
    }

    public static String getWebHomeThemeTrustedUrl() {
        return Prefers.getString("web_home_theme_trusted_url");
    }

    public static void putWebHomeThemeTrustedUrl(String url) {
        Prefers.put("web_home_theme_trusted_url", url);
    }


    private static int clampGlobalHistoryMode(int mode) {
        return mode == GLOBAL_HISTORY_AUTO || mode == GLOBAL_HISTORY_SEARCH ? mode : GLOBAL_HISTORY_OFF;
    }

    private static int clampSearchThread(int thread) {
        return Math.max(1, Math.min(thread, 100));
    }

}
