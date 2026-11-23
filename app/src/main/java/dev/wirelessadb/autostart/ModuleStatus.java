package dev.wirelessadb.autostart;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects whether the Xposed / LSPosed / Vector module is active in both the app process
 * (standard self-hook) and {@code system_server} (async ping with timeout + boot-count marker).
 *
 * <p>All IPC / Settings checks run asynchronously with bounded timeouts so the main thread
 * never blocks or ANRs when LSPosed / Vector is disabled (e.g., Magisk safe mode).
 */
public final class ModuleStatus {
    public static final String ACTION_PING = "dev.wirelessadb.autostart.PING";
    public static final String EXTRA_PING_NONCE = "ping_nonce";
    public static final String EXTRA_HOOK_ARMED = "hook_armed";
    public static final String EXTRA_USER_UNLOCKED = "user_unlocked";
    public static final String EXTRA_HOOK_VERSION = "hook_version";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TCP_PORT = "tcp_port";
    public static final String EXTRA_BOOT_COUNT = "boot_count";
    public static final String PING_PONG = "wadb_pong";
    public static final String GLOBAL_HOOK_BOOT = "wirelessadb_autostart_hook_boot";
    public static final int HOOK_PROTOCOL_VERSION = 2;
    public static final long PING_TIMEOUT_MS = 1_500L;

    public enum State {
        CHECKING,
        ACTIVE,
        SCOPE_OR_REBOOT_REQUIRED,
        INACTIVE
    }

    public static final class Result {
        public final State state;
        public final boolean selfHooked;
        public final int xposedApiVersion;
        public final boolean systemHookAlive;
        public final boolean bootMarkerMatched;
        public final boolean pingTimedOut;
        public final boolean userUnlocked;
        public final int hookVersion;
        public final long latencyMs;

        public Result(State state, boolean selfHooked, int xposedApiVersion,
                      boolean systemHookAlive, boolean bootMarkerMatched,
                      boolean pingTimedOut, boolean userUnlocked,
                      int hookVersion, long latencyMs) {
            this.state = state;
            this.selfHooked = selfHooked;
            this.xposedApiVersion = xposedApiVersion;
            this.systemHookAlive = systemHookAlive;
            this.bootMarkerMatched = bootMarkerMatched;
            this.pingTimedOut = pingTimedOut;
            this.userUnlocked = userUnlocked;
            this.hookVersion = hookVersion;
            this.latencyMs = latencyMs;
        }

        public boolean isActive() {
            return state == State.ACTIVE;
        }

        public boolean isInactive() {
            return state == State.INACTIVE || state == State.SCOPE_OR_REBOOT_REQUIRED;
        }

