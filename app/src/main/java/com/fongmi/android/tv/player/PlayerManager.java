package com.fongmi.android.tv.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.codec.CodecCapabilityInspector;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.IjkPlayerEngine;
import com.fongmi.android.tv.player.exo.ass.ExoAssSession;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.exo.ExoDecoderResourceRecoveryLimiter;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardBufferPolicy;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardController;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardEligibility;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagController;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagPolicy;
import com.fongmi.android.tv.player.exo.ForwardBufferTrend;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.player.danmaku.DanmakuUrlPolicy;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuBatcher;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuBuffer;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuMessage;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuMetrics;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuParser;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuWebSocketSession;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.LocalProxyDebug;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;


import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private static final String NETWORK_GUARD_DEBUG = "EXO_NETWORK_GUARD";

    private static void logNetworkGuard(String message) {
        if (SpiderDebug.isEnabled()) Log.d(NETWORK_GUARD_DEBUG, message);
    }

    private static final long LOCAL_PROXY_READY_TIMEOUT_MS = 5000;
    private static final long LOCAL_PROXY_RETRY_DELAY_MS = 1000;
    private static final long HARD_DECODE_SWITCH_RETRY_DELAY_MS = 1200;
    private static final long EXO_TUNNELING_RETRY_DELAY_MS = 250;
    private static final long EXO_DECODER_RUNTIME_RETRY_DELAY_MS = 1200;
    private static final long EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS = 500;
    private static final long EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS = 1200;
    private static final int LOCAL_PROXY_MAX_RETRY = 2;
    private static final long DANMAKU_FORCE_RELOAD_DEBOUNCE_MS = 10000;
    private static final long LIVE_DANMAKU_METRICS_INTERVAL_MS = 15000L;
    private static final long PLAYBACK_TELEMETRY_INTERVAL_MS = 5000L;
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000L;
    private static final float[] SPEED_PRESETS = new float[]{0.5f, 0.75f, 1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 5f};
    private static final DecimalFormat SPEED_FORMAT = new DecimalFormat("0.##x");

    private final Runnable runnable;
    private final Runnable liveDanmakuMetricsRunnable;
    private final Runnable networkProtectionRunnable;
    private final Runnable playbackTelemetryRunnable;
    private final Callback callback;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private final BroadcastReceiver noisyReceiver;
    private final PlaybackBufferingTracker playbackBufferingTracker;
    private final PlaybackTrace playbackTrace;
    private final PlaybackAutoContextStore playbackAutoContextStore;
    private final PlaybackTelemetryCoordinator playbackTelemetryCoordinator;
    private final PlaybackMediaFactsCoordinator playbackMediaFactsCoordinator;
    private final ExoDecoderResourceRecoveryLimiter
            exoDecoderResourceRecoveryLimiter;
    private final ExoNetworkGuardController networkProtectionController;
    private final ExoRtspLiveLagController rtspLiveLagController;
    private final ForwardBufferTrend networkProtectionTrend;
    private final LiveDanmakuBatcher liveDanmakuBatcher;
    private final LiveDanmakuBuffer liveDanmakuBuffer;
    private final LiveDanmakuMetrics liveDanmakuMetrics;
    private DanmakuController danmakuController;
    private LiveDanmakuWebSocketSession liveDanmakuSession;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private String currentDanmakuUrl;
    private String currentDanmakuKey;
    private String loadingDanmakuKey;
    private String lastLoggedRouteTraceId = PlaybackTrace.NONE;
    private ExoDecoderResourceRecovery pendingExoDecoderResourceRecovery;
    private PlaybackAutoContext.SessionToken playbackAutoSession = PlaybackAutoContext.SessionToken.none();
    private long playbackTrackSequence;
    private long danmakuLoadStartedAtMs;
    private volatile long liveDanmakuGeneration;
    private volatile boolean liveDanmakuPlaybackActive;
    private long pendingSwitchPositionMs = C.TIME_UNSET;
    private long pendingInitialStartPositionMs = C.TIME_UNSET;
    private float pendingSwitchSpeed = 1f;
    private boolean danmakuLoadInProgress;
    private boolean danmakuForeground = true;
    private boolean pendingSwitchRepeat;
    private boolean pendingSwitchRestore;
    private boolean audioFocusHeld;
    private boolean noisyReceiverRegistered;
    private boolean resumeOnAudioFocusGain;
    private Object audioFocusRequest;

    private boolean initTrack;
    private boolean videoEffectsActive;
    private boolean videoEffectsDirty;
    private boolean playWhenReady = true;
    private boolean hardDecodeSwitchRetryArmed;
    private boolean playbackForeground;
    private boolean exoDecoderResourceRecoveryInProgress;
    private int playerType;
    private int retry;
    private int localProxyRetry;
    private int prepareSeq;
    private float userPlaybackSpeed = 1f;
    private float networkProtectionSpeed = 1f;
    private float networkProtectionSupportedSpeed = 1f;
    private long networkProtectionMediaBitrate;
    private ExoNetworkGuardController.State networkProtectionState = ExoNetworkGuardController.State.NORMAL;
    private ExoNetworkGuardController.ProtectionTier networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
    private String networkProtectionReason = "waiting";

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.runnable = this::onPlaybackTimeout;
        this.liveDanmakuMetricsRunnable = () -> logLiveDanmakuMetrics("periodic", true);
        this.networkProtectionRunnable = this::evaluateNetworkProtection;
        this.playbackTelemetryRunnable = this::publishPlaybackTelemetryTick;
        this.playbackBufferingTracker = new PlaybackBufferingTracker();
        this.playbackTrace = new PlaybackTrace();
        this.playbackAutoContextStore = PlaybackAutoContextStore.process();
        this.playbackTelemetryCoordinator = PlaybackTelemetryCoordinator.process();
        this.playbackMediaFactsCoordinator = new PlaybackMediaFactsCoordinator(playbackAutoContextStore);
        this.exoDecoderResourceRecoveryLimiter =
                new ExoDecoderResourceRecoveryLimiter();
        this.networkProtectionController = new ExoNetworkGuardController();
        this.rtspLiveLagController = new ExoRtspLiveLagController();
        this.networkProtectionTrend = new ForwardBufferTrend();
        this.liveDanmakuBuffer = new LiveDanmakuBuffer();
        this.liveDanmakuMetrics = new LiveDanmakuMetrics();
        this.liveDanmakuBatcher = new LiveDanmakuBatcher(liveDanmakuBuffer, this::onLiveDanmakuBatch);
        this.audioFocusChangeListener = this::onNativeAudioFocusChanged;
        this.noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                onNativeAudioBecomingNoisy();
            }
        };
        this.playerType = PlayerSetting.getPlayer();
        this.engine = buildEngine(playerType, PlayerEngine.HARD);
        this.player = engine.getPlayer();
    }

    public void release() {
        prepareSeq++;
        prepareTerminalRelease();
        resetNetworkProtectionSession("release");
        clearExoDecoderResourceRecovery(true);
        player.removeListener(listener);
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        App.removeCallbacks(playbackTelemetryRunnable);
        stopNativeAudioSession();
        clearDanmaku("release");
        releaseLiveDanmakuSession();
        liveDanmakuBatcher.release();
        App.removeCallbacks(liveDanmakuMetricsRunnable);
        if (danmakuController != null) danmakuController.setListener(null);
        danmakuController = null;
        endPlaybackTelemetrySession("release");
        clearPlaybackAutoContext();
        if (engine == null) return;
        engine.release();
        engine = null;
        player = null;
        videoEffectsActive = false;
        videoEffectsDirty = false;
        playbackBufferingTracker.reset();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }

    public void prepareTerminalRelease() {
    }

    private void onPlaybackTimeout() {
        if (retryExoDv7FirstFrameTimeout()) return;
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    private void resetVideoEffectsState() {
        if (engine != null && videoEffectsActive) {
            try {
                engine.setVideoEffects(Collections.emptyList());
            } catch (Throwable ignored) {
            }
        }
        videoEffectsActive = false;
        videoEffectsDirty = false;
    }

    public Player getPlayer() {
        return player;
    }

    public ExoAssSession getAssSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getAssSession() : null;
    }

    public com.fongmi.android.tv.player.exo.subtitle.ExoSubtitleSession getSubtitleSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getSubtitleSession() : null;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return engine.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public String getPlaybackTraceId() {
        return playbackTrace.current();
    }

    public PlaybackAutoContext getPlaybackAutoContext() {
        return playbackAutoContextStore.snapshot();
    }

    public void publishPlaybackRenderTarget(PlaybackAutoContext.RenderTarget renderTarget) {
        playbackMediaFactsCoordinator.publishRenderTarget(
                playbackAutoSession, renderTarget, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDisplayFacts(
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode) {
        playbackMediaFactsCoordinator.publishDisplayFacts(
                playbackAutoSession, currentMode, requestedMode, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDecision(PlaybackTelemetry.DecisionEvent event) {
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession, event, SystemClock.elapsedRealtime());
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return userPlaybackSpeed;
    }

    public float getEffectiveSpeed() {
        return player == null ? userPlaybackSpeed : player.getPlaybackParameters().speed;
    }

    public String getNetworkProtectionText() {
        if (!isExo() || !ExoPerformanceSetting.isNetworkProtectionEnabled()) return "";
        if (Math.abs(userPlaybackSpeed - 1f) > 0.001f) return "手动倍速时停用";
        if (!isVod()) return "仅支持点播";
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        if (!eligibility.eligible()) return networkProtectionEligibilityText(eligibility.reason());
        return switch (networkProtectionState) {
            case NORMAL -> "正常";
            case WARNING -> "评估中";
            case PROTECT -> "降速中";
            case RECOVERY -> "恢复中";
            case UNSUSTAINABLE -> "网络不足";
        };
    }

    private String networkProtectionEligibilityText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "preserve-passthrough" -> "音频直通时停用";
            case "preserve-tunneling" -> "隧道模式时停用";
            case "speed-unsupported" -> "播放器不支持调速";
            case "user-speed" -> "手动倍速时停用";
            case "vod-only" -> "仅支持点播";
            default -> "未启用";
        };
    }

    public long getNetworkProtectionMediaBitrate() {
        return networkProtectionMediaBitrate;
    }

    public long getNetworkProtectionStableThroughput() {
        return networkProtectionMediaBitrate <= 0 ? 0 : Math.max(0, Math.round(networkProtectionMediaBitrate * networkProtectionSupportedSpeed));
    }

    public long getNetworkProtectionConsumption() {
        return networkProtectionMediaBitrate <= 0 ? 0 : Math.max(0, Math.round(networkProtectionMediaBitrate * getEffectiveSpeed()));
    }

    public float getNetworkProtectionSupportedSpeed() {
        return networkProtectionSupportedSpeed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean haveDanmaku() {
        return getDanmakus() != null && getDanmakus().stream().anyMatch(Danmaku::isSelected);
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public long getBufferedDuration() {
        return Math.max(0, getEffectiveBufferedPosition() - getPosition());
    }

    public int getBufferedPercentage() {
        if (!isExo()) return player.getBufferedPercentage();
        long duration = player.getDuration();
        if (duration == 0) return 100;
        if (duration < 0) return 0;
        return Math.max(0, Math.min(100, androidx.media3.common.util.Util.percentInt(
                getEffectiveBufferedPosition(), duration)));
    }

    private long getEffectiveBufferedPosition() {
        long nativeBuffered = Math.max(0, player.getBufferedPosition());
        if (!isExo()) return nativeBuffered;
        String mediaKey = PlaybackDiskBufferStore.mediaKey(player.getCurrentMediaItem());
        return PlaybackDiskBufferStore.process().effectiveEnd(
                mediaKey, nativeBuffered, player.getDuration(), DISK_RANGE_GAP_TOLERANCE_MS);
    }

    public boolean isLoading() {
        return player.isLoading();
    }

    public String getSizeText() {
        int width = getVideoWidth();
        int height = getVideoHeight();
        if (width <= 0 || height <= 0) {
            Format format = getVideoFormat();
            if (format != null) {
                if (width <= 0) width = format.width;
                if (height <= 0) height = format.height;
            }
        }
        return width <= 0 || height <= 0 ? "" : width + " x " + height;
    }

    public Format getVideoFormat() {
        return engine.getVideoFormat();
    }

    public PlayerCacheState getCacheState() {
        return engine == null ? PlayerCacheState.empty() : engine.getCacheState();
    }

    public String getRenderDiagnostics() {
        return engine == null ? "" : engine.getRenderDiagnostics();
    }

    public String getRuntimeDiagnostics() {
        return engine == null ? "" : engine.getRuntimeDiagnostics();
    }

    public String getGpuLoadDiagnostics() {
        return engine == null ? "" : engine.getGpuLoadDiagnostics();
    }

    public void setGpuLoadDiagnosticsEnabled(boolean enabled) {
        if (engine != null) engine.setGpuLoadDiagnosticsEnabled(enabled);
    }

    public PlayerEngine.VideoPlaybackDetails getVideoPlaybackDetails() {
        return engine == null
                ? PlayerEngine.VideoPlaybackDetails.empty()
                : engine.getVideoPlaybackDetails();
    }

    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        return engine == null
                ? AudioPlaybackDiagnostics.Snapshot.empty()
                : engine.getAudioPlaybackDiagnostics();
    }

    public long getDroppedFrames() {
        return engine == null ? 0 : engine.getDroppedFrames();
    }

    public int getRebufferCount() {
        long discCount = 0;
        return (int) Math.min(Integer.MAX_VALUE, playbackBufferingTracker.getRebufferCount() + discCount);
    }

    public long getRebufferTotalMs() {
        return getRebufferTotalMs(SystemClock.elapsedRealtime());
    }

    private long getRebufferTotalMs(long nowMs) {
        long discMs = 0;
        return playbackBufferingTracker.getRebufferTotalMs(nowMs) + discMs;
    }

    public boolean supportsSubtitleStyle() {
        return engine != null && engine.supportsSubtitleStyle();
    }

    public boolean supportsSecondarySubtitle() {
        return engine != null && engine.supportsSecondarySubtitle();
    }

    public boolean isSecondarySubtitleSelected(Format format) {
        return engine != null && engine.isSecondarySubtitleSelected(format);
    }

    public boolean isPrimarySubtitleSelected(Format format) {
        return engine != null && engine.isPrimarySubtitleSelected(format);
    }

    public String getAudioPassThroughText() {
        if (!PlayerSetting.isAudioPassThrough()) return "关";
        return "开";
    }

    public void setSubtitleStyle(float textSize, float position) {
        if (engine != null) engine.setSubtitleStyle(textSize, position);
    }

    public String getSpeedText() {
        return SPEED_FORMAT.format(getSpeed());
    }

    public String getDecodeText() {
        return engine.getDecodeText();
    }

    public boolean isHardDecode() {
        return engine.isHard();
    }

    public String getPlayerText() {
        return ResUtil.getStringArray(R.array.select_player_kernel)[playerType];
    }

    public int getPlayerType() {
        return playerType;
    }

    /**
     * Keep the native player's shutter visible while automatic MPV output is
     * still being selected. This prevents a failed direct DV probe from
     * exposing a stale poster or the last frame before the GPU rebuild.
     */
    public boolean shouldKeepVideoShutterClosed() {
        return false;
    }

    public boolean isExo() {
        return playerType == PlayerSetting.EXO;
    }

    public boolean isNativePlayer() {
        return !isExo();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        setMediaItem();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public void setTitle(MediaEdition edition) {
        if (edition == null) return;
        if (spec != null) spec.setUrl(spec.getUri().buildUpon().fragment("edition=" + edition.index).build().toString());
        if (engine.selectEdition(edition)) return;
        setMediaItem();
        seekTo(0);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        engine.setMetadata(data);
    }

    public void setDanmakuController(DanmakuController controller) {
        if (danmakuController == controller) {
            configureDanmakuController(controller);
            return;
        }
        if (danmakuController != null) {
            danmakuController.setListener(null);
            danmakuController.clearItems();
        }
        danmakuController = controller;
        if (danmakuController == null) return;
        configureDanmakuController(danmakuController);
        restoreDanmakuDataSource();
    }

    private void configureDanmakuController(DanmakuController controller) {
        if (controller == null) return;
        controller.setOkHttpClient(OkHttp.player());
        controller.setConfig(DanmakuSetting.getConfig());
        controller.setEnabled(DanmakuSetting.isShow());
        controller.setListener(new DanmakuController.Listener() {
            @Override
            public void onLoadCompleted(Uri uri, int count) {
                logDanmakuLoad("completed", uri, count, null);
                finishDanmakuLoad(uri);
            }

            @Override
            public void onLoadError(Uri uri, IOException error) {
                logDanmakuLoad("error", uri, -1, error);
                finishDanmakuLoad(uri);
            }
        });
    }

    private void restoreDanmakuDataSource() {
        if (danmakuController == null || TextUtils.isEmpty(currentDanmakuUrl)) return;
        if (!DanmakuUrlPolicy.classify(currentDanmakuUrl).isStatic()) return;
        loadingDanmakuKey = currentDanmakuKey;
        danmakuLoadStartedAtMs = SystemClock.elapsedRealtime();
        danmakuLoadInProgress = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "restore controller %s key=%s", DanmakuUrlPolicy.logSummary(currentDanmakuUrl), summarizeUrl(currentDanmakuKey));
        danmakuController.setDataSource(Uri.parse(currentDanmakuUrl));
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        if (danmakuController != null) danmakuController.setConfig(config);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuController != null) danmakuController.setEnabled(enabled);
        if (!enabled) {
            stopLiveDanmakuSession("hidden");
        } else if (danmakuForeground && DanmakuUrlPolicy.classify(currentDanmakuUrl).isLive()) {
            connectLiveDanmakuSession(currentDanmakuUrl);
        }
    }

    public void setDanmakuForeground(boolean foreground) {
        if (danmakuForeground == foreground) return;
        danmakuForeground = foreground;
        if (!foreground) {
            stopLiveDanmakuSession("background");
            discardLiveDanmakuPending();
        } else if (DanmakuSetting.isShow() && DanmakuUrlPolicy.classify(currentDanmakuUrl).isLive()) {
            connectLiveDanmakuSession(currentDanmakuUrl);
        }
    }

    public void setPlaybackForeground(boolean foreground) {
        playbackForeground = foreground;
        if (!foreground || pendingExoDecoderResourceRecovery == null) return;
        ExoDecoderResourceRecovery recovery = pendingExoDecoderResourceRecovery;
        pendingExoDecoderResourceRecovery = null;
        scheduleExoDecoderResourceRecovery(recovery, "foreground");
    }

    public void sendDanmaku(String text) {
        if (danmakuController != null) danmakuController.sendNow(text);
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        if (Math.abs(speed - userPlaybackSpeed) >= 0.001f) {
        }
        userPlaybackSpeed = speed;
        resetNetworkProtectionSession("user-speed");
        if (Math.abs(speed - 1f) < 0.001f) scheduleNetworkProtection(0);
        return getSpeedText();
    }

    private void applyEffectiveSpeed(float speed, String reason) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            logNetworkGuard("apply skipped reason=" + reason + " player=" + (player != null)
                    + " command=" + (player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)));
            return;
        }
        float current = player.getPlaybackParameters().speed;
        if (Math.abs(speed - userPlaybackSpeed) >= 0.001f) {
        }
        logNetworkGuard(String.format(java.util.Locale.US,
                "apply request reason=%s requested=%.3f current=%.3f user=%.3f state=%d playing=%s loading=%s",
                reason, speed, current, userPlaybackSpeed, player.getPlaybackState(), player.isPlaying(), player.isLoading()));
        if (Math.abs(current - speed) < 0.001f) return;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        logNetworkGuard(String.format(java.util.Locale.US,
                "apply result reason=%s requested=%.3f actual=%.3f", reason, speed, player.getPlaybackParameters().speed));
        PlaybackTrace.log("exo-network-protection", playbackTrace.current(), "speed %.3f->%.3f reason=%s user=%.2f", current, speed, reason, userPlaybackSpeed);
    }

    private void resetNetworkProtectionSession(String reason) {
        App.removeCallbacks(networkProtectionRunnable);
        networkProtectionController.reset();
        networkProtectionTrend.reset();
        networkProtectionState = ExoNetworkGuardController.State.NORMAL;
        networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
        networkProtectionReason = reason;
        networkProtectionSpeed = 1f;
        networkProtectionSupportedSpeed = 1f;
        networkProtectionMediaBitrate = 0;
        applyEffectiveSpeed(userPlaybackSpeed, reason);
    }

    private ExoNetworkGuardEligibility.Decision getNetworkProtectionEligibility() {
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = getAudioPlaybackDiagnostics()
                .outputMode();
        return ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                ExoPerformanceSetting.isNetworkProtectionEnabled()
                        && false,
                player != null && isExo(),
                isVod(),
                Math.abs(userPlaybackSpeed - 1f) < 0.001f,
                player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                PlayerSetting.isTunnel(),
                audioOutputMode));
    }

    private void scheduleNetworkProtection(long delayMs) {
        App.removeCallbacks(networkProtectionRunnable);
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = getAudioPlaybackDiagnostics()
                .outputMode();
        logNetworkGuard("schedule delay=" + delayMs + " eligible=" + eligibility.eligible()
                + " reason=" + eligibility.reason() + " exo=" + isExo() + " vod=" + isVod()
                + " userSpeed=" + userPlaybackSpeed + " tunnel=" + PlayerSetting.isTunnel()
                + " configuredPassthrough=" + PlayerSetting.isAudioPassThrough(PlayerSetting.EXO)
                + " audioOutput=" + audioOutputMode
                + " state=" + (player == null ? -1 : player.getPlaybackState())
                + " playing=" + (player != null && player.isPlaying()));
        if (!eligibility.eligible()) {
            if (networkProtectionSpeed < 0.999f) resetNetworkProtectionSession(eligibility.reason());
            else {
                networkProtectionState = ExoNetworkGuardController.State.NORMAL;
                networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
                networkProtectionReason = eligibility.reason();
            }
            return;
        }
        if (player.getPlaybackState() != Player.STATE_READY || !player.isPlaying()) return;
        App.post(networkProtectionRunnable, delayMs);
    }

    private void evaluateNetworkProtection() {
        resetNetworkProtectionSession("experiment-disabled");
    }

    public String addSpeed() {
        return setSpeed(nextPresetSpeed());
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    private float nextPresetSpeed() {
        float speed = getSpeed();
        for (float preset : SPEED_PRESETS) if (speed < preset - 0.01f) return preset;
        return SPEED_PRESETS[0];
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) engine.setTrack(tracks);
    }

    public void setSecondarySubtitleTrack(Track track) {
        if (engine != null) engine.setSecondarySubtitleTrack(track);
    }

    public void play() {
        startNativeAudioSession(true);
        player.play();
    }

    public void pause() {
        player.pause();
        stopNativeAudioSession();
    }

    public void stop() {
        stopNativeAudioSession();
        clearDanmaku("stop");
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        stopNativeAudioSession();
        clearDanmaku("clear_media_items");
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        engine.setRepeatOne(repeat);
    }

    public void seekTo(long time) {
        long now = SystemClock.elapsedRealtime();
        rtspLiveLagController.onUserSeek(playbackAutoSession, now);
        resetNetworkProtectionSession("user-seek");
        if (isExo()) {
            PlaybackAnalyticsListener.onUserSeekRequested(
                    player.getCurrentPosition(),
                    time,
                    player.getPlaybackState(),
                    player.getBufferedPosition(),
                    player.getTotalBufferedDuration(),
                    player.isLoading(),
                    player.isPlaying());
        }
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET)) return player.getTextOffsetMs();
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET)) return player.getAudioOffsetMs();
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        boolean activePlayback = player != null
                && player.getPlaybackState() == Player.STATE_READY
                && player.getPlayWhenReady();
        if (activePlayback) scheduleNetworkProtection(0);
        else resetNetworkProtectionSession("reset");
        retry = 0;
        localProxyRetry = 0;
        hardDecodeSwitchRetryArmed = false;
        clearPendingSwitchRestore();
    }

    public void clear() {
        prepareSeq++;
        clearExoDecoderResourceRecovery(true);
        resetNetworkProtectionSession("clear");
        spec = null;
        clearPendingSwitchRestore();
        clearDanmaku("clear");
        endPlaybackTelemetrySession("clear");
        playbackBufferingTracker.reset();
        clearPlaybackAutoContext();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }

    public void resetTrack() {
        engine.resetTrack();
    }

    public void restoreVideoTrack() {
        if (engine != null) engine.restoreVideoTrack();
    }

    public void toggleDecode() {
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        beginPlaybackTrace("switch-decode");
        engine.setDecode(next);
        if (engine instanceof ExoPlayerEngine exo) {
            exo.prepareFrameSchedulingForNextPlayback();
        }
        rebuildPlayer(resetVideoSurface);
        setMediaItem();
    }

    public void switchDecode(PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginPlaybackTrace("switch-decode-fresh");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        resetVideoEffectsState();
        stopNativeAudioSession();
        engine.release();
        spec = freshSpec;
        bindPlaybackTrace();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh decode=%d position=%d spec=%s", next, position, debugSpec());
        callback.onPlayerRebuild(player, resetVideoSurface);
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchDecode(Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginPlaybackTrace("switch-decode-result");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        resetVideoEffectsState();
        stopNativeAudioSession();
        stopParse();
        engine.release();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh parse decode=%d position=%d useParse=%s spec=%s", next, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh result decode=%d position=%d spec=%s", next, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    public void togglePlayer() {
        switchPlayer(PlayerSetting.nextPlayer(playerType));
    }

    public void switchPlayer(int type) {
        switchPlayer(type, true);
    }

    public void switchPlayer(int type, PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginPlaybackTrace("switch-player-fresh");
        type = PlayerSetting.sanitizePlayer(type);
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        resetVideoEffectsState();
        stopNativeAudioSession();
        engine.release();
        playerType = type;
        PlayerSetting.putPlayer(type);
        spec = freshSpec;
        bindPlaybackTrace();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh type=%d position=%d spec=%s", type, position, debugSpec());
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        callback.onPlayerRebuild(player, resetVideoSurface);
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchPlayer(int type, Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginPlaybackTrace("switch-player-result");
        type = PlayerSetting.sanitizePlayer(type);
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        resetVideoEffectsState();
        stopNativeAudioSession();
        stopParse();
        engine.release();
        playerType = type;
        PlayerSetting.putPlayer(type);
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh parse type=%d position=%d useParse=%s spec=%s", type, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh result type=%d position=%d spec=%s", type, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    private void switchPlayer(int type, boolean persist) {
        if (engine == null || player == null) return;
        type = PlayerSetting.sanitizePlayer(type);
        if (type == playerType) return;
        beginPlaybackTrace("switch-player");
        long position = getPosition();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        resetVideoEffectsState();
        stopNativeAudioSession();
        engine.release();
        playerType = type;
        if (persist) {
            PlayerSetting.putPlayer(type);
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player type=%d persist=%s position=%d spec=%s", type, persist, position, debugSpec());
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        // A Surface used by a CPU renderer stays connected after decoder release.
        // A different player needs a new Surface before its GPU/codec can connect.
        callback.onPlayerRebuild(player, true);
        if (spec == null || spec.getUrl() == null) return;
        this.playWhenReady = wasPlayWhenReady;
        if (reparseForPlayerSwitch(position, speed, repeat)) return;
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void rebuildPlayer() {
        rebuildPlayer(false);
    }

    private void rebuildPlayer(boolean resetVideoSurface) {
        stopNativeAudioSession();
        player = engine.rebuild(listener);
        videoEffectsActive = false;
        videoEffectsDirty = false;
        callback.onPlayerRebuild(player, resetVideoSurface);
    }

    public void applyPerformanceSettings() {
        if (isExo()) {
            resetNetworkProtectionSession("performance-settings-changed");
            scheduleNetworkProtection(0);
            if (engine instanceof ExoPlayerEngine exo
                    && exo.requiresDv7Hdr10FallbackRebuild()) {
                rebuildAndRestartExo("dv7-fallback-setting-changed");
            }
            return;
        }
    }

    private void restoreTrackSelection(List<Track> tracks) {
        if (tracks != null && !tracks.isEmpty()) engine.setTrack(tracks);
    }

    private PlayerEngine buildEngine(int type, int decode) {
        return switch (type) {
            case PlayerSetting.IJK -> new IjkPlayerEngine(decode, listener);
            default -> new ExoPlayerEngine(decode, listener);
        };
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, true);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady) {
        start(spec, timeout, playWhenReady, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady, long positionMs) {
        endPlaybackTelemetrySession("replace-start");
        clearPendingSwitchRestore();
        clearDanmaku("start");
        this.spec = spec;
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        beginPlaybackTrace("start", false);
        this.playWhenReady = playWhenReady;
        retry = 0;
        localProxyRetry = 0;
        hardDecodeSwitchRetryArmed = false;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, true);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, boolean playWhenReady) {
        parse(key, result, useParse, metadata, playWhenReady, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata,
                      boolean playWhenReady, long positionMs) {
        endPlaybackTelemetrySession("replace-parse");
        stopParse();
        clearPendingSwitchRestore();
        clearDanmaku("parse");
        spec = PlaySpec.fromParse(result, key, metadata, useParse);
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        beginPlaybackTrace("parse", false);
        this.playWhenReady = playWhenReady;
        retry = 0;
        localProxyRetry = 0;
        hardDecodeSwitchRetryArmed = false;
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private boolean reparseForPlayerSwitch(long position, float speed, boolean repeat) {
        if (spec == null || !spec.canReparse() || !spec.isParseSource()) return false;
        Result result = spec.getParseResult();
        boolean useParse = spec.isParseUseParse();
        MediaMetadata metadata = spec.getMetadata();
        String key = spec.getKey();
        pendingSwitchRestore = true;
        pendingSwitchPositionMs = position;
        pendingSwitchSpeed = speed;
        pendingSwitchRepeat = repeat;
        stopParse();
        if (spec.isParseSource()) {
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player reparse type=%d position=%d useParse=%s spec=%s", playerType, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            refreshDirectForPlayerSwitch(result, key, metadata);
        }
        return true;
    }

    private void refreshDirectForPlayerSwitch(Result result, String key, MediaMetadata metadata) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refresh direct type=%d keyLen=%d flag=%s url=%s", playerType, safeLength(key), result.getFlag(), summarizeUrl(result.getUrl().v()));
        Task.execute(() -> {
            try {
                Result refreshed = SiteApi.playerContent(key, result.getFlag(), result.getUrl().v(), playerType);
                App.post(() -> startRefreshedSwitchResult(refreshed, key, metadata));
            } catch (Throwable e) {
                App.post(() -> {
                    clearPendingSwitchRestore();
                    callback.onError(e.getMessage());
                });
            }
        });
    }

    private void startRefreshedSwitchResult(Result result, String key, MediaMetadata metadata) {
        if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
            clearPendingSwitchRestore();
            callback.onError(result == null ? ResUtil.getString(R.string.error_play_url) : result.hasMsg() ? result.getMsg() : ResUtil.getString(R.string.error_play_url));
            return;
        }
        if (result.needParse()) {
            spec = PlaySpec.fromParse(result, key, metadata, false);
            bindPlaybackTrace();
            parseJob = ParseJob.create(this).start(result, false);
            return;
        }
        spec = PlaySpec.from(result, key, metadata);
        bindPlaybackTrace();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refreshed direct spec=%s", debugSpec());
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    private void restoreAfterSwitchReparse() {
        if (!pendingSwitchRestore) return;
        long position = pendingSwitchPositionMs;
        float speed = pendingSwitchSpeed;
        boolean repeat = pendingSwitchRepeat;
        clearPendingSwitchRestore();
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void clearPendingSwitchRestore() {
        pendingSwitchRestore = false;
        pendingSwitchPositionMs = C.TIME_UNSET;
        pendingSwitchSpeed = 1f;
        pendingSwitchRepeat = false;
    }

    public void setMediaItem() {
        playWhenReady = player == null || player.getPlayWhenReady();
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private boolean rebuildAndRestartExo(String reason) {
        if (!isExo() || spec == null || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        App.removeCallbacks(runnable);
        videoSize = null;
        initTrack = false;
        rebuildPlayer();
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE,
                "player=" + playerType + " decode=" + engine.getDecode()
                        + " exo=" + reason);
        engine.start(spec.checkUa(), position, wasPlayWhenReady);
        startNativeAudioSession(wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        int seq = ++prepareSeq;
        if (LocalProxyDebug.shouldAwaitReady(spec.getUrl())) {
            awaitLocalProxyAndSetMediaItem(seq, timeout);
            return;
        }
        setMediaItemNow(timeout, true);
    }

    private void awaitLocalProxyAndSetMediaItem(int seq, long timeout) {
        PlaySpec target = spec;
        String url = target.getUrl();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await start seq=%d timeout=%d spec=%s", seq, timeout, debugSpec());
        Task.execute(() -> {
            boolean ready = LocalProxyDebug.awaitReady(url, LOCAL_PROXY_READY_TIMEOUT_MS);
            App.post(() -> {
                if (seq != prepareSeq || spec != target || engine == null) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await skip seq=%d current=%d ready=%s", seq, prepareSeq, ready);
                    return;
                }
                if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await done seq=%d ready=%s spec=%s", seq, ready, debugSpec());
                setMediaItemNow(timeout, true);
            });
        });
    }

    private void setMediaItemNow(long timeout, boolean notifyPrepare) {
        if (spec == null || spec.getUrl() == null || engine == null) return;
        prepareExoFrameSchedulingForNewPlayback();
        spec.setPlaybackTraceId(playbackTrace.ensure());
        spec.refreshPlaybackRoute();
        publishPlaybackAutoContext(false);
        logPlaybackRoute();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "setMediaItem timeout=%d notify=%s spec=%s", timeout, notifyPrepare, debugSpec());
        resetNetworkProtectionSession("new-media");
        App.removeCallbacks(runnable);
        setDanmakus(spec.getDanmakus());
        initTrack = false;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE, "player=" + playerType + " decode=" + engine.getDecode());
        long initialPositionMs = pendingInitialStartPositionMs;
        pendingInitialStartPositionMs = C.TIME_UNSET;
        engine.start(spec.checkUa(), initialPositionMs, playWhenReady);
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
        startNativeAudioSession(playWhenReady);
        App.post(runnable, timeout);
        if (notifyPrepare) callback.onPrepare();
    }

    private void prepareExoFrameSchedulingForNewPlayback() {
        if (!(engine instanceof ExoPlayerEngine exo)) return;
        if (!exo.prepareFrameSchedulingForNextPlayback()) return;
        rebuildPlayer(false);
    }

    private void applySubtitleStyle() {
        if (engine != null) engine.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition());
    }

    private void startNativeAudioSession(boolean shouldPlay) {
        if (!shouldPlay || !isNativePlayer()) return;
        requestNativeAudioFocus();
        registerNoisyReceiver();
    }

    private void stopNativeAudioSession() {
        unregisterNoisyReceiver();
        abandonNativeAudioFocus();
        resumeOnAudioFocusGain = false;
    }

    private void requestNativeAudioFocus() {
        AudioManager manager = audioManager();
        if (manager == null || audioFocusHeld) return;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusApi26.request(manager, audioFocusChangeListener, raw -> diagnosticNativeFocus("request", raw, 0));
            result = audioFocusRequest == null ? AudioManager.AUDIOFOCUS_REQUEST_FAILED : AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            result = manager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            diagnosticNativeFocus("request", result, 0);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!audioFocusHeld && SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio focus request denied type=%d", playerType);
    }

    private void abandonNativeAudioFocus() {
        if (!audioFocusHeld) return;
        AudioManager manager = audioManager();
        if (manager != null) {
            int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? AudioFocusApi26.abandon(manager, audioFocusRequest) : manager.abandonAudioFocus(audioFocusChangeListener);
            diagnosticNativeFocus("abandon", result, 0);
        }
        audioFocusRequest = null;
        audioFocusHeld = false;
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        try {
            App.get().registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "register noisy receiver failed error=%s", causeChain(e));
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            App.get().unregisterReceiver(noisyReceiver);
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "unregister noisy receiver failed error=%s", causeChain(e));
        }
        noisyReceiverRegistered = false;
    }

    private void onNativeAudioBecomingNoisy() {
        if (!isNativePlayer() || player == null) return;
        boolean wasPlaying = player.isPlaying();
        player.pause();
        stopNativeAudioSession();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio noisy pause type=%d wasPlaying=%s", playerType, wasPlaying);
    }

    private void onNativeAudioFocusChanged(int focusChange) {
        if (!isNativePlayer() || player == null) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false;
                    startNativeAudioSession(true);
                    player.play();
                }
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnAudioFocusGain = player.isPlaying();
                player.pause();
            }
            case AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnAudioFocusGain = false;
                player.pause();
                stopNativeAudioSession();
            }
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio focus changed type=%d change=%d resume=%s", playerType, focusChange, resumeOnAudioFocusGain);
        diagnosticNativeFocus("callback", 0, focusChange);
    }

    private void diagnosticNativeFocus(String action, int result, int change) {
        if (!com.github.catvod.crawler.DebugLogStore.isEnabled()) return;
        com.github.catvod.crawler.DebugLogStore.event(new com.github.catvod.crawler.diagnostics.DiagnosticEvent("audio.focus", getPlaybackTraceId(),
                "native-focus-controller", 0, 0).observed("focusOwner", "App/native-player").observed("focusAction", action)
                .observed("focusGain", AudioManager.AUDIOFOCUS_GAIN).observed("focusResult", result).observed("focusChange", change)
                .observed("usage", android.media.AudioAttributes.USAGE_MEDIA).observed("contentType", android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .observed("playWhenReady", player != null && player.getPlayWhenReady()).observed("active", audioFocusHeld)
                .observed("metricScope", "controller focus session; callback result is a focus change, not a new request"));
    }

    private AudioManager audioManager() {
        return (AudioManager) App.get().getSystemService(Context.AUDIO_SERVICE);
    }

    private static final class AudioFocusApi26 {

        private static Object request(AudioManager manager, AudioManager.OnAudioFocusChangeListener listener, java.util.function.IntConsumer observe) {
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .build();
            int result = manager.requestAudioFocus(request); observe.accept(result);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? request : null;
        }

        private static int abandon(AudioManager manager, Object request) {
            return request instanceof android.media.AudioFocusRequest ? manager.abandonAudioFocusRequest((android.media.AudioFocusRequest) request) : AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
    }

    private void setDanmakus(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        setDanmaku(item, false);
    }

    public void reloadDanmaku(Danmaku item) {
        setDanmaku(item, true);
    }

    private void setDanmaku(Danmaku item, boolean force) {
        if (item.isEmpty()) {
            if (spec != null) spec.setDanmaku(item);
            clearDanmaku("empty_source");
            return;
        }
        if (danmakuController == null) return;
        String url = DanmakuUrlPolicy.normalize(DanmakuSetting.getValidApiUrl(), item.getRealUrl());
        DanmakuUrlPolicy.SourceType sourceType = DanmakuUrlPolicy.classify(url);
        if (!sourceType.isSupported()) {
            if (spec != null) spec.setDanmaku(item);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "reject %s", DanmakuUrlPolicy.logSummary(url));
            clearDanmaku("unsupported_source");
            return;
        }
        String key = normalizeDanmakuKey(url);
        if (!force && TextUtils.equals(currentDanmakuUrl, url)) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "skip same %s", DanmakuUrlPolicy.logSummary(url));
            return;
        }
        if (force && shouldSkipForcedDanmakuReload(key)) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "skip duplicate reload key=%s %s", summarizeUrl(key), DanmakuUrlPolicy.logSummary(url));
            return;
        }
        if (spec != null) spec.setDanmaku(item);
        if (force && currentDanmakuUrl != null) danmakuController.clearItems();
        currentDanmakuUrl = url;
        currentDanmakuKey = key;
        if (sourceType.isLive()) {
            danmakuController.clearItems();
            loadingDanmakuKey = null;
            danmakuLoadStartedAtMs = 0;
            danmakuLoadInProgress = false;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "select live source pending websocket connection %s", DanmakuUrlPolicy.logSummary(url));
            if (danmakuForeground && DanmakuSetting.isShow()) connectLiveDanmakuSession(url);
            return;
        }
        stopLiveDanmakuSession("static_source");
        loadingDanmakuKey = key;
        danmakuLoadStartedAtMs = SystemClock.elapsedRealtime();
        danmakuLoadInProgress = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "%s name=%s %s key=%s", force ? "reload" : "load", item.getName(), DanmakuUrlPolicy.logSummary(url), summarizeUrl(key));
        danmakuController.setDataSource(Uri.parse(url));
    }

    private boolean shouldSkipForcedDanmakuReload(String key) {
        if (TextUtils.isEmpty(key) || !TextUtils.equals(currentDanmakuKey, key) || danmakuLoadStartedAtMs <= 0) return false;
        if (danmakuLoadInProgress && (TextUtils.isEmpty(loadingDanmakuKey) || TextUtils.equals(loadingDanmakuKey, key))) return true;
        long elapsed = SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        return elapsed >= 0 && elapsed < DANMAKU_FORCE_RELOAD_DEBOUNCE_MS;
    }

    private void finishDanmakuLoad(Uri uri) {
        String key = normalizeDanmakuKey(uri == null ? "" : uri.toString());
        if (!TextUtils.isEmpty(loadingDanmakuKey) && !TextUtils.equals(loadingDanmakuKey, key)) return;
        danmakuLoadInProgress = false;
        loadingDanmakuKey = null;
    }

    private void clearDanmakuState() {
        currentDanmakuUrl = null;
        currentDanmakuKey = null;
        loadingDanmakuKey = null;
        danmakuLoadStartedAtMs = 0;
        danmakuLoadInProgress = false;
    }

    private void clearDanmaku(String reason) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "clear reason=%s current=%s", reason, DanmakuUrlPolicy.logSummary(currentDanmakuUrl));
        stopLiveDanmakuSession(reason);
        liveDanmakuBatcher.clear();
        liveDanmakuBuffer.clear();
        if (danmakuController != null) danmakuController.clearItems();
        clearDanmakuState();
    }

    private void connectLiveDanmakuSession(String url) {
        if (TextUtils.isEmpty(url)) return;
        if (liveDanmakuSession == null) {
            liveDanmakuSession = new LiveDanmakuWebSocketSession(App.get(), new LiveDanmakuWebSocketSession.Listener() {
                @Override
                public void onStateChanged(LiveDanmakuWebSocketSession.State state, long generation, String sourceUrl, int code, String detail) {
                    liveDanmakuGeneration = generation;
                    liveDanmakuMetrics.onState(state, code, SystemClock.elapsedRealtime());
                    if (state == LiveDanmakuWebSocketSession.State.CONNECTING) {
                        liveDanmakuBuffer.reset(generation);
                        liveDanmakuBatcher.reset(generation);
                    } else if (state == LiveDanmakuWebSocketSession.State.RETRY_WAIT || state == LiveDanmakuWebSocketSession.State.STOPPED || state == LiveDanmakuWebSocketSession.State.RELEASED) {
                        liveDanmakuBatcher.clear();
                        liveDanmakuBuffer.clear();
                    }
                    if (state != LiveDanmakuWebSocketSession.State.OPEN) {
                        App.post(() -> logLiveDanmakuMetrics("state_" + state, false));
                        clearLiveDanmakuRenderer(generation);
                    }
                    if (state == LiveDanmakuWebSocketSession.State.CONNECTING || state == LiveDanmakuWebSocketSession.State.OPEN || state == LiveDanmakuWebSocketSession.State.RETRY_WAIT) scheduleLiveDanmakuMetrics();
                    else App.removeCallbacks(liveDanmakuMetricsRunnable);
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku-ws", "state=%s generation=%d code=%d detail=%s %s", state, generation, code, detail, DanmakuUrlPolicy.logSummary(sourceUrl));
                }

                @Override
                public void onMessage(long generation, String text) {
                    if (generation != liveDanmakuGeneration) return;
                    liveDanmakuMetrics.onFrame();
                    long parseStartedNs = SystemClock.elapsedRealtimeNanos();
                    LiveDanmakuParser.Result result = LiveDanmakuParser.parse(text, generation, SystemClock.elapsedRealtime());
                    liveDanmakuMetrics.onParse(result, SystemClock.elapsedRealtimeNanos() - parseStartedNs);
                    if (!result.isAccepted()) return;
                    LiveDanmakuWebSocketSession session = liveDanmakuSession;
                    if (session != null) session.markMessageAccepted(generation);
                    if (result.kind() == LiveDanmakuParser.Kind.ONLINE) {
                        liveDanmakuBuffer.updateOnline(generation, result.online());
                    } else {
                        if (!liveDanmakuPlaybackActive) return;
                        LiveDanmakuMessage message = result.message();
                        LiveDanmakuBuffer.OfferResult offer = liveDanmakuBuffer.offer(message);
                        liveDanmakuMetrics.onOffer(offer);
                        if (offer != LiveDanmakuBuffer.OfferResult.STALE) liveDanmakuBatcher.requestDrain(generation);
                    }
                }
            });
        }
        liveDanmakuGeneration = liveDanmakuSession.connect(url);
    }

    private void stopLiveDanmakuSession(String reason) {
        if (liveDanmakuSession != null) liveDanmakuGeneration = liveDanmakuSession.stop(reason);
    }

    private void releaseLiveDanmakuSession() {
        if (liveDanmakuSession == null) return;
        liveDanmakuSession.release();
        liveDanmakuSession = null;
    }

    private void onLiveDanmakuBatch(long generation, List<LiveDanmakuMessage> messages) {
        long scheduledAtMs = SystemClock.elapsedRealtime();
        App.post(() -> {
            if (generation != liveDanmakuGeneration || messages.isEmpty() || danmakuController == null || !DanmakuSetting.isShow()) return;
            liveDanmakuMetrics.onBatch(messages.size(), SystemClock.elapsedRealtime() - scheduledAtMs);
            List<androidx.media3.ui.danmaku.Danmaku> batch = new ArrayList<>(messages.size());
            for (LiveDanmakuMessage message : messages) {
                int pool = message.type() == LiveDanmakuMessage.Type.SUPER_CHAT ? androidx.media3.ui.danmaku.Danmaku.POOL_SPECIAL : androidx.media3.ui.danmaku.Danmaku.POOL_NORMAL;
                long ttlMs = message.type() == LiveDanmakuMessage.Type.SUPER_CHAT ? LiveDanmakuBuffer.DEFAULT_PRIORITY_TTL_MS : LiveDanmakuBuffer.DEFAULT_NORMAL_TTL_MS;
                batch.add(new androidx.media3.ui.danmaku.Danmaku(message.text(), 0L, androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL, message.colorArgb(), 0f, pool, "", 0L)
                        .setLiveExpiryElapsedRealtimeMs(message.receivedAtMs() + ttlMs));
            }
            danmakuController.offerLiveBatch(batch);
        });
    }

    private void clearLiveDanmakuRenderer(long generation) {
        App.post(() -> {
            if (generation != liveDanmakuGeneration || danmakuController == null) return;
            danmakuController.clearLiveItems();
        });
    }

    private void discardLiveDanmakuPending() {
        liveDanmakuBuffer.discardPending();
        liveDanmakuBatcher.reset(liveDanmakuGeneration);
        clearLiveDanmakuRenderer(liveDanmakuGeneration);
    }

    private void scheduleLiveDanmakuMetrics() {
        if (!SpiderDebug.isEnabled()) return;
        App.post(liveDanmakuMetricsRunnable, LIVE_DANMAKU_METRICS_INTERVAL_MS);
    }

    private void logLiveDanmakuMetrics(String reason, boolean reschedule) {
        if (!SpiderDebug.isEnabled()) return;
        long nowMs = SystemClock.elapsedRealtime();
        LiveDanmakuMetrics.Snapshot metrics = liveDanmakuMetrics.snapshotAndReset(nowMs);
        LiveDanmakuBuffer.Snapshot buffer = liveDanmakuBuffer.snapshot();
        androidx.media3.ui.danmaku.DanmakuView.LiveStats render = danmakuController == null ? androidx.media3.ui.danmaku.DanmakuView.LiveStats.EMPTY : danmakuController.getLiveStats();
        SpiderDebug.log("danmaku-ws-metrics", "reason=%s state=%s stateMs=%d openMs=%d code=%d received=%d parsed=%d invalid=%d normal=%d super=%d online=%d queued=%d overflow=%d stale=%d batches=%d batchMessages=%d parseAvgUs=%d parseMaxUs=%d mainAvgMs=%d mainMaxMs=%d retries=%d appPending=%d/%d appExpired=%d appHigh=%d renderOffered=%d renderAdmitted=%d renderOverflow=%d renderExpired=%d trackWaits=%d renderPending=%d renderHigh=%d active=%d", reason, metrics.state(), metrics.stateDurationMs(), metrics.openDurationMs(), metrics.lastCode(), metrics.received(), metrics.parsed(), metrics.invalid(), metrics.normal(), metrics.superChat(), metrics.online(), metrics.queued(), metrics.overflow(), metrics.stale(), metrics.batches(), metrics.batchedMessages(), metrics.averageParseNanos() / 1000L, metrics.maxParseNanos() / 1000L, metrics.averageMainDelayMs(), metrics.maxMainDelayMs(), metrics.retryWaits(), buffer.normalPending(), buffer.priorityPending(), buffer.droppedExpired(), buffer.highWaterMark(), render.offered, render.admitted, render.droppedOverflow, render.droppedExpired, render.trackWaits, render.pending, render.highWaterMark, render.active);
        if (reschedule && liveDanmakuSession != null && liveDanmakuSession.state() != LiveDanmakuWebSocketSession.State.STOPPED && liveDanmakuSession.state() != LiveDanmakuWebSocketSession.State.RELEASED) scheduleLiveDanmakuMetrics();
    }

    private void logDanmakuLoad(String event, Uri uri, int count, IOException error) {
        if (!SpiderDebug.isEnabled()) return;
        long elapsed = danmakuLoadStartedAtMs <= 0 ? -1 : SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        if (error == null) {
            SpiderDebug.log("danmaku", "load %s count=%d elapsed=%dms %s", event, count, elapsed, DanmakuUrlPolicy.logSummary(uri == null ? "" : uri.toString()));
        } else {
            SpiderDebug.log("danmaku", "load %s elapsed=%dms %s error=%s", event, elapsed, DanmakuUrlPolicy.logSummary(uri == null ? "" : uri.toString()), error.getMessage());
        }
    }

    private static String normalizeDanmakuKey(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        try {
            Uri uri = Uri.parse(value);
            String nested = getNestedDanmakuUrl(uri);
            return TextUtils.isEmpty(nested) ? value : normalizeDanmakuKey(nested);
        } catch (Throwable e) {
            return value;
        }
    }

    private static String getNestedDanmakuUrl(Uri uri) {
        if (uri == null) return "";
        String path = uri.getPath();
        if (TextUtils.isEmpty(path) || !path.endsWith("/danmaku")) return "";
        return uri.getQueryParameter("url");
    }

    public void addDanmaku(Danmaku item) {
        if (danmakuController == null || item.isEmpty()) return;
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        playbackTrace.mark(PlaybackTrace.Stage.PARSE_COMPLETE, "headers=" + (headers == null ? 0 : headers.size()));
        PlaybackTrace.log("player", playbackTrace.current(), "parseSuccess from=%s url=%s headers=%s", from, summarizeUrl(url), headers == null ? 0 : headers.size());
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    @Override
    public void onParseError() {
        clearPendingSwitchRestore();
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    private String debugSpec() {
        if (spec == null) return "null";
        return "trace=" + playbackTrace.current() +
                ", keyLen=" + safeLength(spec.getKey()) +
                ", url=" + summarizeUrl(spec.getUrl()) +
                ", format=" + spec.getFormat() +
                ", headers=" + (spec.getHeaders() == null ? 0 : spec.getHeaders().size()) +
                ", subs=" + (spec.getSubs() == null ? 0 : spec.getSubs().size()) +
                ", danmakus=" + (spec.getDanmakus() == null ? 0 : spec.getDanmakus().size());
    }

    private void beginPlaybackTrace(String reason) {
        beginPlaybackTrace(reason, true);
    }

    private void beginPlaybackTrace(String reason, boolean finishCurrentSession) {
        if (finishCurrentSession) {
            endPlaybackTelemetrySession("replace-" + reason);
        }
        playbackBufferingTracker.reset();
        clearExoDecoderResourceRecovery(true);
        playbackTrace.begin();
        long now = SystemClock.elapsedRealtime();
        playbackAutoSession = playbackAutoContextStore.beginSession(playbackTrace.current(), now);
        rtspLiveLagController.beginSession(playbackAutoSession);
        playbackTrackSequence = 1;
        playbackMediaFactsCoordinator.beginSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().beginSession(playbackAutoSession);
        PlaybackSystemConditionMonitor.process().beginSession(playbackAutoSession);
        playbackTelemetryCoordinator.beginSession(playbackAutoSession, now);
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
        bindPlaybackTrace();
        playbackTrace.mark(PlaybackTrace.Stage.REQUEST, "reason=" + reason + " player=" + playerType + " decode=" + (engine == null ? -1 : engine.getDecode()));
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
    }

    private void bindPlaybackTrace() {
        if (spec != null) spec.setPlaybackTraceId(playbackTrace.current());
    }

    private PlaybackResourceClassifier.Classification currentResourceClassification() {
        PlaybackResourceClassifier.Classification request = PlaybackResourceClassifier.classifyRequest(
                spec == null ? null : spec.getUrl(),
                spec == null ? null : spec.getFormat(),
                spec == null ? null : spec.getFormat());
        PlaybackResourceClassifier.Classification observed =
                engine == null ? null : engine.getResourceClassification();
        return PlaybackResourceClassifier.merge(request, observed);
    }

    private void publishPlaybackAutoContext(boolean acceptDecoder) {
        if (spec == null || engine == null || !playbackAutoSession.active()) return;
        PlaybackResourceClassifier.Classification classification = currentResourceClassification();
        PlaybackRoute.Resolution observedRoute = engine.getEffectivePlaybackRoute();
        if (observedRoute == null || observedRoute.route() == PlaybackRoute.OTHER) observedRoute = spec.getPlaybackRoute();
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel = PlaybackAutoContext.Fact.forSession(
                playbackAutoKernel(playerType), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decode = PlaybackAutoContext.Fact.forSession(
                engine.isHard() ? PlaybackAutoContext.DecodeMode.HARDWARE : PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.PathFacts path = classification.toPathFacts(observedRoute, now);
        PlaybackAutoContext.ResourceFacts resource = classification.toResourceFacts(now);
        if (!playbackAutoContextStore.publishPlaybackFacts(playbackAutoSession, kernel, decode, resource, path, now)) return;
        try {
            playbackMediaFactsCoordinator.publishEngineFacts(
                    playbackAutoSession,
                    playbackTrackSequence,
                    engine.getPlaybackFactsSnapshot(),
                    acceptDecoder,
                    now);
        } catch (Throwable error) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(),
                    "media facts unavailable type=%s action=keep-partial", error.getClass().getSimpleName());
        }
        PlaybackAutoContext snapshot = playbackAutoContextStore.snapshot();
        if (playbackAutoSession.equals(snapshot.session())) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(), "%s", snapshot.logSummary());
            PlaybackTrace.log("playback-auto-resource", playbackTrace.current(), "%s", classification.logSummary());
        }
    }

    private void publishPlaybackTelemetryTick() {
        if (!playbackAutoSession.active()) return;
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
    }

    private void publishPlaybackTelemetry() {
        publishPlaybackTelemetry(null);
    }

    private void publishPlaybackTelemetry(PlaybackAutoContext.PlaybackPhase phaseOverride) {
        publishPlaybackTelemetry(phaseOverride, true);
    }

    private void publishPlaybackTelemetry(
            PlaybackAutoContext.PlaybackPhase phaseOverride,
            boolean evaluateMpvHlsVariant) {
        if (!playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation =
                collectPlaybackTelemetry(phaseOverride, now);
        playbackTelemetryCoordinator.publishRuntime(
                playbackAutoSession, observation, now);
        evaluateExoRtspLiveLag(observation, now);
        if (phaseOverride != PlaybackAutoContext.PlaybackPhase.ERROR) {
        }
    }

    private void schedulePlaybackTelemetry() {
        App.removeCallbacks(playbackTelemetryRunnable);
        if (!playbackAutoSession.active() || player == null || player.getPlaybackState() == Player.STATE_ENDED) return;
        App.post(playbackTelemetryRunnable, PLAYBACK_TELEMETRY_INTERVAL_MS);
    }

    private void endPlaybackTelemetrySession(String reason) {
        App.removeCallbacks(playbackTelemetryRunnable);
        if (!playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation =
                collectPlaybackTelemetry(null, now);
        playbackTelemetryCoordinator.endSession(
                playbackAutoSession, reason, observation, now);
        rtspLiveLagController.endSession(playbackAutoSession);
    }

    private PlaybackTelemetry.RuntimeObservation collectPlaybackTelemetry(
            PlaybackAutoContext.PlaybackPhase phaseOverride,
            long now) {
        PlaybackAutoContext.PlaybackPhase phaseValue = phaseOverride == null ? playbackPhaseSnapshot() : phaseOverride;
        PlaybackTelemetry.Metric<PlaybackAutoContext.PlaybackPhase> phase = phaseValue == PlaybackAutoContext.PlaybackPhase.UNKNOWN
                ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(phaseValue, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        PlaybackTelemetry.Metric<Boolean> loading = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> position = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> duration = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> buffered = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> bandwidth = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> mediaBitrate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Float> renderedFrameRate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> droppedFrames = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> liveLag = PlaybackTelemetry.Metric.unknown();
        if (player != null) {
            try {
                loading = PlaybackTelemetry.Metric.of(player.isLoading(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getCurrentPosition();
                if (value >= 0) position = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getDuration();
                if (value >= 0 && value != C.TIME_UNSET) duration = PlaybackTelemetry.Metric.of(value,
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getTotalBufferedDuration();
                if (value >= 0) buffered = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
        }
        if (isExo() && playbackTrace.current().equals(PlaybackAnalyticsListener.getPlaybackTraceId())) {
            PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
            if (analytics.bandwidthEstimate() > 0) {
                bandwidth = PlaybackTelemetry.Metric.of(analytics.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        PlaybackAutoContext.Confidence.MEDIUM);
            }
            PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media =
                    PlaybackAnalyticsListener.getDisplayMediaBitrateEstimate(getVideoFormat());
            if (media.bitrateBitsPerSecond() > 0) {
                PlaybackAutoContext.ValueSource source = "format".equals(media.source())
                        ? PlaybackAutoContext.ValueSource.PLAYER_CALLBACK : PlaybackAutoContext.ValueSource.ESTIMATOR;
                mediaBitrate = PlaybackTelemetry.Metric.of(media.bitrateBitsPerSecond(), source,
                        telemetryConfidence(media.confidence()));
            }
            PlaybackAnalyticsListener.DisplayFrameRateEstimate frameRate = PlaybackAnalyticsListener.getDisplayFrameRateEstimate();
            if (frameRate.frameRate() > 0 && frameRate.sampleCount() > 0) {
                renderedFrameRate = PlaybackTelemetry.Metric.of(frameRate.frameRate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        frameRate.sampleCount() >= 12 ? PlaybackAutoContext.Confidence.HIGH : PlaybackAutoContext.Confidence.MEDIUM);
            }
            if (analytics.everReady() || analytics.videoFormat() != null) {
                droppedFrames = PlaybackTelemetry.Metric.of(Math.max(0, analytics.droppedFrames()),
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            }
            try {
                long value = player.getCurrentLiveOffset();
                if (player.isCurrentMediaItemLive() && value >= 0 && value != C.TIME_UNSET) {
                    liveLag = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                            PlaybackAutoContext.Confidence.HIGH);
                }
            } catch (Throwable ignored) {
            }
        } else if (engine != null) {
            try {
                PlayerEngine.RuntimeMetrics metrics = engine.getRuntimeMetrics();
                if (metrics.bandwidthBitsPerSecond() != null) bandwidth = PlaybackTelemetry.Metric.of(
                        metrics.bandwidthBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.mediaBitrateBitsPerSecond() != null) mediaBitrate = PlaybackTelemetry.Metric.of(
                        metrics.mediaBitrateBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.renderedFrameRate() != null) renderedFrameRate = PlaybackTelemetry.Metric.of(
                        metrics.renderedFrameRate(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.droppedFrames() != null) droppedFrames = PlaybackTelemetry.Metric.of(
                        metrics.droppedFrames(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable error) {
                PlaybackTrace.log("playback-telemetry", playbackTrace.current(),
                        "native metrics unavailable type=%s action=keep-unknown", error.getClass().getSimpleName());
            }
        }
        long firstFrameMs = playbackTrace.stageElapsedMs(PlaybackTrace.Stage.FIRST_FRAME);
        PlaybackTelemetry.Metric<Long> firstFrame = firstFrameMs < 0 ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(firstFrameMs, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        return new PlaybackTelemetry.RuntimeObservation(
                phase,
                loading,
                position,
                duration,
                buffered,
                bandwidth,
                mediaBitrate,
                renderedFrameRate,
                droppedFrames,
                PlaybackTelemetry.Metric.of(getRebufferCount(),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                PlaybackTelemetry.Metric.of(getRebufferTotalMs(now),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                firstFrame,
                liveLag);
    }

    private void evaluateExoRtspLiveLag(
            PlaybackTelemetry.RuntimeObservation observation,
            long nowMs) {
        if (!playbackAutoSession.active()) return;
        boolean automatic = PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO);
        boolean exo = isExo() && engine instanceof ExoPlayerEngine;
        PlaybackResourceClassifier.Classification classification =
                currentResourceClassification();
        boolean rtsp = classification.protocol() == PlaybackAutoContext.Protocol.RTSP;
        boolean classifiedLive = classification.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || classification.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        boolean mediaItemLive = false;
        boolean active = false;
        boolean loading = false;
        boolean seekAvailable = false;
        if (player != null) {
            try {
                mediaItemLive = player.isCurrentMediaItemLive();
            } catch (Throwable ignored) {
            }
            try {
                int state = player.getPlaybackState();
                active = player.getPlayWhenReady()
                        && (player.isPlaying()
                        || state == Player.STATE_READY
                        || state == Player.STATE_BUFFERING);
            } catch (Throwable ignored) {
            }
            try {
                loading = player.isLoading();
            } catch (Throwable ignored) {
            }
            try {
                seekAvailable = player.isCommandAvailable(
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION);
            } catch (Throwable ignored) {
            }
        }
        boolean live = classifiedLive && mediaItemLive;
        boolean startupComplete = playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        long liveLagMs = metricLong(observation == null ? null : observation.liveLagMs());
        long bufferedMs = metricLong(observation == null ? null : observation.bufferedDurationMs());
        boolean liveEdgeReliable = isReliableDynamicLiveEdge(
                live, seekAvailable, liveLagMs);
        ExoRtspLiveLagPolicy.Decision decision = rtspLiveLagController.evaluate(
                new ExoRtspLiveLagController.Input(
                        playbackAutoSession,
                        automatic,
                        exo,
                        rtsp,
                        live,
                        active,
                        startupComplete,
                        false,
                        loading,
                        liveEdgeReliable,
                        seekAvailable,
                        liveLagMs,
                        bufferedMs,
                        nowMs));
        ExoRtspLiveLagController.Snapshot stateAtDecision =
                rtspLiveLagController.snapshot();

        // Do not fill the shared decision log with inapplicable HLS/DASH/native observations.
        if (!automatic || !exo || !rtsp) return;
        if (!decision.requestsRecovery()) {
            publishExoRtspLiveLagDecision(
                    decision,
                    PlaybackTelemetry.DecisionOutcome.HELD,
                    false,
                    active,
                    loading,
                    seekAvailable,
                    liveEdgeReliable,
                    stateAtDecision,
                    nowMs);
            return;
        }

        boolean started = rtspLiveLagController.beginAction(
                playbackAutoSession, decision.action(), nowMs);
        boolean succeeded = started && executeExoRtspLiveLagRecovery(decision.action());
        if (started) {
            rtspLiveLagController.completeAction(
                    playbackAutoSession, decision.action(), succeeded);
        }
        publishExoRtspLiveLagDecision(
                decision,
                succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : PlaybackTelemetry.DecisionOutcome.FAILED,
                succeeded,
                active,
                loading,
                seekAvailable,
                liveEdgeReliable,
                stateAtDecision,
                nowMs);
    }

    private boolean executeExoRtspLiveLagRecovery(
            ExoRtspLiveLagPolicy.Action action) {
        try {
            return switch (action) {
                case SEEK_LIVE_EDGE -> engine instanceof ExoPlayerEngine exo
                        && exo.recoverRtspLiveEdge();
                case REBUILD_SESSION -> restartExoRtspLiveSession();
                case HOLD -> false;
            };
        } catch (Throwable error) {
            PlaybackTrace.log("exo-rtsp-live", playbackTrace.current(),
                    "action=%s result=failed errorType=%s",
                    action.label(), error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean restartExoRtspLiveSession() {
        if (!(engine instanceof ExoPlayerEngine)
                || player == null
                || spec == null
                || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        prepareSeq++;
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        resetNetworkProtectionSession("rtsp-live-rebuild");
        setDanmakus(spec.getDanmakus());
        initTrack = false;
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        PlaybackTrace.log("exo-rtsp-live", playbackTrace.current(),
                "action=rebuild-session play=%s speed=%.3f repeat=%s",
                wasPlayWhenReady, speed, repeat);
        engine.restart(spec.checkUa(), C.TIME_UNSET, wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        callback.onPrepare();
        return true;
    }

    private boolean isReliableDynamicLiveEdge(
            boolean live,
            boolean seekAvailable,
            long liveLagMs) {
        if (!live || !seekAvailable || liveLagMs < 0 || player == null) return false;
        try {
            Timeline timeline = player.getCurrentTimeline();
            int index = player.getCurrentMediaItemIndex();
            if (timeline == null || timeline.isEmpty()
                    || index < 0 || index >= timeline.getWindowCount()) {
                return false;
            }
            Timeline.Window window = timeline.getWindow(index, new Timeline.Window());
            return window.isLive() && window.isDynamic;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void publishExoRtspLiveLagDecision(
            ExoRtspLiveLagPolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            boolean succeeded,
            boolean active,
            boolean loading,
            boolean seekAvailable,
            boolean liveEdgeReliable,
            ExoRtspLiveLagController.Snapshot stateAtDecision,
            long nowMs) {
        ExoRtspLiveLagController.Snapshot snapshot =
                rtspLiveLagController.snapshot();
        ExoRtspLiveLagController.Snapshot decisionState = stateAtDecision == null
                ? snapshot : stateAtDecision;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "trigger", decision.trigger().label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.liveLagMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("live_lag_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "live_lag_ms", decision.liveLagMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.lagGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("lag_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "lag_growth_msps", decision.lagGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decision.bufferedMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("buffered_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "buffered_ms", decision.bufferedMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.bufferGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("buffer_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "buffer_growth_msps", decision.bufferGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "active", active,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "loading", loading,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "edge_reliable", liveEdgeReliable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "seek_available", seekAvailable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", decisionState.consecutiveRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "recoveries", snapshot.recoveryAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.RTSP_LIVE_RECOVERY,
                        outcome,
                        decisionState.lastAction().label(),
                        decision.action().label(),
                        succeeded ? decision.action().label() : "hold",
                        decision.reason().label(),
                        decision.requestsRecovery() ? succeeded ? "none" : "action-failed"
                                : decision.reason().label(),
                        inputs),
                nowMs);
    }

    private static long metricLong(PlaybackTelemetry.Metric<Long> metric) {
        return metric == null || !metric.known() || metric.value() < 0
                ? -1 : metric.value();
    }

    private PlaybackAutoContext.PlaybackPhase playbackPhaseSnapshot() {
        if (player == null) return PlaybackAutoContext.PlaybackPhase.IDLE;
        return switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING -> PlaybackAutoContext.PlaybackPhase.BUFFERING;
            case Player.STATE_READY -> PlaybackAutoContext.PlaybackPhase.READY;
            case Player.STATE_ENDED -> PlaybackAutoContext.PlaybackPhase.ENDED;
            case Player.STATE_IDLE -> spec == null ? PlaybackAutoContext.PlaybackPhase.IDLE : PlaybackAutoContext.PlaybackPhase.PREPARING;
            default -> PlaybackAutoContext.PlaybackPhase.UNKNOWN;
        };
    }

    private static PlaybackAutoContext.Confidence telemetryConfidence(String value) {
        if (value == null) return PlaybackAutoContext.Confidence.UNKNOWN;
        return switch (value) {
            case "high" -> PlaybackAutoContext.Confidence.HIGH;
            case "medium" -> PlaybackAutoContext.Confidence.MEDIUM;
            case "low" -> PlaybackAutoContext.Confidence.LOW;
            default -> PlaybackAutoContext.Confidence.UNKNOWN;
        };
    }

    private void clearPlaybackAutoContext() {
        PlaybackSystemConditionMonitor.process().endSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().endSession(playbackAutoSession);
        playbackMediaFactsCoordinator.endSession(playbackAutoSession);
        playbackAutoContextStore.clear(playbackAutoSession);
        playbackAutoSession = PlaybackAutoContext.SessionToken.none();
        playbackTrackSequence = 0;
    }

    private static PlaybackAutoContext.Kernel playbackAutoKernel(int playerType) {
        return switch (PlayerSetting.sanitizePlayer(playerType)) {
            default -> PlaybackAutoContext.Kernel.EXO;
        };
    }

    private void logPlaybackRoute() {
        if (spec == null) return;
        String traceId = playbackTrace.current();
        if (traceId.equals(lastLoggedRouteTraceId)) return;
        PlaybackRoute.Resolution resolution = spec.getPlaybackRoute();
        PlaybackTrace.log("playback-route", traceId, "%s", resolution.logSummary());
        DiagnosticControls.forTrace(traceId, "input.route", "resolved-playback-route", e -> e
                .observed("inputRoute", resolution.route().name()).observed("source", resolution.owner().name())
                .observed("reason", resolution.evidence().name()).observed("input", resolution.logSummary())
                .observed("metricScope", "resolved local route; external proxy upstream is not observed"));
        lastLoggedRouteTraceId = traceId;
    }

    private static String summarizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return "scheme=" + (TextUtils.isEmpty(scheme) ? "unknown" : scheme)
                + " len=" + url.length();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private void markStartupCompletion(boolean ready, Tracks tracks) {
        if (tracks == null) return;
        boolean hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO);
        boolean hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO);
        boolean videoSubmitted = false;
        PlaybackStartupPolicy.Completion completion = PlaybackStartupPolicy.resolve(ready, videoSubmitted, hasVideo, hasAudio);
        if (completion == PlaybackStartupPolicy.Completion.FIRST_FRAME) {
            if (playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)) return;
            playbackTrace.mark(PlaybackTrace.Stage.FIRST_FRAME, "source=media3 player=" + playerType);
        } else if (completion == PlaybackStartupPolicy.Completion.AUDIO_PLAYABLE) {
            playbackTrace.mark(PlaybackTrace.Stage.AUDIO_PLAYABLE, "source=ready player=" + playerType);
        }
    }

    private void recordBufferingState(int state) {
        if (player == null) return;
        if (isExo()
                && state == Player.STATE_BUFFERING
                && !playbackBufferingTracker.isBuffering()
                && PlaybackAnalyticsListener.isSeekRecoveryActive()) {
            PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                    "event=excluded phase=seek outcome=user-action");
            return;
        }
        boolean startupComplete = playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME) || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        PlaybackBufferingTracker.Event event = playbackBufferingTracker.update(
                state == Player.STATE_BUFFERING,
                startupComplete,
                SystemClock.elapsedRealtime(),
                state,
                currentPositionSnapshot(),
                forwardBufferedSnapshot(),
                loadingSnapshot());
        if (event == null) return;
        PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                "event=%s phase=%s outcome=%s duration=%dms count=%d total=%dms position=%d forward=%d state=%s loading=%s",
                event.type().label(), event.phase().label(), bufferingOutcome(event), event.durationMs(), event.rebufferCount(), event.rebufferTotalMs(),
                event.positionMs(), event.forwardBufferedMs(), stateName(event.playbackState()), event.loading());
    }

    private long currentPositionSnapshot() {
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long forwardBufferedSnapshot() {
        try {
            return Math.max(0, player.getTotalBufferedDuration());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean loadingSnapshot() {
        try {
            return player.isLoading();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String bufferingOutcome(PlaybackBufferingTracker.Event event) {
        if (event.type() == PlaybackBufferingTracker.Type.START) return "-";
        return switch (event.playbackState()) {
            case Player.STATE_READY -> "ready";
            case Player.STATE_ENDED -> "ended";
            case Player.STATE_IDLE -> "idle";
            default -> "left-buffering";
        };
    }

    private static String trackSummary(Tracks tracks) {
        return "video=" + tracks.containsType(C.TRACK_TYPE_VIDEO) +
                " audio=" + tracks.containsType(C.TRACK_TYPE_AUDIO) +
                " text=" + tracks.containsType(C.TRACK_TYPE_TEXT) +
                " groups=" + tracks.getGroups().size();
    }

    private static String causeChain(Throwable error) {
        if (error == null) return "null";
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getName());
            current = current.getCause();
        }
        return builder.toString();
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onReload(String msg);

        default void onPlayerRenderRequired() {
        }

        default void onPlayerOutputPending() {
        }

        default void onPlayerOutputReady() {
        }

        default void onExoFirstFrame() {
        }

        void onPlayerRebuild(Player newPlayer, boolean resetVideoSurface);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            liveDanmakuPlaybackActive = isPlaying;
            if (!isPlaying) discardLiveDanmakuPending();
            if (isPlaying) scheduleNetworkProtection(0);
            else if (player.getPlaybackState() == Player.STATE_READY && !player.getPlayWhenReady()) {
                resetNetworkProtectionSession("paused");
            } else if (player.getPlaybackState() != Player.STATE_BUFFERING) {
                App.removeCallbacks(networkProtectionRunnable);
                networkProtectionController.disrupt(networkProtectionSpeed);
                networkProtectionTrend.reset();
                networkProtectionState = networkProtectionController.getState();
                networkProtectionTier = networkProtectionController.getTier();
            } else {
                // A rebuffer is exactly the evidence the guard needs after playback resumes.
                // Keep the current protection speed, controller history, and forward-buffer
                // trend instead of forcing another warm-up window on every stall.
                App.removeCallbacks(networkProtectionRunnable);
                networkProtectionReason = "buffering-hold";
            }
            publishPlaybackTelemetry();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "state=%s spec=%s", stateName(state), debugSpec());
            publishPlaybackAutoContext(state != Player.STATE_IDLE);
            if (state == Player.STATE_READY) {
                playbackTrace.mark(PlaybackTrace.Stage.READY, "player=" + playerType);
                markStartupCompletion(true, getCurrentTracks());
                hardDecodeSwitchRetryArmed = false;
                scheduleNetworkProtection(0);
            } else if (state == Player.STATE_BUFFERING) {
                App.removeCallbacks(networkProtectionRunnable);
                // Do not reset/disrupt the network guard here. BUFFERING is transient and
                // clearing its trend makes repeated stalls permanently outrun the 10 s
                // confirmation window. READY schedules an immediate evaluation using the
                // retained pre-stall trend plus the newly recorded rebuffer count.
                networkProtectionReason = "buffering-hold";
            } else {
                resetNetworkProtectionSession(state == Player.STATE_ENDED ? "ended" : "inactive");
            }
            recordBufferingState(state);
            publishPlaybackTelemetry();
            if (state == Player.STATE_ENDED) {
                App.removeCallbacks(playbackTelemetryRunnable);
            } else {
                schedulePlaybackTelemetry();
            }
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            if (isExo()) scheduleNetworkProtection(0);
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            rtspLiveLagController.onPositionDiscontinuity(playbackAutoSession);
            resetNetworkProtectionSession("discontinuity-" + reason);
            scheduleNetworkProtection(ExoNetworkGuardController.OBSERVE_INTERVAL_MS);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
            publishPlaybackAutoContext(true);
            publishPlaybackTelemetry();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (playbackTrackSequence < Long.MAX_VALUE) playbackTrackSequence++;
            publishPlaybackAutoContext(false);
            if (isExo()) scheduleNetworkProtection(0);
            if (!tracks.isEmpty() && !initTrack) {
                playbackTrace.mark(PlaybackTrace.Stage.TRACKS, trackSummary(tracks));
                restoreTrackSelection(Track.find(getKey()));
                callback.onTracksChanged();
                initTrack = true;
            }
            markStartupCompletion(player != null && player.getPlaybackState() == Player.STATE_READY, tracks);
            publishPlaybackTelemetry();
        }

        @Override
        public void onRenderedFirstFrame() {
            // A rendered video frame proves that the media and video decoder are
            // working even if a slow audio track keeps Exo in BUFFERING briefly.
            // Do not let the generic startup timer turn that valid playback into
            // a false connection-timeout error.
            if (isExo()) App.removeCallbacks(runnable);
            playbackTrace.mark(PlaybackTrace.Stage.FIRST_FRAME,
                    "source=media3" + " player=" + playerType);
            publishPlaybackAutoContext(true);
            publishPlaybackTelemetry();
            if (isExo()) callback.onExoFirstFrame();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onTitlesChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            App.removeCallbacks(runnable);
            App.removeCallbacks(networkProtectionRunnable);
            if (handleExoDecoderResourcesReclaimed(e)) return;
            rtspLiveLagController.onPlaybackError(playbackAutoSession);
            // Publish the failing runtime snapshot without letting the periodic
            // HLS timeout path start a rollback before this concrete error is
            // classified. A downgrade error gets exactly one rollback attempt;
            // an error from that rollback continues through the normal handler.
            publishPlaybackTelemetry(
                    PlaybackAutoContext.PlaybackPhase.ERROR, false);
            PlaybackErrorClassifier.Failure failure = PlaybackErrorClassifier.classify(e, getEffectivePlaybackRoute());
            PlayerEngine.ErrorAction action = engine.handleError(e);
            PlaybackTrace.log("playback-error", playbackTrace.current(), "%s action=%s player=%d decode=%d", failure.logSummary(), action, playerType, engine.getDecode());
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "error code=%d errorType=%s action=%s retry=%d causeTypes=%s", e.errorCode, e.getClass().getSimpleName(), action, retry, causeChain(e));
            LocalProxyDebug.dumpIfLocalFailure(spec == null ? null : spec.getUrl(), e);
            boolean decoderRuntimeObserved = action == PlayerEngine.ErrorAction.DECODE
                    && engine instanceof ExoPlayerEngine exo
                    && exo.observeDecoderRuntimeFailure(e);
            if (action == PlayerEngine.ErrorAction.DECODE && retryExoTunnelingFailure(e)) return;
            if (decoderRuntimeObserved && retryExoDecoderRuntimeFailure(e)) return;
            if (action == PlayerEngine.ErrorAction.DECODE && retryHardDecodeSwitch(e)) return;
            if (action == PlayerEngine.ErrorAction.FATAL && retryLocalProxy(e)) return;
                if (action == PlayerEngine.ErrorAction.RELOAD) {
                callback.onReload(getPlaybackErrorMessage(failure));
                return;
            }
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                if (spec != null) setDanmakus(spec.getDanmakus());
                return;
            }
            callback.onError(getPlaybackErrorMessage(failure));
        }
    };

    private record ExoDecoderResourceRecovery(
            PlaySpec target,
            long positionMs,
            float speed,
            boolean repeat,
            boolean playWhenReady,
            long textOffsetMs,
            long audioOffsetMs) {
    }

    private PlaybackRoute.Resolution getEffectivePlaybackRoute() {
        PlaybackRoute.Resolution route = engine == null ? null : engine.getEffectivePlaybackRoute();
        if (route != null && route.route() != PlaybackRoute.OTHER) return route;
        return spec == null ? PlaybackRoute.resolve(null) : spec.getPlaybackRoute();
    }

    private String getPlaybackErrorMessage(PlaybackErrorClassifier.Failure failure) {
        return switch (failure.stage()) {
            case LOCAL_ENDPOINT -> switch (failure.route().owner()) {
                case APP_MAIN_SERVER, APP_HLS_PROXY -> ResUtil.getString(R.string.error_play_stage_app_local);
                default -> ResUtil.getString(R.string.error_play_stage_external_local);
            };
            case NETWORK_IO -> PlaybackRouteCapabilities.resolve(failure.route()).externalUpstreamOpaque()
                    ? ResUtil.getString(R.string.error_play_stage_external_supply)
                    : ResUtil.getString(R.string.error_play_stage_network);
            case MEDIA_PARSING -> ResUtil.getString(R.string.error_play_stage_media);
            case DECODER -> ResUtil.getString(R.string.error_play_stage_decoder);
            case OUTPUT -> isAudioOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_audio_output)
                    : isVideoOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_video_output)
                    : ResUtil.getString(R.string.error_play_stage_output);
            case DRM -> ResUtil.getString(R.string.error_play_stage_drm);
            case UNKNOWN -> ResUtil.getString(R.string.error_play_stage_unknown);
        };
    }

    private boolean isAudioOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED);
    }

    private boolean isVideoOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    }

    private boolean hasErrorCode(
            PlaybackErrorClassifier.Failure failure, int errorCode) {
        return PlaybackException.getErrorCodeName(errorCode).equals(failure.errorCode());
    }

    private boolean handleExoDecoderResourcesReclaimed(
            PlaybackException error) {
        if (error.errorCode
                != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            return false;
        }
        if (pendingExoDecoderResourceRecovery != null
                || exoDecoderResourceRecoveryInProgress) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=suppress-duplicate pending=%s inProgress=%s",
                    pendingExoDecoderResourceRecovery != null,
                    exoDecoderResourceRecoveryInProgress);
            return true;
        }
        ExoDecoderResourceRecovery recovery =
                captureExoDecoderResourceRecovery();
        ExoDecoderResourceRecoveryLimiter.Action action =
                exoDecoderResourceRecoveryLimiter.request(
                        error.errorCode,
                        engine instanceof ExoPlayerEngine,
                        engine != null && player != null,
                        recovery != null,
                        playbackForeground);
        if (action
                == ExoDecoderResourceRecoveryLimiter.Action.DEFER_UNTIL_FOREGROUND) {
            hardDecodeSwitchRetryArmed = false;
            pendingExoDecoderResourceRecovery = recovery;
            App.removeCallbacks(playbackTelemetryRunnable);
            resetNetworkProtectionSession("exo-resource-reclaimed-deferred");
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=defer position=%d play=%s",
                    recovery.positionMs(),
                    recovery.playWhenReady());
            return true;
        }
        if (action
                != ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=pass-through reason=%s",
                    action);
            return false;
        }
        hardDecodeSwitchRetryArmed = false;
        scheduleExoDecoderResourceRecovery(recovery, "foreground-error");
        return true;
    }

    @Nullable
    private ExoDecoderResourceRecovery captureExoDecoderResourceRecovery() {
        if (!(engine instanceof ExoPlayerEngine)
                || player == null
                || spec == null
                || spec.getUrl() == null) {
            return null;
        }
        return new ExoDecoderResourceRecovery(
                spec,
                Math.max(0, getPosition()),
                getSpeed(),
                isRepeatOne(),
                player.getPlayWhenReady(),
                getTextOffsetMs(),
                getAudioOffsetMs());
    }

    private void scheduleExoDecoderResourceRecovery(
            ExoDecoderResourceRecovery recovery,
            String reason) {
        if (recovery == null) return;
        if (!playbackForeground) {
            pendingExoDecoderResourceRecovery = recovery;
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=defer-before-rebuild reason=%s",
                    reason);
            return;
        }
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec != recovery.target()) {
            return;
        }
        exoDecoderResourceRecoveryInProgress = true;
        int seq = ++prepareSeq;
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        App.removeCallbacks(playbackTelemetryRunnable);
        resetNetworkProtectionSession("exo-resource-reclaimed");
        rebuildPlayer(true);
        this.playWhenReady = recovery.playWhenReady();
        initTrack = false;
        PlaybackTrace.log(
                "exo-decoder-resource",
                playbackTrace.current(),
                "action=rebuild delay=%d position=%d play=%s reason=%s",
                EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS,
                recovery.positionMs(),
                recovery.playWhenReady(),
                reason);
        App.post(() -> {
            if (seq != prepareSeq
                    || spec != recovery.target()
                    || engine != exo
                    || player == null) {
                exoDecoderResourceRecoveryInProgress = false;
                return;
            }
            if (!playbackForeground) {
                pendingExoDecoderResourceRecovery = recovery;
                exoDecoderResourceRecoveryInProgress = false;
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        playbackTrace.current(),
                        "action=defer-before-prepare");
                return;
            }
            try {
                setDanmakus(recovery.target().getDanmakus());
                applySubtitleStyle();
                engine.start(
                        recovery.target().checkUa(),
                        recovery.positionMs(),
                        recovery.playWhenReady());
                setSpeed(recovery.speed());
                setRepeatOne(recovery.repeat());
                setTextOffsetMs(recovery.textOffsetMs());
                setAudioOffsetMs(recovery.audioOffsetMs());
                App.post(runnable, Constant.TIMEOUT_PLAY);
                callback.onPrepare();
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        playbackTrace.current(),
                        "action=prepare position=%d play=%s",
                        recovery.positionMs(),
                        recovery.playWhenReady());
            } finally {
                exoDecoderResourceRecoveryInProgress = false;
            }
        }, EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS);
    }

    private void clearExoDecoderResourceRecovery(boolean resetBudget) {
        pendingExoDecoderResourceRecovery = null;
        exoDecoderResourceRecoveryInProgress = false;
        if (resetBudget) exoDecoderResourceRecoveryLimiter.reset();
    }

    private boolean retryHardDecodeSwitch(PlaybackException e) {
        if (!hardDecodeSwitchRetryArmed || engine == null || player == null || spec == null || !engine.isHard()) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        hardDecodeSwitchRetryArmed = false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        resetVideoEffectsState();
        engine.release();
        engine = buildEngine(playerType, PlayerEngine.HARD);
        player = engine.getPlayer();
        callback.onPlayerRebuild(player, true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry delay=%d position=%d errorType=%s", HARD_DECODE_SWITCH_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine == null || player == null || !engine.isHard()) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry start position=%d", position);
            setDanmakus(target.getDanmakus());
            initTrack = false;
            applySubtitleStyle();
            engine.start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, HARD_DECODE_SWITCH_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoTunnelingFailure(PlaybackException e) {
        if (!(engine instanceof ExoPlayerEngine exo) || player == null || spec == null) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        if (!exo.disableTunnelingForSession()) return false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback scheduled delay=%d position=%d errorType=%s", EXO_TUNNELING_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback start position=%d", position);
            setDanmakus(target.getDanmakus());
            applySubtitleStyle();
            engine.start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, EXO_TUNNELING_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDecoderRuntimeFailure(PlaybackException e) {
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null) {
            return false;
        }
        boolean dolbyVisionFallback =
                exo.isDolbyVisionP81RuntimeFailurePending();
        if (!exo.prepareDecoderRuntimeFallback()) {
            return false;
        }
        hardDecodeSwitchRetryArmed = false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-decoder-profile",
                    "action=retry-scheduled delay=%d position=%d errorType=%s",
                    EXO_DECODER_RUNTIME_RETRY_DELAY_MS,
                    position,
                    e.getClass().getSimpleName());
        }
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-decoder-profile",
                        "action=retry-start position=%d",
                        position);
            }
            setDanmakus(target.getDanmakus());
            applySubtitleStyle();
            engine.start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, EXO_DECODER_RUNTIME_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDv7FirstFrameTimeout() {
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null
                || !exo.prepareDv7Hdr10FallbackForFirstFrameTimeout()) {
            return false;
        }
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-dv",
                    "action=first-frame-timeout-fallback-scheduled delay=%d position=%d",
                    EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS,
                    position);
        }
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            setDanmakus(target.getDanmakus());
            applySubtitleStyle();
            engine.start(target.checkUa(), position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-dv",
                        "action=first-frame-timeout-fallback-start position=%d",
                        position);
            }
        }, EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS);
        return true;
    }

    private boolean retryLocalProxy(PlaybackException e) {
        if (spec == null || !LocalProxyDebug.isLocalProxyUrl(spec.getUrl())) return false;
        if (!LocalProxyDebug.isConnectionRefused(e)) return false;
        if (++localProxyRetry > LOCAL_PROXY_MAX_RETRY) return false;
        int attempt = localProxyRetry;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry schedule attempt=%d delay=%d spec=%s", attempt, LOCAL_PROXY_RETRY_DELAY_MS, debugSpec());
        App.removeCallbacks(runnable);
        App.post(() -> {
            if (spec == null || attempt != localProxyRetry) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry start attempt=%d spec=%s", attempt, debugSpec());
            setMediaItem();
        }, LOCAL_PROXY_RETRY_DELAY_MS);
        return true;
    }

}
