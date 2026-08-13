package dev.wirelessadb.autostart;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.ActivityManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class WirelessAdbHook implements IXposedHookLoadPackage {
    private static final String ACTION_LOG = "dev.wirelessadb.autostart.LOG_EVENT";
    private static final String ACTION_REQUEST_COPY = "dev.wirelessadb.autostart.REQUEST_COPY";
    private static final String ACTION_SET_MODE = "dev.wirelessadb.autostart.SET_MODE";
    private static final String ACTION_APPLY = "dev.wirelessadb.autostart.APPLY";
    private static final String LOG_RECEIVER = "dev.wirelessadb.autostart.LogReceiver";
    private static final String MODULE_PACKAGE = "dev.wirelessadb.autostart";
    private static final String WETYPE_PACKAGE = "com.tencent.wetype";
    private static final String SETTING_ADB_WIFI = "adb_wifi_enabled";
    private static final String PROP_TLS_PORT = "service.adb.tls.port";
    private static final String PROP_TCP_PORT = "service.adb.tcp.port";
    private static final long REENABLE_COOLDOWN_MS = 8_000L;
    private static final long WIFI_RESTORE_DELAY_MS = 2_500L;
    private static final long ADDRESS_POLL_INTERVAL_MS = 2_500L;
    private static final int ADDRESS_POLL_MAX = 12;

    private static Context systemContext;
    private static Handler handler;
    private static boolean armed;
    private static boolean keepersRegistered;
    private static boolean userUnlocked;
    private static String pendingAddress;
    private static boolean copyScheduled;
    private static String lastCopiedAddress;
    private static long lastEnableAt;
    private static final AtomicBoolean enableInFlight = new AtomicBoolean(false);

    /** Cached mode; refreshed from prefs on unlock / SET_MODE. */
    private static String cachedMode = AdbModeConfig.MODE_TLS;
    private static int cachedTcpPort = AdbModeConfig.DEFAULT_TCP_PORT;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName) || !"android".equals(lpparam.processName)) return;
        handler = new Handler(Looper.getMainLooper());
        try {
            Class<?> ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", lpparam.classLoader);
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (armed) return;
                    armed = true;
                    try { systemContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext"); }
                    catch (Throwable t) { log("获取系统上下文失败：" + shortError(t)); return; }
                    registerCommandReceivers();
                    waitForUnlock();
                }
            });
            hookWeTypeProcessStart(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("WirelessAdbAutoStart hook failed: " + t);
        }
    }

    private static void registerCommandReceivers() {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    handler.post(() -> {
                        if (ACTION_REQUEST_COPY.equals(action)) {
                            manualCopyAddress();
                        } else if (ACTION_SET_MODE.equals(action)) {
                            onSetMode(intent);
                        } else if (ACTION_APPLY.equals(action)) {
                            reloadConfig();
                            enableAdb("界面请求立即应用", true);
                        }
                    });
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_REQUEST_COPY);
            filter.addAction(ACTION_SET_MODE);
            filter.addAction(ACTION_APPLY);
            systemContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            log("Command receivers ready (copy / set_mode / apply)");
        } catch (Throwable t) {
            log("Command receiver failed: " + shortError(t));
        }
    }

    private static void onSetMode(Intent intent) {
        String mode = intent.getStringExtra("mode");
        int port = intent.getIntExtra("port", cachedTcpPort);
        if (mode != null && !mode.isEmpty()) {
            cachedMode = AdbModeConfig.MODE_TCP.equals(mode) ? AdbModeConfig.MODE_TCP : AdbModeConfig.MODE_TLS;
            if (port >= 1 && port <= 65535) cachedTcpPort = port;
            AdbModeConfig.setModeAsSystem(systemContext, cachedMode, cachedTcpPort);
            try {
                Context app = moduleContext();
                if (app != null) AdbModeConfig.setMode(app, cachedMode, cachedTcpPort);
            } catch (Throwable ignored) { }
        } else {
            reloadConfig();
        }
        lastCopiedAddress = null;
        lastEnableAt = 0L;
        log("切换模式 → " + AdbModeConfig.modeLabel(cachedMode)
                + (AdbModeConfig.MODE_TCP.equals(cachedMode) ? ("，端口 " + cachedTcpPort) : ""));
        if (userUnlocked) enableAdb("切换模式", true);
        else log("模式已保存，解锁后生效");
    }

    private static void reloadConfig() {
        try {
            // Settings.Global first (system always can read); fallback module prefs.
            String globalMode = null;
            try {
                globalMode = Settings.Global.getString(systemContext.getContentResolver(), AdbModeConfig.GLOBAL_MODE);
            } catch (Throwable ignored) { }
            if (AdbModeConfig.MODE_TCP.equals(globalMode) || AdbModeConfig.MODE_TLS.equals(globalMode)) {
                cachedMode = globalMode;
            } else {
                Context app = moduleContext();
                if (app != null) cachedMode = AdbModeConfig.getMode(app);
            }
            int globalPort = -1;
            try {
                globalPort = Settings.Global.getInt(systemContext.getContentResolver(),
                        AdbModeConfig.GLOBAL_TCP_PORT, -1);
            } catch (Throwable ignored) { }
            if (globalPort >= 1 && globalPort <= 65535) {
                cachedTcpPort = globalPort;
            } else {
                Context app = moduleContext();
                if (app != null) cachedTcpPort = AdbModeConfig.getTcpPort(app);
            }
            log("当前模式：" + AdbModeConfig.modeLabel(cachedMode)
                    + (AdbModeConfig.MODE_TCP.equals(cachedMode) ? (" @" + cachedTcpPort) : ""));
        } catch (Throwable t) {
            log("读取配置失败：" + shortError(t));
        }
    }

    private static Context moduleContext() {
        try {
            return systemContext.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void manualCopyAddress() {
        reloadConfig();
        String ip = findWifiIpv4();
        int port = resolveActivePort();
        if (ip == null || port <= 0) {
            log("Manual copy failed: mode=" + cachedMode + ", IP=" + ip + ", port=" + port);
            return;
        }
        pendingAddress = null;
        copyScheduled = false;
        String address = ip + ":" + port;
        log("Manual copy requested (immediate): " + address + " [" + cachedMode + "]");
        copyAddress(address);
    }

    private static void hookWeTypeProcessStart(ClassLoader loader) {
        try {
            Class<?> processList = XposedHelpers.findClass("com.android.server.am.ProcessList", loader);
            XposedBridge.hookAllMethods(processList, "handleProcessStartedLocked", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (pendingAddress == null || copyScheduled) return;
                    for (Object arg : param.args) {
                        if (isWeTypeProcess(arg)) {
                            scheduleCopyAfterWeTypeReady("检测到微信输入法进程启动");
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("WirelessAdbAutoStart WeType hook failed: " + t);
        }
    }

    private static boolean isWeTypeProcess(Object value) {
        if (value == null || !value.getClass().getName().endsWith(".ProcessRecord")) return false;
        try {
            Object info = XposedHelpers.getObjectField(value, "info");
            return WETYPE_PACKAGE.equals(XposedHelpers.getObjectField(info, "packageName"));
        } catch (Throwable ignored) {
            try {
                Object name = XposedHelpers.getObjectField(value, "processName");
                return name != null && name.toString().startsWith(WETYPE_PACKAGE);
            } catch (Throwable ignoredAgain) { return false; }
        }
    }

    private static void waitForUnlock() {
        try {
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    onUserUnlocked("收到 USER_UNLOCKED");
                }
            }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            UserManager users = systemContext.getSystemService(UserManager.class);
            if (users != null && users.isUserUnlocked()) onUserUnlocked("系统已解锁");
            else log("系统已启动，等待首次解锁");
        } catch (Throwable t) {
            log("监听解锁失败：" + shortError(t));
        }
    }

    private static void onUserUnlocked(String reason) {
        if (userUnlocked) return;
        userUnlocked = true;
        reloadConfig();
        log(reason + "，开始保持 ADB（" + AdbModeConfig.modeLabel(cachedMode) + "）");
        registerKeepers();
        enableAdb("首次解锁", true);
    }

    private static void registerKeepers() {
        if (keepersRegistered || systemContext == null || handler == null) return;
        keepersRegistered = true;
        registerAdbWifiObserver();
        registerWifiCallback();
        registerScreenOnReceiver();
    }

    private static void registerAdbWifiObserver() {
        try {
            Uri uri = Settings.Global.getUriFor(SETTING_ADB_WIFI);
            systemContext.getContentResolver().registerContentObserver(uri, false, new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    handler.post(() -> {
                        if (!AdbModeConfig.MODE_TLS.equals(cachedMode)) return;
                        int value = getAdbWifiEnabled();
                        if (value == 1) {
                            checkAddress("开关变为开启", 0, false);
                            return;
                        }
                        if (value == 0) {
                            log("检测到无线调试被关闭，准备重新开启");
                            enableAdb("ContentObserver", false);
                        }
                    });
                }
            });
            log("已注册 adb_wifi_enabled 监听（仅 TLS 模式生效）");
        } catch (Throwable t) {
            log("注册无线调试开关监听失败：" + shortError(t));
        }
    }

    private static void registerWifiCallback() {
        try {
            ConnectivityManager cm = systemContext.getSystemService(ConnectivityManager.class);
            if (cm == null) throw new IllegalStateException("ConnectivityManager 不可用");
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    handler.postDelayed(() -> {
                        if (!userUnlocked) return;
                        log("Wi-Fi 可用，检查 ADB（" + cachedMode + "）");
                        keepAlive("Wi-Fi 恢复");
                    }, WIFI_RESTORE_DELAY_MS);
                }

                @Override public void onLost(Network network) {
                    handler.post(() -> log("Wi-Fi 断开，等待恢复后再保持 ADB"));
                }
            });
            log("已注册 Wi-Fi 网络回调");
        } catch (Throwable t) {
            log("注册 Wi-Fi 回调失败：" + shortError(t));
        }
    }

    private static void registerScreenOnReceiver() {
        try {
            systemContext.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!userUnlocked) return;
                    handler.postDelayed(() -> keepAlive("亮屏检查"), 1_000L);
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON));
            log("已注册亮屏检查");
        } catch (Throwable t) {
            log("注册亮屏检查失败：" + shortError(t));
        }
    }

    private static void keepAlive(String reason) {
        if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
            if (!isTcpPortReady(cachedTcpPort)) {
                enableAdb(reason, false);
            } else {
                checkAddress(reason, 0, false);
            }
            return;
        }
        if (getAdbWifiEnabled() != 1) {
            enableAdb(reason, false);
        } else {
            checkAddress(reason, 0, false);
        }
    }

    private static void enableAdb(String reason, boolean forceCopy) {
        if (systemContext == null || handler == null) return;
        long now = System.currentTimeMillis();
        if (!forceCopy && now - lastEnableAt < REENABLE_COOLDOWN_MS) {
            log("跳过重复开启（冷却中，原因：" + reason + "）");
            return;
        }
        if (!enableInFlight.compareAndSet(false, true)) {
            log("开启进行中，跳过：" + reason);
            return;
        }
        lastEnableAt = now;
        try {
            if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
                enableTcpMode(reason, forceCopy);
            } else {
                enableTlsMode(reason, forceCopy);
            }
        } catch (Throwable t) {
            log("开启 ADB 失败：" + shortError(t));
        } finally {
            enableInFlight.set(false);
        }
    }

    private static void enableTlsMode(String reason, boolean forceCopy) {
        // Leaving classic TCP avoids two listeners confusing users; optional clear.
        try {
            String currentTcp = getSystemProperty(PROP_TCP_PORT, "");
            if (currentTcp != null && !currentTcp.isEmpty() && !"-1".equals(currentTcp) && !"0".equals(currentTcp)) {
                setSystemPropertyWithRootFallback(PROP_TCP_PORT, "-1");
                log("TLS 模式：已清除 service.adb.tcp.port（原 " + currentTcp + "）");
                restartAdbd("切回 TLS");
            }
        } catch (Throwable t) {
            log("清除 TCP 端口时出错（可忽略）：" + shortError(t));
        }
        boolean changed = Settings.Global.putInt(systemContext.getContentResolver(), SETTING_ADB_WIFI, 1);
        log((changed ? "已请求开启无线调试 TLS" : "无线调试设置未变化（可能已经开启）") + "，原因：" + reason);
        checkAddress(reason, 0, forceCopy);
    }

    private static void enableTcpMode(String reason, boolean forceCopy) {
        int port = cachedTcpPort > 0 ? cachedTcpPort : AdbModeConfig.DEFAULT_TCP_PORT;
        setSystemPropertyWithRootFallback(PROP_TCP_PORT, String.valueOf(port));
        log("已设置 " + PROP_TCP_PORT + "=" + port + "，原因：" + reason);
        // Ensure adbd picks up the port (same effect as `adb tcpip port`).
        restartAdbd("TCP 模式");
        checkAddress(reason, 0, forceCopy);
    }

    private static void restartAdbd(String reason) {
        try {
            // ctl.restart is the standard init trigger used by `adb tcpip`.
            setSystemPropertyWithRootFallback("ctl.restart", "adbd");
            log("已请求重启 adbd（" + reason + "）");
        } catch (Throwable t) {
            log("重启 adbd 失败：" + shortError(t));
        }
    }

    private static void checkAddress(String reason, int attempt, boolean forceCopy) {
        handler.postDelayed(() -> {
            if (AdbModeConfig.MODE_TLS.equals(cachedMode)) {
                if (getAdbWifiEnabled() != 1 && attempt == 0) {
                    enableAdb(reason + "-二次确认", forceCopy);
                    return;
                }
            } else if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
                if (!isTcpPortReady(cachedTcpPort) && attempt == 0) {
                    // prop may not have been applied yet; nudge once
                    setSystemPropertyWithRootFallback(PROP_TCP_PORT, String.valueOf(cachedTcpPort));
                }
            }

            String ip = findWifiIpv4();
            int port = resolveActivePort();
            if (ip != null && port > 0) {
                waitForWeType(ip + ":" + port, forceCopy);
            } else if (attempt < ADDRESS_POLL_MAX) {
                if (attempt == 0 || attempt == 4 || attempt == 8) {
                    log("等待地址就绪（" + reason + "/" + cachedMode + "）：IP=" + ip
                            + "，端口=" + port + "，第 " + (attempt + 1) + " 次");
                }
                if (AdbModeConfig.MODE_TLS.equals(cachedMode) && (attempt == 3 || attempt == 7)) {
                    try {
                        Settings.Global.putInt(systemContext.getContentResolver(), SETTING_ADB_WIFI, 1);
                    } catch (Throwable ignored) { }
                }
                if (AdbModeConfig.MODE_TCP.equals(cachedMode) && (attempt == 3 || attempt == 7)) {
                    setSystemPropertyWithRootFallback(PROP_TCP_PORT, String.valueOf(cachedTcpPort));
                    restartAdbd("TCP 轮询重试");
                }
                checkAddress(reason, attempt + 1, forceCopy);
            } else {
                log("获取地址失败（" + reason + "/" + cachedMode + "）：IP=" + ip + "，端口=" + port);
            }
        }, attempt == 0 ? 1_500L : ADDRESS_POLL_INTERVAL_MS);
    }

    private static int resolveActivePort() {
        if (AdbModeConfig.MODE_TCP.equals(cachedMode)) {
            int prop = getSystemPropertyInt(PROP_TCP_PORT, -1);
            if (prop > 0) return prop;
            return cachedTcpPort > 0 ? cachedTcpPort : -1;
        }
        return getTlsPort();
    }

    private static boolean isTcpPortReady(int expected) {
        int prop = getSystemPropertyInt(PROP_TCP_PORT, -1);
        return prop > 0 && (expected <= 0 || prop == expected);
    }

    private static void waitForWeType(String address, boolean forceCopy) {
        if (!forceCopy && address.equals(lastCopiedAddress)) {
            return;
        }
        if (address.equals(pendingAddress) && copyScheduled) return;
        pendingAddress = address;
        if (isWeTypeRunning()) {
            scheduleCopyAfterWeTypeReady(forceCopy ? "手动/强制复制" : "微信输入法已经运行");
        } else {
            log("无线 ADB 地址已就绪，等待微信输入法启动后复制：" + address);
        }
    }

    private static boolean isWeTypeRunning() {
        try {
            ActivityManager manager = systemContext.getSystemService(ActivityManager.class);
            if (manager == null || manager.getRunningAppProcesses() == null) return false;
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.processName != null && process.processName.startsWith(WETYPE_PACKAGE)) return true;
                if (process.pkgList != null) {
                    for (String pkg : process.pkgList) if (WETYPE_PACKAGE.equals(pkg)) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void scheduleCopyAfterWeTypeReady(String reason) {
        if (pendingAddress == null || copyScheduled) return;
        copyScheduled = true;
        log(reason + "，等待 3 秒初始化跨设备粘贴服务");
        handler.postDelayed(() -> {
            String address = pendingAddress;
            pendingAddress = null;
            copyScheduled = false;
            if (address != null) copyAddress(address);
        }, 3000L);
    }

    private static int getAdbWifiEnabled() {
        try {
            return Settings.Global.getInt(systemContext.getContentResolver(), SETTING_ADB_WIFI, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int getTlsPort() {
        return getSystemPropertyInt(PROP_TLS_PORT, -1);
    }

    private static int getSystemPropertyInt(String key, int def) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (Integer) XposedHelpers.callStaticMethod(properties, "getInt", key, def);
        } catch (Throwable ignored) { return def; }
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) XposedHelpers.callStaticMethod(properties, "get", key, def);
        } catch (Throwable ignored) { return def; }
    }

    private static void setSystemProperty(String key, String value) {
        Class<?> properties;
        try {
            properties = Class.forName("android.os.SystemProperties");
        } catch (Throwable t) {
            throw new IllegalStateException("SystemProperties 不可用", t);
        }
        XposedHelpers.callStaticMethod(properties, "set", key, value);
    }

    /**
     * Some vendor SELinux policies deny system_server from changing adbd properties.
     * On rooted devices, use the root service as the same fallback used by adb tcpip.
     */
    private static void setSystemPropertyWithRootFallback(String key, String value) {
        try {
            setSystemProperty(key, value);
            return;
        } catch (Throwable directFailure) {
            if (setSystemPropertyAsRoot(key, value)) return;
            throw directFailure;
        }
    }

    private static boolean setSystemPropertyAsRoot(String key, String value) {
        try {
            Bundle extras = new Bundle();
            extras.putString(RootCommandProvider.EXTRA_VALUE, value);
            Bundle result = systemContext.getContentResolver().call(
                    RootCommandProvider.URI,
                    RootCommandProvider.METHOD_SET_PROPERTY,
                    key,
                    extras);
            return result != null && result.getBoolean("success", false);
        } catch (Throwable t) {
            XposedBridge.log("WirelessAdbAutoStart root provider failed: " + shortError(t));
            return false;
        }
    }

    private static String findWifiIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName();
                if (!(name.startsWith("wlan") || name.startsWith("wifi"))) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (host != null && host.startsWith("172.19.")) continue;
                        return host;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void copyAddress(String address) {
        try {
            ClipboardManager clipboard = systemContext.getSystemService(ClipboardManager.class);
            if (clipboard == null) throw new IllegalStateException("剪贴板服务不可用");
            clipboard.setPrimaryClip(ClipData.newPlainText("无线 ADB 地址", address));
            lastCopiedAddress = address;
            log("已复制无线 ADB 地址：" + address);
        } catch (Throwable t) {
            log("复制地址失败：" + shortError(t));
        }
    }

    private static void log(String message) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        String line = time + "  " + message;
        XposedBridge.log("WirelessAdbAutoStart: " + line);
        if (systemContext == null) return;
        // Settings.Global：system_server 开机早期就能写（同模式配置）。
        if (EventLog.appendGlobal(systemContext, line)) return;
        try {
            Intent intent = new Intent(ACTION_LOG)
                    .setComponent(new ComponentName(MODULE_PACKAGE, LOG_RECEIVER))
                    .putExtra("line", line)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            systemContext.sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log("WirelessAdbAutoStart log broadcast failed: " + t);
        }
    }

    private static String shortError(Throwable t) {
        String text = t.getClass().getSimpleName() + ": " + t.getMessage();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}
