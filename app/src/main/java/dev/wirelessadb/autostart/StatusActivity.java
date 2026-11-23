package dev.wirelessadb.autostart;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusActivity extends Activity {
    private static final int LOG_PREVIEW_LINES = 6;
    private static final float DISABLED_ALPHA = 0.48f;
    private static final Pattern ADDRESS_IN_LOG =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wadb-ui-io");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger refreshGen = new AtomicInteger(0);
    private final AtomicInteger probeGen = new AtomicInteger(0);

    private View moduleAlertShell;
    private View moduleAlertShadow;
    private View moduleAlertCard;
    private TextView moduleAlertTitle;
    private TextView moduleAlertBadge;
    private TextView moduleAlertMessage;
    private TextView moduleAlertMeta;
    private TextView moduleAlertRecheckBtn;

    private View statusDot;
    private TextView noticeChip;
    private TextView statusTitle;
    private TextView modeLabel;
    private TextView addressView;
    private View portShell;
    private EditText portInput;
    private View modeTlsShell;
    private View modeTcpShell;
    private View modeTlsBtn;
    private View modeTcpBtn;
    private View modeTlsShadow;
    private View modeTcpShadow;
    private ImageView modeTlsIcon;
    private ImageView modeTcpIcon;
    private TextView modeTlsText;
    private TextView modeTcpText;
    private ImageView modeTlsCheck;
    private ImageView modeTcpCheck;
    private View applyRoot;
    private View copyRoot;
    private View refreshRoot;
    private View applyBtn;
    private View copyBtn;
    private View refreshBtn;
    private View logEmpty;
    private ScrollView logScroll;
    private TextView logView;
    private ImageView copyIcon;
    private TextView copyLabel;
    private boolean copiedFlash;

    private ModuleStatus.Result moduleStatus = new ModuleStatus.Result(
            ModuleStatus.State.CHECKING, false, -1, false, false, false, false, -1, 0L);
    private UiSnapshot lastSnapshot;

    private static final class UiSnapshot {
        final String mode;
        final int port;
        final String log;
        final String address;

        UiSnapshot(String mode, int port, String log, String address) {
            this.mode = mode;
            this.port = port;
            this.log = log;
            this.address = address;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        renderInitialCheckingState();
    }

    /** 避开状态栏 / 导航栏，避免标题卡和系统栏重叠。 */
    private void applySystemInsets() {
        View scroll = findViewById(R.id.scroll);
        int extraTop = Math.round(12 * getResources().getDisplayMetrics().density);
        int extraBottom = Math.round(8 * getResources().getDisplayMetrics().density);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), top + extraTop, v.getPaddingRight(), bottom + extraBottom);
            return insets.consumeSystemWindowInsets();
        });
        scroll.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        probeModuleStatusAsync(false);
        refreshUiAsync(null);
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        moduleAlertShell = findViewById(R.id.module_alert_shell);
        moduleAlertShadow = findViewById(R.id.module_alert_shadow);
        moduleAlertCard = findViewById(R.id.module_alert_card);
        moduleAlertTitle = findViewById(R.id.module_alert_title);
        moduleAlertBadge = findViewById(R.id.module_alert_badge);
        moduleAlertMessage = findViewById(R.id.module_alert_message);
        moduleAlertMeta = findViewById(R.id.module_alert_meta);
        moduleAlertRecheckBtn = findViewById(R.id.module_alert_recheck_btn);

        statusDot = findViewById(R.id.status_dot);
        noticeChip = findViewById(R.id.notice_chip);
        statusTitle = findViewById(R.id.status_title);
        modeLabel = findViewById(R.id.mode_label);
        addressView = findViewById(R.id.address_view);
        portShell = findViewById(R.id.port_shell);
        portInput = findViewById(R.id.port_input);
        modeTlsShell = findViewById(R.id.mode_tls_shell);
        modeTcpShell = findViewById(R.id.mode_tcp_shell);
        modeTlsBtn = findViewById(R.id.mode_tls_btn);
        modeTcpBtn = findViewById(R.id.mode_tcp_btn);
        modeTlsShadow = findViewById(R.id.mode_tls_shadow);
        modeTcpShadow = findViewById(R.id.mode_tcp_shadow);
        modeTlsIcon = findViewById(R.id.mode_tls_icon);
        modeTcpIcon = findViewById(R.id.mode_tcp_icon);
        modeTlsText = findViewById(R.id.mode_tls_text);
        modeTcpText = findViewById(R.id.mode_tcp_text);
        modeTlsCheck = findViewById(R.id.mode_tls_check);
        modeTcpCheck = findViewById(R.id.mode_tcp_check);
        logEmpty = findViewById(R.id.log_empty);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);

        applyRoot = findViewById(R.id.action_apply);
        copyRoot = findViewById(R.id.action_copy);
        refreshRoot = findViewById(R.id.action_refresh);
        applyBtn = setupAction(applyRoot, R.drawable.ic_check_cyan, "立即应用", v -> onApplyClicked());
        copyBtn = setupAction(copyRoot, R.drawable.ic_copy, "复制地址", v -> onCopyClicked());
        refreshBtn = setupAction(refreshRoot, R.drawable.ic_refresh, "刷新记录", v -> onRefreshClicked());

        copyIcon = copyRoot.findViewById(R.id.action_icon);
        copyLabel = copyRoot.findViewById(R.id.action_label);
    }

    private View setupAction(View root, int iconRes, String label, View.OnClickListener click) {
        ImageView icon = root.findViewById(R.id.action_icon);
        TextView text = root.findViewById(R.id.action_label);
        View btn = root.findViewById(R.id.action_btn);
        icon.setImageResource(iconRes);
        text.setText(label);
        btn.setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            v.animate().translationY(4f).setDuration(80)
                    .withEndAction(() -> v.animate().translationY(0f).setDuration(80).start())
                    .start();
            click.onClick(v);
        });
        return btn;
    }

    private void wireActions() {
        modeTlsBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TLS));
        modeTcpBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TCP));
        moduleAlertRecheckBtn.setOnClickListener(v -> {
            moduleAlertRecheckBtn.setEnabled(false);
            moduleAlertRecheckBtn.setText("正在重新检测…");
            probeModuleStatusAsync(true);
            refreshUiAsync(null);
        });
    }

    private void renderInitialCheckingState() {
        moduleAlertShell.setVisibility(View.GONE);
        statusTitle.setText("正在检测模块…");
        setNotice("检测中…", R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        statusDot.setBackgroundResource(R.drawable.bg_status_dot);
        addressView.setText("检测模块状态中…");
        applyPluginControlsEnabled(false);
    }

    private void probeModuleStatusAsync(boolean userTriggered) {
        final int gen = probeGen.incrementAndGet();
        ModuleStatus.probeAsync(getApplicationContext(), ioExecutor, uiHandler, result -> {
            if (isFinishing() || isDestroyed() || gen != probeGen.get()) return;
            moduleStatus = result;
            moduleAlertRecheckBtn.setEnabled(true);
            moduleAlertRecheckBtn.setText("重新检测模块状态");

            renderModuleAlert(result);
            applyPluginControlsEnabled(result.isActive());

            if (userTriggered) {
                if (result.isActive()) {
                    Toast.makeText(this, "模块已激活，系统框架正常响应", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = result.state == ModuleStatus.State.SCOPE_OR_REBOOT_REQUIRED
                            ? "系统框架 Hook 仍未响应，请确认作用域已勾选「系统框架」并重启手机"
                            : "仍未检测到模块激活，请检查 Magisk / LSPosed / Vector 模块开关并重启";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            }

            if (lastSnapshot != null) {
                renderUiSnapshot(lastSnapshot, null);
            } else {
                renderStatusWithoutSnapshot();
            }
        });
    }

    private void renderModuleAlert(ModuleStatus.Result result) {
        if (result == null || result.state == ModuleStatus.State.CHECKING || result.isActive()) {
            moduleAlertShell.setVisibility(View.GONE);
            return;
        }

        moduleAlertShell.setVisibility(View.VISIBLE);
        moduleAlertMeta.setText(result.diagnosticSummary());

        if (result.state == ModuleStatus.State.SCOPE_OR_REBOOT_REQUIRED) {
            moduleAlertShadow.setBackgroundResource(R.drawable.bg_alert_shadow_amber);
            moduleAlertCard.setBackgroundResource(R.drawable.bg_alert_card_amber);
            moduleAlertTitle.setText("系统框架 Hook 未生效");
            moduleAlertTitle.setTextColor(getColor(R.color.wadb_amber_title));
            moduleAlertBadge.setText("待生效");
            moduleAlertBadge.setBackgroundResource(R.drawable.bg_alert_chip_amber);
            moduleAlertBadge.setTextColor(getColor(R.color.wadb_amber_title));
            moduleAlertRecheckBtn.setBackgroundResource(R.drawable.bg_alert_btn_amber);
            moduleAlertRecheckBtn.setTextColor(getColor(R.color.wadb_amber_title));
            moduleAlertMessage.setText(
                    "LSPosed / Vector 已注入本应用，但系统框架（android）未响应，依赖插件的操作已安全暂停。\n\n"
                            + "排查步骤：\n"
                            + "1. 在 LSPosed / Vector 中确认已勾选「系统框架（android）」作用域\n"
                            + "2. 若刚启用或更新本模块，请重启手机使其在系统框架生效");
        } else {
            moduleAlertShadow.setBackgroundResource(R.drawable.bg_alert_shadow);
            moduleAlertCard.setBackgroundResource(R.drawable.bg_alert_card);
            moduleAlertTitle.setText("模块未激活");
            moduleAlertTitle.setTextColor(getColor(R.color.wadb_warn_title));
            moduleAlertBadge.setText("未激活");
            moduleAlertBadge.setBackgroundResource(R.drawable.bg_alert_chip);
            moduleAlertBadge.setTextColor(getColor(R.color.wadb_warn_title));
            moduleAlertRecheckBtn.setBackgroundResource(R.drawable.bg_alert_btn);
            moduleAlertRecheckBtn.setTextColor(getColor(R.color.wadb_warn_title));
            moduleAlertMessage.setText(
                    "未检测到 LSPosed / Vector 激活本模块，无线 ADB 自启与系统控制操作已禁用以防卡死。\n\n"
                            + "排查步骤：\n"
                            + "1. 检查 Magisk 是否触发安全模式，确认 LSPosed / Vector（如 zygisk_vector）已启用\n"
                            + "2. 在 LSPosed / Vector 中开启本模块并勾选「系统框架（android）」作用域\n"
                            + "3. 完成后重启手机，再打开本应用即可恢复正常");
        }
    }

    private void applyPluginControlsEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : DISABLED_ALPHA;
        modeTlsBtn.setEnabled(enabled);
        modeTcpBtn.setEnabled(enabled);
        modeTlsShell.setAlpha(alpha);
        modeTcpShell.setAlpha(alpha);
        portInput.setEnabled(enabled);
        portInput.setFocusable(enabled);
        portInput.setFocusableInTouchMode(enabled);
        portShell.setAlpha(alpha);
        applyBtn.setEnabled(enabled);
        applyRoot.setAlpha(alpha);
        copyBtn.setEnabled(enabled);
        copyRoot.setAlpha(alpha);
        refreshBtn.setEnabled(true);
        refreshRoot.setAlpha(1.0f);
    }

    private void onApplyClicked() {
        if (!requireModuleActive()) return;
        final Context appContext = getApplicationContext();
        executeSafely(ioExecutor, () -> {
            try {
                appContext.sendBroadcast(new Intent("dev.wirelessadb.autostart.APPLY").setPackage("android"));
            } catch (Throwable ignored) { }
        });
        setNotice("已请求立即应用", R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        Toast.makeText(this, "已请求立即应用", Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) refreshUiAsync("记录已刷新");
        }, 1_500L);
    }

    private void onCopyClicked() {
        if (!requireModuleActive()) return;
        final Context appContext = getApplicationContext();
        executeSafely(ioExecutor, () -> {
            try {
                appContext.sendBroadcast(new Intent("dev.wirelessadb.autostart.REQUEST_COPY").setPackage("android"));
            } catch (Throwable ignored) { }
        });
        flashCopied();
        setNotice("地址已复制", R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        Toast.makeText(this, "已请求立即复制", Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) refreshUiAsync(null);
        }, 1_200L);
    }

    private void onRefreshClicked() {
        probeModuleStatusAsync(false);
        refreshUiAsync(moduleStatus.isActive() ? "记录已刷新" : null);
    }

    private void switchMode(String mode) {
        if (!requireModuleActive()) return;
        int port = lastSnapshot != null ? lastSnapshot.port : AdbModeConfig.DEFAULT_TCP_PORT;
        if (AdbModeConfig.MODE_TCP.equals(mode)) {
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (Exception e) {
                port = AdbModeConfig.DEFAULT_TCP_PORT;
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "端口无效，请输入 1–65535", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final int finalPort = port;
        final Context appContext = getApplicationContext();
        executeSafely(ioExecutor, () -> {
            AdbModeConfig.setMode(appContext, mode, finalPort);
            try {
                appContext.sendBroadcast(new Intent("dev.wirelessadb.autostart.SET_MODE")
                        .setPackage("android")
                        .putExtra("mode", mode)
                        .putExtra("port", finalPort));
            } catch (Throwable ignored) { }
        });

        updateModeButtons(mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 无线调试（随机端口）"
                : ("TCP 固定端口（" + finalPort + "）"));
        String notice = AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 模式已应用"
                : ("TCP 端口 " + finalPort + " 已应用");
        setNotice(notice, R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) refreshUiAsync(null);
        }, 1_500L);
    }

    private boolean requireModuleActive() {
        if (moduleStatus.isActive()) return true;
        String hint = moduleStatus.state == ModuleStatus.State.SCOPE_OR_REBOOT_REQUIRED
                ? "系统框架 Hook 未生效，请勾选「系统框架」作用域并重启"
                : "模块未激活，请先启用 LSPosed / Vector 并重启";
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void refreshUiAsync(String noticeOverride) {
        final int gen = refreshGen.incrementAndGet();
        final Context appContext = getApplicationContext();
        executeSafely(ioExecutor, () -> {
            String mode = AdbModeConfig.getMode(appContext);
            int port = AdbModeConfig.getTcpPort(appContext);
            String log = EventLog.readMerged(appContext);
            String address = resolveAddress(appContext, mode, port, log);
            UiSnapshot snapshot = new UiSnapshot(mode, port, log, address);
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed() || gen != refreshGen.get()) return;
                lastSnapshot = snapshot;
                renderUiSnapshot(snapshot, noticeOverride);
            });
        });
    }

    private void renderUiSnapshot(UiSnapshot snapshot, String noticeOverride) {
        if (!portInput.hasFocus()) {
            portInput.setText(String.valueOf(snapshot.port));
        }
        updateModeButtons(snapshot.mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(snapshot.mode)
                ? "TLS 无线调试（随机端口）"
                : ("TCP 固定端口（" + snapshot.port + "）"));

        String preview = recentLogLines(snapshot.log, LOG_PREVIEW_LINES);
        boolean empty = preview == null || preview.isEmpty();
        if (empty) {
            logEmpty.setVisibility(View.VISIBLE);
            logScroll.setVisibility(View.GONE);
            logView.setText("");
        } else {
            logEmpty.setVisibility(View.GONE);
            logScroll.setVisibility(View.VISIBLE);
            logView.setText(preview);
        }

        if (!moduleStatus.isActive()) {
            renderStatusWithoutSnapshot();
            return;
        }

        statusDot.setBackgroundResource(R.drawable.bg_status_dot);
        addressView.setText(snapshot.address == null ? "等待地址…" : snapshot.address);
        boolean ready = snapshot.address != null;
        statusTitle.setText(ready ? "连接已就绪" : "等待连接");
        if (noticeOverride != null) {
            setNotice(noticeOverride, R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        } else if (!copiedFlash) {
            setNotice(ready ? "已准备就绪" : "等待模块生效",
                    R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
        }
    }

    private void renderStatusWithoutSnapshot() {
        if (moduleStatus.state == ModuleStatus.State.INACTIVE) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_warn);
            statusTitle.setText("模块未激活");
            setNotice("未激活", R.drawable.bg_alert_chip, R.color.wadb_warn_title);
            addressView.setText("模块未激活，无法控制无线 ADB");
        } else if (moduleStatus.state == ModuleStatus.State.SCOPE_OR_REBOOT_REQUIRED) {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_warn);
            statusTitle.setText("系统框架未生效");
            setNotice("待生效", R.drawable.bg_alert_chip_amber, R.color.wadb_amber_title);
            addressView.setText("请勾选系统框架作用域并重启");
        } else {
            statusDot.setBackgroundResource(R.drawable.bg_status_dot);
            statusTitle.setText("正在检测模块…");
            setNotice("检测中…", R.drawable.bg_notice_chip, R.color.wadb_cyan_text);
            addressView.setText("检测模块状态中…");
        }
    }

    private static void executeSafely(ExecutorService executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) { }
    }

    private void updateModeButtons(String mode) {
        boolean tls = AdbModeConfig.MODE_TLS.equals(mode);
        modeTlsBtn.setBackgroundResource(tls ? R.drawable.bg_mode_selected : R.drawable.bg_mode_idle);
        modeTcpBtn.setBackgroundResource(tls ? R.drawable.bg_mode_idle : R.drawable.bg_mode_selected);
        modeTlsShadow.setBackgroundResource(tls ? R.drawable.bg_mode_selected_shadow : R.drawable.bg_mode_idle_shadow);
        modeTcpShadow.setBackgroundResource(tls ? R.drawable.bg_mode_idle_shadow : R.drawable.bg_mode_selected_shadow);
        modeTlsText.setTextColor(tls ? Color.WHITE : getColor(R.color.wadb_ink));
        modeTcpText.setTextColor(tls ? getColor(R.color.wadb_ink) : Color.WHITE);
        modeTlsIcon.setImageResource(tls ? R.drawable.ic_wifi_on_pink : R.drawable.ic_wifi_idle);
        modeTcpIcon.setImageResource(tls ? R.drawable.ic_link_idle : R.drawable.ic_link_on_pink);
        modeTlsCheck.setVisibility(tls ? View.VISIBLE : View.GONE);
        modeTcpCheck.setVisibility(tls ? View.GONE : View.VISIBLE);
    }

    private void setNotice(String text, int bgRes, int colorRes) {
        noticeChip.setText(text);
        noticeChip.setBackgroundResource(bgRes);
        noticeChip.setTextColor(getColor(colorRes));
    }

    private void flashCopied() {
        copiedFlash = true;
        copyIcon.setImageResource(R.drawable.ic_check_cyan);
        copyLabel.setText("已复制");
        copyLabel.postDelayed(() -> {
            copiedFlash = false;
            copyIcon.setImageResource(R.drawable.ic_copy);
            copyLabel.setText("复制地址");
        }, 1600);
    }

    /** 只展示最近几条，倒序（最新在上）。 */
    private static String recentLogLines(String log, int limit) {
        if (log == null || log.isEmpty() || limit <= 0) return "";
        String[] lines = log.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].trim().isEmpty()) end--;
        if (end == 0) return "";
        int start = Math.max(0, end - limit);
        StringBuilder out = new StringBuilder();
        for (int i = end - 1; i >= start; i--) {
            if (lines[i].trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String resolveAddress(Context context, String mode, int port, String log) {
        String saved = EventLog.readLastAddress(context);
        if (saved != null && !saved.isEmpty()) return saved;
        if (log != null) {
            Matcher m = ADDRESS_IN_LOG.matcher(log);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null) return last;
        }
        String ip = findWifiIpv4();
        if (ip == null) return null;
        if (AdbModeConfig.MODE_TCP.equals(mode)) return ip + ":" + port;
        return ip + ":?";
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
}