        public String diagnosticSummary() {
            String selfText = selfHooked
                    ? (xposedApiVersion > 0 ? ("已注入 (API " + xposedApiVersion + ")") : "已注入")
                    : "未注入";
            String sysText;
            if (systemHookAlive) {
                sysText = "正常响应 (" + Math.max(0L, latencyMs) + "ms)";
            } else if (bootMarkerMatched && pingTimedOut) {
                sysText = "已加载（探测超时）";
            } else if (pingTimedOut) {
                sysText = "响应超时";
            } else {
                sysText = "无响应";
            }
            return "自检 Hook：" + selfText + " · 系统框架：" + sysText;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    // Volatile backing prevents compiler/ART constant-folding so Xposed can reliably replace these methods.
    private static volatile boolean selfHookFallback = false;
    private static volatile int xposedApiVersionFallback = -1;
    private static final AtomicLong PING_SEQ = new AtomicLong(1L);

    private ModuleStatus() {}

    /**
     * Standard Xposed / LSPosed self-activation probe.
     * {@link WirelessAdbHook} replaces this method in the module's own process to return {@code true}.
     */
    public static boolean isSelfHooked() {
        return selfHookFallback;
    }

    /**
     * Replaced by {@link WirelessAdbHook} in the module's own process to return
     * {@code XposedBridge.getXposedVersion()}.
     */
    public static int getXposedApiVersion() {
        return xposedApiVersionFallback;
    }

    /** Reads Android's monotonic boot counter from {@link Settings.Global#BOOT_COUNT}. */
    public static int readBootCount(Context context) {
        if (context == null) return -1;
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Called inside {@code system_server} when command receivers are registered. */
    public static boolean writeBootMarker(Context systemContext, int hookProtocolVersion) {
        if (systemContext == null) return false;
        try {
            int bootCount = readBootCount(systemContext);
            if (bootCount <= 0) return false;
            return Settings.Global.putString(
                    systemContext.getContentResolver(),
                    GLOBAL_HOOK_BOOT,
                    bootCount + ":" + hookProtocolVersion);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns true only if the hook boot marker in Settings.Global matches the current boot count. */
    public static boolean isBootMarkerCurrent(Context context) {
        if (context == null) return false;
        try {
            int currentBoot = readBootCount(context);
            if (currentBoot <= 0) return false;
            String raw = Settings.Global.getString(context.getContentResolver(), GLOBAL_HOOK_BOOT);
            return matchesBootMarker(raw, currentBoot);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean matchesBootMarker(String raw, int currentBootCount) {
        if (raw == null || currentBootCount <= 0) return false;
        int colon = raw.indexOf(':');
        String bootPart = colon >= 0 ? raw.substring(0, colon) : raw;
        try {
            return Integer.parseInt(bootPart.trim()) == currentBootCount;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** Pure evaluation helper mapping raw probe signals to a deterministic activation state. */
    static Result evaluate(boolean selfHooked, int xposedApiVersion,
                           boolean pingResponded, boolean bootMarkerMatched,
                           boolean pingTimedOut, boolean userUnlocked,
                           int hookVersion, long latencyMs) {
        State state;
        if (pingResponded) {
            state = State.ACTIVE;
        } else if (pingTimedOut && bootMarkerMatched) {
            state = State.ACTIVE;
        } else if (selfHooked) {
            state = State.SCOPE_OR_REBOOT_REQUIRED;
        } else {
            state = State.INACTIVE;
        }
        return new Result(state, selfHooked, xposedApiVersion,
                pingResponded, bootMarkerMatched, pingTimedOut,
                userUnlocked, hookVersion, latencyMs);
    }

    /**
     * Performs a non-blocking activation probe off the main thread and delivers the result on
     * {@code callbackHandler} within at most {@link #PING_TIMEOUT_MS}.
     */
    public static void probeAsync(Context context, Executor ioExecutor,
                                  Handler callbackHandler, Callback callback) {
        final Context appContext = context.getApplicationContext();
        final long startMs = SystemClock.elapsedRealtime();

        try {
            ioExecutor.execute(() -> {
                final boolean selfHooked = isSelfHooked();
                final int apiVersion = getXposedApiVersion();
                final boolean bootMarkerMatched = isBootMarkerCurrent(appContext);
                final String nonce = PING_SEQ.getAndIncrement() + "-" + System.nanoTime();
                final AtomicBoolean completed = new AtomicBoolean(false);

                final Runnable timeoutTask = () -> {
                    if (!completed.compareAndSet(false, true)) return;
                    long elapsed = SystemClock.elapsedRealtime() - startMs;
                    Result res = evaluate(selfHooked, apiVersion,
                            false, bootMarkerMatched, true, false, -1, elapsed);
                    callback.onResult(res);
                };

                callbackHandler.postDelayed(timeoutTask, PING_TIMEOUT_MS);

                BroadcastReceiver resultReceiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
                        callbackHandler.removeCallbacks(timeoutTask);
                        if (!completed.compareAndSet(false, true)) return;
                        long elapsed = SystemClock.elapsedRealtime() - startMs;
                        boolean pong = false;
                        boolean unlocked = false;
                        int hookVer = -1;
                        try {
                            int code = getResultCode();
                            String data = getResultData();
                            Bundle extras = getResultExtras(false);
                            if (code == Activity.RESULT_OK
                                    && PING_PONG.equals(data)
                                    && extras != null
                                    && nonce.equals(extras.getString(EXTRA_PING_NONCE))) {
                                pong = extras.getBoolean(EXTRA_HOOK_ARMED, true);
                                unlocked = extras.getBoolean(EXTRA_USER_UNLOCKED, false);
                                hookVer = extras.getInt(EXTRA_HOOK_VERSION, -1);
                            }
                        } catch (Throwable ignored) { }
                        Result res = evaluate(selfHooked, apiVersion,
                                pong, bootMarkerMatched, false, unlocked, hookVer, elapsed);
                        callback.onResult(res);
                    }
                };

                try {
                    Intent ping = new Intent(ACTION_PING)
                            .setPackage("android")
                            .putExtra(EXTRA_PING_NONCE, nonce);
                    appContext.sendOrderedBroadcast(
                            ping,
                            null,
                            resultReceiver,
                            callbackHandler,
                            Activity.RESULT_CANCELED,
                            null,
                            null);
                } catch (Throwable ignored) {
                    callbackHandler.removeCallbacks(timeoutTask);
                    if (completed.compareAndSet(false, true)) {
                        long elapsed = SystemClock.elapsedRealtime() - startMs;
                        Result res = evaluate(selfHooked, apiVersion,
                                false, bootMarkerMatched, false, false, -1, elapsed);
                        callbackHandler.post(() -> callback.onResult(res));
                    }
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }
}
