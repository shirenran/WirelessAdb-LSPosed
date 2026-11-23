package dev.wirelessadb.autostart;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Mode prefs shared between UI and system_server hook.
 * Primary durable store for the hook: Settings.Global (system can always read).
 * UI also mirrors into device-protected SharedPreferences for offline display.
 */
public final class AdbModeConfig {
    public static final String PREFS = "config";
    public static final String KEY_MODE = "mode";
    public static final String KEY_TCP_PORT = "tcp_port";

    /** Settings.Global keys (readable from system_server). */
    public static final String GLOBAL_MODE = "wirelessadb_autostart_mode";
    public static final String GLOBAL_TCP_PORT = "wirelessadb_autostart_tcp_port";

    /** Android 11+ 无线调试 TLS，端口随机。 */
    public static final String MODE_TLS = "tls";
    /** 经典 adb tcpip，固定端口（默认 5555）。 */
    public static final String MODE_TCP = "tcp";

    public static final int DEFAULT_TCP_PORT = 5555;

    private AdbModeConfig() {}

    public static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getMode(Context context) {
        try {
            String global = Settings.Global.getString(context.getContentResolver(), GLOBAL_MODE);
            if (MODE_TCP.equals(global) || MODE_TLS.equals(global)) return global;
        } catch (Throwable ignored) { }
        try {
            String mode = prefs(context).getString(KEY_MODE, MODE_TLS);
            return MODE_TCP.equals(mode) ? MODE_TCP : MODE_TLS;
        } catch (Throwable ignored) {
            return MODE_TLS;
        }
    }

    public static int getTcpPort(Context context) {
        try {
            int global = Settings.Global.getInt(context.getContentResolver(), GLOBAL_TCP_PORT, -1);
            if (global >= 1 && global <= 65535) return global;
        } catch (Throwable ignored) { }
        try {
            int port = prefs(context).getInt(KEY_TCP_PORT, DEFAULT_TCP_PORT);
            if (port < 1 || port > 65535) return DEFAULT_TCP_PORT;
            return port;
        } catch (Throwable ignored) {
            return DEFAULT_TCP_PORT;
        }
    }

    public static void setMode(Context context, String mode, int tcpPort) {
        String normalized = MODE_TCP.equals(mode) ? MODE_TCP : MODE_TLS;
        int port = tcpPort;
        if (port < 1 || port > 65535) port = DEFAULT_TCP_PORT;
        try {
            prefs(context).edit()
                    .putString(KEY_MODE, normalized)
                    .putInt(KEY_TCP_PORT, port)
                    .apply();
        } catch (Throwable ignored) { }
        try {
            Settings.Global.putString(context.getContentResolver(), GLOBAL_MODE, normalized);
            Settings.Global.putInt(context.getContentResolver(), GLOBAL_TCP_PORT, port);
        } catch (Throwable ignored) {
            // App process may lack WRITE_SECURE_SETTINGS; hook will write on SET_MODE.
        }
    }

    /** Called from system_server (has permission to write Settings.Global). */
    public static void setModeAsSystem(Context systemContext, String mode, int tcpPort) {
        String normalized = MODE_TCP.equals(mode) ? MODE_TCP : MODE_TLS;
        int port = tcpPort;
        if (port < 1 || port > 65535) port = DEFAULT_TCP_PORT;
        try {
            Settings.Global.putString(systemContext.getContentResolver(), GLOBAL_MODE, normalized);
            Settings.Global.putInt(systemContext.getContentResolver(), GLOBAL_TCP_PORT, port);
        } catch (Throwable ignored) { }
    }

    public static String modeLabel(String mode) {
        return MODE_TCP.equals(mode)
                ? "TCP 固定端口"
                : "TLS 无线调试（随机端口）";
    }
}
