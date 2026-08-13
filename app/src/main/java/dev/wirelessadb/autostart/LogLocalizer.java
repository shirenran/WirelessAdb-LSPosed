package dev.wirelessadb.autostart;

import android.content.Context;

import java.util.Locale;

/** Localizes hook records while keeping the raw history backward-compatible. */
public final class LogLocalizer {
    private LogLocalizer() {}

    public static String localize(Context context, String line) {
        if (line == null || !supportsTranslation(context)) return line;
        int separator = line.indexOf("  ");
        if (separator < 0) return localizeMessage(context, line);
        return line.substring(0, separator + 2)
                + localizeMessage(context, line.substring(separator + 2));
    }

    private static String localizeMessage(Context context, String message) {
        String out = message;

        out = replace(context, out, "Command receivers ready (copy / set_mode / apply)", R.string.log_command_receivers_ready);
        out = replace(context, out, "Command receiver failed: ", R.string.log_command_receiver_failed_prefix);
        out = replace(context, out, "Manual copy failed: ", R.string.log_manual_copy_failed_prefix);
        out = replace(context, out, "Manual copy requested (immediate): ", R.string.log_manual_copy_requested_prefix);

        out = replace(context, out, "获取系统上下文失败：", R.string.log_context_failed_prefix);
        out = replace(context, out, "界面请求立即应用", R.string.log_apply_reason);
        out = replace(context, out, "切换模式 → ", R.string.log_mode_switch_prefix);
        out = replace(context, out, "切换模式", R.string.log_mode_switch_reason);
        out = replace(context, out, "模式已保存，解锁后生效", R.string.log_mode_saved);
        out = replace(context, out, "当前模式：", R.string.log_current_mode_prefix);
        out = replace(context, out, "读取配置失败：", R.string.log_config_failed_prefix);
        out = replace(context, out, "检测到微信输入法进程启动", R.string.log_wetype_started);
        out = replace(context, out, "收到 USER_UNLOCKED", R.string.log_user_unlocked_event);
        out = replace(context, out, "系统已解锁", R.string.log_system_unlocked);
        out = replace(context, out, "系统已启动，等待首次解锁", R.string.log_system_waiting_unlock);
        out = replace(context, out, "首次解锁", R.string.log_initial_unlock);
        out = replace(context, out, "监听解锁失败：", R.string.log_unlock_failed_prefix);
        out = replace(context, out, "，开始保持 ADB（", R.string.log_keep_adb_separator);
        out = replace(context, out, "开关变为开启", R.string.log_wifi_switch_enabled);
        out = replace(context, out, "检测到无线调试被关闭，准备重新开启", R.string.log_wireless_disabled);
        out = replace(context, out, "已注册 adb_wifi_enabled 监听（仅 TLS 模式生效）", R.string.log_wifi_observer_registered);
        out = replace(context, out, "注册无线调试开关监听失败：", R.string.log_wifi_observer_failed_prefix);
        out = replace(context, out, "ConnectivityManager 不可用", R.string.log_connectivity_unavailable);
        out = replace(context, out, "Wi-Fi 可用，检查 ADB（", R.string.log_wifi_available_prefix);
        out = replace(context, out, "Wi-Fi 恢复", R.string.log_wifi_restored);
        out = replace(context, out, "Wi-Fi 断开，等待恢复后再保持 ADB", R.string.log_wifi_disconnected);
        out = replace(context, out, "已注册 Wi-Fi 网络回调", R.string.log_wifi_callback_registered);
        out = replace(context, out, "注册 Wi-Fi 回调失败：", R.string.log_wifi_callback_failed_prefix);
        out = replace(context, out, "已注册亮屏检查", R.string.log_screen_registered);
        out = replace(context, out, "注册亮屏检查失败：", R.string.log_screen_failed_prefix);
        out = replace(context, out, "亮屏检查", R.string.log_screen_check);
        out = replace(context, out, "跳过重复开启（冷却中，原因：", R.string.log_skip_cooldown_prefix);
        out = replace(context, out, "开启进行中，跳过：", R.string.log_enable_in_progress_prefix);
        out = replace(context, out, "开启 ADB 失败：", R.string.log_enable_failed_prefix);
        out = replace(context, out, "TLS 模式：已清除 service.adb.tcp.port（原 ", R.string.log_tls_cleared_prefix);
        out = replace(context, out, "切回 TLS", R.string.log_switch_to_tls);
        out = replace(context, out, "清除 TCP 端口时出错（可忽略）：", R.string.log_tcp_clear_failed_prefix);
        out = replace(context, out, "已请求开启无线调试 TLS", R.string.log_tls_requested);
        out = replace(context, out, "无线调试设置未变化（可能已经开启）", R.string.log_tls_unchanged);
        out = replace(context, out, "，原因：", R.string.log_reason_separator);
        out = replace(context, out, "已设置 ", R.string.log_tcp_set_prefix);
        out = replace(context, out, "TCP 模式", R.string.log_tcp_mode);
        out = replace(context, out, "已请求重启 adbd（", R.string.log_restart_requested_prefix);
        out = replace(context, out, "重启 adbd 失败：", R.string.log_restart_failed_prefix);
        out = replace(context, out, "二次确认", R.string.log_second_confirmation);
        out = replace(context, out, "等待地址就绪（", R.string.log_waiting_address_prefix);
        out = replace(context, out, "，第 ", R.string.log_attempt_separator);
        out = replace(context, out, "TCP 轮询重试", R.string.log_tcp_poll_retry);
        out = replace(context, out, "获取地址失败（", R.string.log_address_failed_prefix);
        out = replace(context, out, "手动/强制复制", R.string.log_manual_copy);
        out = replace(context, out, "微信输入法已经运行", R.string.log_wetype_running);
        out = replace(context, out, "无线 ADB 地址已就绪，等待微信输入法启动后复制：", R.string.log_address_ready_prefix);
        out = replace(context, out, "，等待 3 秒初始化跨设备粘贴服务", R.string.log_paste_wait);
        out = replace(context, out, "SystemProperties 不可用", R.string.log_system_properties_unavailable);
        out = replace(context, out, "剪贴板服务不可用", R.string.log_clipboard_unavailable);
        out = replace(context, out, "已复制无线 ADB 地址：", R.string.log_address_copied_prefix);
        out = replace(context, out, "复制地址失败：", R.string.log_copy_failed_prefix);

        out = replace(context, out, "TLS 无线调试（随机端口）", R.string.log_mode_tls);
        out = replace(context, out, "TCP 固定端口", R.string.log_mode_tcp);
        out = replace(context, out, "无线 ADB 地址", R.string.log_wireless_address);
        out = replace(context, out, "模式", R.string.log_mode_word);
        out = replace(context, out, "端口", R.string.log_port_word);

        return out.replace("，", ", ")
                .replace("：", ": ")
                .replace("（", " (")
                .replace("）", ")")
                .replace(" 次", "");
    }

    private static String replace(Context context, String source, String original, int resource) {
        String replacement = context.getString(resource);
        if (original.endsWith(" ") || needsTrailingSpace(resource)) replacement += " ";
        return source.replace(original, replacement);
    }

    private static boolean needsTrailingSpace(int resource) {
        return resource == R.string.log_context_failed_prefix
                || resource == R.string.log_current_mode_prefix
                || resource == R.string.log_config_failed_prefix
                || resource == R.string.log_unlock_failed_prefix
                || resource == R.string.log_wifi_observer_failed_prefix
                || resource == R.string.log_wifi_callback_failed_prefix
                || resource == R.string.log_enable_in_progress_prefix
                || resource == R.string.log_enable_failed_prefix
                || resource == R.string.log_tcp_clear_failed_prefix
                || resource == R.string.log_restart_failed_prefix
                || resource == R.string.log_reason_separator
                || resource == R.string.log_address_ready_prefix
                || resource == R.string.log_address_copied_prefix
                || resource == R.string.log_copy_failed_prefix;
    }

    private static boolean supportsTranslation(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        String language = locale.getLanguage();
        return "en".equals(language) || "pt".equals(language) || "es".equals(language);
    }
}
