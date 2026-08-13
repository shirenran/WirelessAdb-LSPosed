package dev.wirelessadb.autostart;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusActivity extends Activity {
    private static final int LOG_PREVIEW_LINES = 6;
    private static final float ADDRESS_MAX_TEXT_SP = 24f;
    private static final float ADDRESS_MIN_TEXT_SP = 14f;
    private static final Pattern ADDRESS_IN_LOG =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d{1,5})");

    private TextView noticeChip;
    private TextView statusTitle;
    private TextView modeLabel;
    private TextView addressView;
    private EditText portInput;
    private Spinner languageSpinner;
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
    private View logEmpty;
    private ScrollView logScroll;
    private TextView logView;
    private ImageView copyIcon;
    private TextView copyLabel;
    private boolean copiedFlash;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageConfig.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        applySystemInsets();
        bindViews();
        wireActions();
        refreshAll(getString(R.string.notice_ready));
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
        refreshAll(null);
    }

    private void bindViews() {
        noticeChip = findViewById(R.id.notice_chip);
        statusTitle = findViewById(R.id.status_title);
        modeLabel = findViewById(R.id.mode_label);
        addressView = findViewById(R.id.address_view);
        portInput = findViewById(R.id.port_input);
        languageSpinner = findViewById(R.id.language_spinner);
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

        View applyRoot = findViewById(R.id.action_apply);
        View copyRoot = findViewById(R.id.action_copy);
        View refreshRoot = findViewById(R.id.action_refresh);
        setupAction(applyRoot, R.drawable.ic_check_cyan, getString(R.string.action_apply), v -> {
            sendBroadcast(new Intent("dev.wirelessadb.autostart.APPLY").setPackage("android"));
            setNotice(getString(R.string.notice_apply_requested));
            Toast.makeText(this, R.string.notice_apply_requested, Toast.LENGTH_SHORT).show();
            portInput.postDelayed(() -> refreshAll(getString(R.string.notice_log_refreshed)), 1500);
        });
        setupAction(copyRoot, R.drawable.ic_copy, getString(R.string.action_copy), v -> {
            sendBroadcast(new Intent("dev.wirelessadb.autostart.REQUEST_COPY").setPackage("android"));
            flashCopied();
            setNotice(getString(R.string.notice_address_copied));
            Toast.makeText(this, R.string.notice_copy_requested, Toast.LENGTH_SHORT).show();
            portInput.postDelayed(() -> refreshAll(null), 1200);
        });
        setupAction(refreshRoot, R.drawable.ic_refresh, getString(R.string.action_refresh),
                v -> refreshAll(getString(R.string.notice_log_refreshed)));

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

        final String currentLanguage = LanguageConfig.getLanguage(this);
        final boolean[] ready = {false};
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!ready[0]) return;
                String language = LanguageConfig.languageAt(position);
                if (language.equals(LanguageConfig.getLanguage(StatusActivity.this))) return;
                LanguageConfig.setLanguage(StatusActivity.this, language);
                recreate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        languageSpinner.setSelection(LanguageConfig.positionFor(currentLanguage), false);
        ready[0] = true;
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
                Toast.makeText(this, R.string.tcp_port_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        AdbModeConfig.setMode(this, mode, port);
        sendBroadcast(new Intent("dev.wirelessadb.autostart.SET_MODE")
                .setPackage("android")
                .putExtra("mode", mode)
                .putExtra("port", port));
        String notice = AdbModeConfig.MODE_TLS.equals(mode)
                ? getString(R.string.notice_tls_applied)
                : getString(R.string.notice_tcp_applied, port);
        refreshAll(notice);
        Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        portInput.postDelayed(() -> refreshAll(null), 1500);
    }

    private void refreshAll(String noticeOverride) {
        String mode = AdbModeConfig.getMode(this);
        int port = AdbModeConfig.getTcpPort(this);
        portInput.setText(String.valueOf(port));
        updateModeButtons(mode);
        modeLabel.setText(AdbModeConfig.MODE_TLS.equals(mode)
                ? getString(R.string.mode_tls_current)
                : getString(R.string.mode_tcp_current, port));

        String log = readLog();
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

        String address = resolveAddress(mode, port, log);
        setAddressText(address);
        boolean ready = address != null;
        statusTitle.setText(ready ? R.string.status_connected : R.string.status_waiting_connection);
        if (noticeOverride != null) {
            setNotice(noticeOverride);
        } else if (!copiedFlash) {
            setNotice(ready ? getString(R.string.notice_ready) : getString(R.string.notice_waiting_module));
        }
    }

    private void setAddressText(String address) {
        String text = address == null ? getString(R.string.address_waiting) : address;
        addressView.setText(text);
        addressView.setMaxLines(1);
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADDRESS_MAX_TEXT_SP);
        addressView.post(() -> fitAddressText(text));
    }

    private void fitAddressText(String text) {
        int availableWidth = addressView.getWidth()
                - addressView.getPaddingLeft() - addressView.getPaddingRight();
        if (availableWidth <= 0) return;

        addressView.setMaxLines(1);
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADDRESS_MAX_TEXT_SP);
        float measuredWidth = addressView.getPaint().measureText(text);
        float scale = measuredWidth <= 0f ? ADDRESS_MAX_TEXT_SP
                : ADDRESS_MAX_TEXT_SP * availableWidth / measuredWidth;
        if (scale >= ADDRESS_MIN_TEXT_SP) {
            addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    Math.min(ADDRESS_MAX_TEXT_SP, scale));
        } else {
            addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADDRESS_MIN_TEXT_SP);
            addressView.setMaxLines(2);
        }
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
        copyLabel.setText(R.string.action_copied);
        copyLabel.postDelayed(() -> {
            copiedFlash = false;
            copyIcon.setImageResource(R.drawable.ic_copy);
            copyLabel.setText(R.string.action_copy);
        }, 1600);
    }

    private String readLog() {
        return EventLog.readMerged(this);
    }

    /** 只展示最近几条，倒序（最新在上）。 */
    private String recentLogLines(String log, int limit) {
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
            out.append(LogLocalizer.localize(this, lines[i]));
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
}
