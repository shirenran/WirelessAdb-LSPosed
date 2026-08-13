package dev.wirelessadb.autostart;

/** Permissions used to protect app-to-system-server and system-server-to-app IPC. */
public final class IpcContract {
    public static final String CONTROL_PERMISSION =
            "dev.wirelessadb.autostart.permission.CONTROL";
    public static final String LOG_WRITE_PERMISSION =
            "dev.wirelessadb.autostart.permission.LOG_WRITE";

    private IpcContract() {}
}
