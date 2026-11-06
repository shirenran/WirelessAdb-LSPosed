package dev.wirelessadb.autostart;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusActivity extends Activity {
    private static final int LOG_PREVIEW_LINES = 12;
    private static final long FOREGROUND_CLIENT_POLL_MS = 3_500L;
    private static final float ADDRESS_MAX_TEXT_SP = 24f;
    private static final float ADDRESS_MIN_TEXT_SP = 15f;
    private static final Pattern ADDRESS_IN_LOG =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private TextView noticeChip;
    private TextView statusTitle;
    private TextView modeLabel;
    private TextView addressView;
    private EditText portInput;
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
    private TextView clientsCountChip;
    private View clientsEmpty;
    private TextView clientsEmptyHint;
    private LinearLayout clientsList;
    private View logEmpty;
    private ScrollView logScroll;
    private TextView logView;
    private ImageView copyIcon;
    private TextView copyLabel;
    private boolean copiedFlash;
    private boolean resumed;
    private Boolean rootFallbackAvailable;
    private List<AdbClientMonitor.ClientEntry> lastRenderedClients = Collections.emptyList();

    private final ContentObserver globalObserver = new ContentObserver(uiHandler) {
        @Override public void onChange(boolean selfChange) {
            if (!resumed) return;
            refreshClientsAndLogs();
        }
    };

    private final Runnable foregroundPollRunnable = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            requestHookClientRefresh();
            uiHandler.postDelayed(this, FOREGROUND_CLIENT_POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        refreshAll("已准备就绪", true);
    }

    /** 避开状态栏 / 导航栏，避免标题卡和系统栏重叠。 */
    private void applySystemInsets() {
        View scroll = findViewById(R.id.scroll);
        int extraTop = dp(12);
        int extraBottom = dp(8);
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
        resumed = true;
        registerGlobalObservers();
        requestHookClientRefresh();
        refreshAll(null, true);
        uiHandler.removeCallbacks(foregroundPollRunnable);
        uiHandler.postDelayed(foregroundPollRunnable, FOREGROUND_CLIENT_POLL_MS);
    }

    @Override protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(foregroundPollRunnable);
        try {
            getContentResolver().unregisterContentObserver(globalObserver);
        } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    private void registerGlobalObservers() {
        try {
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(AdbClientMonitor.GLOBAL_CLIENTS), false, globalObserver);
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(EventLog.GLOBAL_LOG), false, globalObserver);
        } catch (Throwable ignored) { }
    }

    private void requestHookClientRefresh() {
        try {
            sendBroadcast(new Intent(AdbClientMonitor.ACTION_REFRESH_CLIENTS).setPackage("android"));
        } catch (Throwable ignored) { }
    }

    private void bindViews() {
        noticeChip = findViewById(R.id.notice_chip);
        statusTitle = findViewById(R.id.status_title);
        modeLabel = findViewById(R.id.mode_label);
        addressView = findViewById(R.id.address_view);
        portInput = findViewById(R.id.port_input);
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
        clientsCountChip = findViewById(R.id.clients_count_chip);
        clientsEmpty = findViewById(R.id.clients_empty);
        clientsEmptyHint = findViewById(R.id.clients_empty_hint);
        clientsList = findViewById(R.id.clients_list);
        logEmpty = findViewById(R.id.log_empty);
        logScroll = findViewById(R.id.log_scroll);
        logView = findViewById(R.id.log_view);

        View applyRoot = findViewById(R.id.action_apply);
        View copyRoot = findViewById(R.id.action_copy);
        View refreshRoot = findViewById(R.id.action_refresh);
        setupAction(applyRoot, R.drawable.ic_check_cyan, "立即应用", v -> {
            sendBroadcast(new Intent("dev.wirelessadb.autostart.APPLY").setPackage("android"));
            setNotice("已请求立即应用");
            Toast.makeText(this, "已请求立即应用", Toast.LENGTH_SHORT).show();
            portInput.postDelayed(() -> refreshAll("记录已刷新", true), 1500);
        });
        setupAction(copyRoot, R.drawable.ic_copy, "复制地址", v -> {
            sendBroadcast(new Intent("dev.wirelessadb.autostart.REQUEST_COPY").setPackage("android"));
            flashCopied();
            setNotice("地址已复制");
            Toast.makeText(this, "已请求立即复制", Toast.LENGTH_SHORT).show();
            portInput.postDelayed(() -> refreshAll(null, false), 1200);
        });
        setupAction(refreshRoot, R.drawable.ic_refresh, "刷新记录", v -> {
            rootFallbackAvailable = null;
            requestHookClientRefresh();
            refreshAll("记录已刷新", true);
        });

        copyIcon = copyRoot.findViewById(R.id.action_icon);
        copyLabel = copyRoot.findViewById(R.id.action_label);
    }

    private void setupAction(View root, int iconRes, String label, View.OnClickListener click) {
        ImageView icon = root.findViewById(R.id.action_icon);
        TextView text = root.findViewById(R.id.action_label);
        View btn = root.findViewById(R.id.action_btn);
        icon.setImageResource(iconRes);
        text.setText(label);
        btn.setOnClickListener(v -> {
            v.animate().translationY(4f).setDuration(80)
                    .withEndAction(() -> v.animate().translationY(0f).setDuration(80).start())
                    .start();
            click.onClick(v);
        });
    }

    private void wireActions() {
        modeTlsBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TLS));
        modeTcpBtn.setOnClickListener(v -> switchMode(AdbModeConfig.MODE_TCP));
        portInput.setText(String.valueOf(AdbModeConfig.getTcpPort(this)));
    }

    private void switchMode(String mode) {
        int port = AdbModeConfig.getTcpPort(this);
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
        AdbModeConfig.setMode(this, mode, port);
        sendBroadcast(new Intent("dev.wirelessadb.autostart.SET_MODE")
                .setPackage("android")
                .putExtra("mode", mode)
                .putExtra("port", port));
        String notice = AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 模式已应用"
                : ("TCP 端口 " + port + " 已应用");
        refreshAll(notice, true);
        Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        portInput.postDelayed(() -> refreshAll(null, true), 1500);
    }

    private void refreshAll(String noticeOverride, boolean allowRootFallback) {
        String mode = AdbModeConfig.getMode(this);
        int port = AdbModeConfig.getTcpPort(this);
        if (!portInput.hasFocus()) {
            portInput.setText(String.valueOf(port));
        }
        updateModeButtons(mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(mode)
                ? "TLS 无线调试（随机端口）"
                : ("TCP 固定端口（" + port + "）"));

        String log = readLog();
        updateLogPreview(log);

        String address = resolveAddress(mode, port, log);
        setAddressText(address);
        boolean ready = address != null;
        statusTitle.setText(ready ? "连接已就绪" : "等待连接");
        if (noticeOverride != null) {
            setNotice(noticeOverride);
        } else if (!copiedFlash) {
            setNotice(ready ? "已准备就绪" : "等待模块生效");
        }

        refreshClientsSection(address, port, allowRootFallback);
    }

    private void refreshClientsAndLogs() {
        String mode = AdbModeConfig.getMode(this);
        int port = AdbModeConfig.getTcpPort(this);
        String log = readLog();
        updateLogPreview(log);
        String address = resolveAddress(mode, port, log);
        refreshClientsSection(address, port, false);
    }

    private void updateLogPreview(String log) {
        String preview = recentLogLines(log, LOG_PREVIEW_LINES);
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
    }

    private void refreshClientsSection(String activeAddress, int configuredTcpPort, boolean allowRootFallback) {
        AdbClientMonitor.Snapshot snapshot = AdbClientMonitor.readGlobalSnapshot(this);
        if (snapshot.fromHook) {
            renderClients(snapshot.clients, true);
            return;
        }

        if (!allowRootFallback || Boolean.FALSE.equals(rootFallbackAvailable)) {
            renderClients(lastRenderedClients, false);
            return;
        }

        final int resolvedPort = parsePortFromAddress(activeAddress, configuredTcpPort);
        bgExecutor.execute(() -> {
            List<AdbClientMonitor.ClientEntry> rootScanned =
                    AdbClientMonitor.scanViaRootFallback(resolvedPort, -1);
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (rootScanned == null) {
                    rootFallbackAvailable = Boolean.FALSE;
                    renderClients(Collections.emptyList(), false);
                } else {
                    rootFallbackAvailable = Boolean.TRUE;
                    renderClients(rootScanned, true);
                }
            });
        });
    }

    private void renderClients(List<AdbClientMonitor.ClientEntry> clients, boolean dataReady) {
        List<AdbClientMonitor.ClientEntry> safeList =
                clients == null ? Collections.emptyList() : clients;
        lastRenderedClients = safeList;

        int count = safeList.size();
        clientsCountChip.setText(count + " 台在线");
        clientsCountChip.setTextColor(count > 0
                ? getColor(R.color.wadb_mint_icon)
                : getColor(R.color.wadb_cyan_text));

        if (safeList.isEmpty()) {
            clientsList.removeAllViews();
            clientsList.setVisibility(View.GONE);
            clientsEmpty.setVisibility(View.VISIBLE);
            if (!dataReady) {
                clientsEmptyHint.setText("重启手机使新版模块生效（或在 Root 管理器授权本应用）即可实时监控连接 IP");
            } else {
                clientsEmptyHint.setText("电脑执行 adb connect 连接后，客户端 IP 会实时显示在这里");
            }
            return;
        }

        clientsEmpty.setVisibility(View.GONE);
        clientsList.setVisibility(View.VISIBLE);
        clientsList.removeAllViews();

        for (int i = 0; i < safeList.size(); i++) {
            AdbClientMonitor.ClientEntry entry = safeList.get(i);
            int sameIpCount = countByIp(safeList, entry.remoteIp);
            View row = buildClientRow(entry, sameIpCount);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(10);
            clientsList.addView(row, lp);
        }
    }

    private View buildClientRow(AdbClientMonitor.ClientEntry entry, int sameIpCount) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_client_item);
        int padH = dp(14);
        int padV = dp(12);
        card.setPadding(padH, padV, padH, padV);

        // Top row: dot + IP + alias tag
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackgroundResource(R.drawable.bg_online_dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.setMarginEnd(dp(8));
        topRow.addView(dot, dotLp);

        TextView ipText = new TextView(this);
        ipText.setText(entry.remoteIp);
        ipText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        ipText.setTextColor(getColor(R.color.wadb_address));
        ipText.setMaxLines(1);
        ipText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        ipText.setOnClickListener(v -> copyClientIp(entry));
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(ipText, ipLp);

        String alias = AdbClientMonitor.getAlias(this, entry.remoteIp);
        TextView aliasChip = new TextView(this);
        aliasChip.setBackgroundResource(R.drawable.bg_btn_tag);
        aliasChip.setPadding(dp(10), dp(3), dp(10), dp(3));
        aliasChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        aliasChip.setTypeface(Typeface.DEFAULT_BOLD);
        aliasChip.setTextColor(getColor(R.color.wadb_cyan_text));
        aliasChip.setText(alias.isEmpty() ? "+ 备注" : alias);
        aliasChip.setOnClickListener(v -> showEditAliasDialog(entry.remoteIp, alias));
        LinearLayout.LayoutParams aliasLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aliasLp.setMarginStart(dp(8));
        topRow.addView(aliasChip, aliasLp);

        card.addView(topRow);

        // Bottom row: port/time details + Disconnect button
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = dp(8);

        TextView detailText = new TextView(this);
        detailText.setTypeface(Typeface.MONOSPACE);
        detailText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        detailText.setTextColor(getColor(R.color.wadb_muted2));
        detailText.setText(formatClientSubtitle(entry));
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomRow.addView(detailText, detailLp);

        TextView kickBtn = new TextView(this);
        kickBtn.setBackgroundResource(R.drawable.bg_btn_kick);
        kickBtn.setPadding(dp(12), dp(5), dp(12), dp(5));
        kickBtn.setText("断开");
        kickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        kickBtn.setTypeface(Typeface.DEFAULT_BOLD);
        kickBtn.setTextColor(getColor(R.color.wadb_pink_deep));
        kickBtn.setOnClickListener(v -> confirmKickClient(entry, sameIpCount));
        LinearLayout.LayoutParams kickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kickLp.setMarginStart(dp(8));
        bottomRow.addView(kickBtn, kickLp);

        card.addView(bottomRow, bottomLp);
        return card;
    }

    private String formatClientSubtitle(AdbClientMonitor.ClientEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("远端 :").append(entry.remotePort)
                .append(" → ").append(entry.transport).append(" :").append(entry.localPort);
        if (entry.connectedSinceMs > 0L) {
            String hm = new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                    .format(new Date(entry.connectedSinceMs));
            sb.append(" · ").append(hm);
        }
        return sb.toString();
    }

    private void copyClientIp(AdbClientMonitor.ClientEntry entry) {
        try {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ADB Client IP", entry.remoteIp));
                Toast.makeText(this, "已复制客户端 IP：" + entry.remoteIp, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) { }
    }

    private void showEditAliasDialog(String ip, String currentAlias) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        input.setHint("例如：主力电脑 / NAS / MacBook");
        if (!"本机回环".equals(currentAlias)) {
            input.setText(currentAlias);
            input.setSelection(input.getText().length());
        }

        LinearLayout wrapper = new LinearLayout(this);
        int pad = dp(20);
        wrapper.setPadding(pad, dp(12), pad, dp(4));
        wrapper.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("设备备注 (" + ip + ")")
                .setView(wrapper)
                .setPositiveButton("保存", (d, w) -> {
                    AdbClientMonitor.setAlias(this, ip, input.getText().toString());
                    renderClients(lastRenderedClients, true);
                })
                .setNeutralButton("清除", (d, w) -> {
                    AdbClientMonitor.setAlias(this, ip, "");
                    renderClients(lastRenderedClients, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmKickClient(AdbClientMonitor.ClientEntry entry, int sameIpCount) {
        String alias = AdbClientMonitor.getAlias(this, entry.remoteIp);
        String label = alias.isEmpty()
                ? entry.remoteEndpoint()
                : (alias + " (" + entry.remoteEndpoint() + ")");
        boolean hookReady = AdbClientMonitor.readGlobalSnapshot(this).fromHook;
        String msg = hookReady
                ? ("强制关闭来自 " + label + " 的无线 ADB 会话吗？")
                : ("强制关闭来自 " + label + " 的无线 ADB 会话吗？\n\n注：当前模块尚未重启生效，将尝试通过 Root 权限断开。");

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("断开客户端连接")
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("断开此连接", (d, w) -> executeKick(entry, false));

        if (sameIpCount > 1) {
            builder.setNeutralButton("断开该 IP 全部 (" + sameIpCount + ")",
                    (d, w) -> executeKick(entry, true));
        }
        builder.show();
    }

    private void executeKick(AdbClientMonitor.ClientEntry entry, boolean allForIp) {
        setNotice("正在断开连接…");
        final String target = allForIp ? entry.remoteIp : entry.remoteEndpoint();
        final boolean hookReady = AdbClientMonitor.readGlobalSnapshot(this).fromHook;

        if (hookReady) {
            try {
                sendBroadcast(new Intent(AdbClientMonitor.ACTION_DISCONNECT_CLIENT)
                        .setPackage("android")
                        .putExtra("ip", entry.remoteIp)
                        .putExtra("remote_port", entry.remotePort)
                        .putExtra("local_port", entry.localPort)
                        .putExtra("all_for_ip", allForIp));
            } catch (Throwable ignored) { }
        }

        bgExecutor.execute(() -> {
            if (hookReady) {
                try { Thread.sleep(380L); } catch (InterruptedException ignored) { }
                AdbClientMonitor.Snapshot after = AdbClientMonitor.readGlobalSnapshot(this);
                boolean stillConnected = false;
                for (AdbClientMonitor.ClientEntry c : after.clients) {
                    if (allForIp && entry.remoteIp.equals(c.remoteIp) && entry.localPort == c.localPort) {
                        stillConnected = true;
                        break;
                    }
                    if (!allForIp && entry.key().equals(c.key())) {
                        stillConnected = true;
                        break;
                    }
                }
                if (!stillConnected) {
                    uiHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, "已断开 " + target, Toast.LENGTH_SHORT).show();
                        refreshAll("已断开 " + entry.remoteIp, false);
                    });
                    return;
                }
            }

            AdbClientMonitor.KickResult res = AdbClientMonitor.disconnectClientAsRoot(
                    entry.remoteIp, entry.remotePort, entry.localPort, allForIp);
            uiHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (res.success) {
                    rootFallbackAvailable = Boolean.TRUE;
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                            .format(new Date());
                    EventLog.append(this, time + "  手动断开客户端：" + target);
                    requestHookClientRefresh();
                    Toast.makeText(this, "已断开 " + target, Toast.LENGTH_SHORT).show();
                    uiHandler.postDelayed(() -> refreshAll("已断开 " + entry.remoteIp, true), 350L);
                } else {
                    setNotice("断开失败");
                    Toast.makeText(this, "断开失败：" + res.detail, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static int countByIp(List<AdbClientMonitor.ClientEntry> list, String ip) {
        int c = 0;
        for (AdbClientMonitor.ClientEntry item : list) {
            if (ip.equals(item.remoteIp)) c++;
        }
        return c;
    }

    private static int parsePortFromAddress(String address, int fallbackPort) {
        if (address != null) {
            int colon = address.lastIndexOf(':');
            if (colon > 0 && colon + 1 < address.length()) {
                try {
                    int p = Integer.parseInt(address.substring(colon + 1).trim());
                    if (p >= 1 && p <= 65535) return p;
                } catch (NumberFormatException ignored) { }
            }
        }
        return fallbackPort;
    }

    private void setAddressText(String address) {
        String text = address == null ? "等待地址…" : address;
        addressView.setText(text);
        addressView.setMaxLines(1);
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADDRESS_MAX_TEXT_SP);
        addressView.post(() -> fitAddressText(text));
    }

    private void fitAddressText(String text) {
        int available = addressView.getWidth()
                - addressView.getPaddingLeft()
                - addressView.getPaddingRight();
        if (available <= 0 || TextUtils.isEmpty(text)) return;
        TextPaint paint = new TextPaint(addressView.getPaint());
        float density = getResources().getDisplayMetrics().scaledDensity;
        for (float sp = ADDRESS_MAX_TEXT_SP; sp >= ADDRESS_MIN_TEXT_SP; sp -= 1f) {
            paint.setTextSize(sp * density);
            if (paint.measureText(text) <= available) {
                addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                return;
            }
        }
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADDRESS_MIN_TEXT_SP);
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

    private void setNotice(String text) {
        noticeChip.setText(text);
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

    private String readLog() {
        return EventLog.readMerged(this);
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

    private String resolveAddress(String mode, int port, String log) {
        String saved = EventLog.readLastAddress(this);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
