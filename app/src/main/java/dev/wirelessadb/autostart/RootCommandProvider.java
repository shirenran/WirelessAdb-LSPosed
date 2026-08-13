package dev.wirelessadb.autostart;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.concurrent.TimeUnit;

/** Executes the small, fixed set of root operations requested by system_server. */
public final class RootCommandProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.wirelessadb.autostart.root";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_SET_PROPERTY = "set_property";
    public static final String EXTRA_VALUE = "value";
    private static final String PROP_TCP_PORT = "service.adb.tcp.port";
    private static final String PROP_RESTART = "ctl.restart";
    private static final String VALUE_ADBD = "adbd";
    private static final String[] ROOT_EXECUTABLES = {
            "/product/bin/su",
            "/system/bin/su",
            "su"
    };

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("Only system_server may request root operations");
        }
        Bundle result = new Bundle();
        if (!METHOD_SET_PROPERTY.equals(method) || arg == null || extras == null) {
            return result;
        }

        String value = extras.getString(EXTRA_VALUE);
        if (!isAllowedProperty(arg, value)) return result;
        result.putBoolean("success", setPropertyAsRoot(arg, value));
        return result;
    }

    private static boolean isAllowedProperty(String key, String value) {
        if (PROP_RESTART.equals(key)) return VALUE_ADBD.equals(value);
        if (!PROP_TCP_PORT.equals(key) || value == null) return false;
        try {
            int port = Integer.parseInt(value);
            return port == -1 || (port >= 1 && port <= 65535);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean setPropertyAsRoot(String key, String value) {
        String command = "setprop " + shellQuote(key) + " " + shellQuote(value);
        for (String executable : ROOT_EXECUTABLES) {
            java.lang.Process process = null;
            try {
                process = new ProcessBuilder(executable, "-c", command)
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() == 0) return true;
            } catch (Throwable ignored) {
                // Try the next known su location.
            } finally {
                if (process != null) {
                    try { process.getInputStream().close(); } catch (Throwable ignored) { }
                    try { process.getErrorStream().close(); } catch (Throwable ignored) { }
                    try { process.getOutputStream().close(); } catch (Throwable ignored) { }
                }
            }
        }
        return false;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
