package com.mybatis.loghelper.history;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * SQL 历史记录条目。
 *
 * @param sql             还原后的 SQL
 * @param timestampMillis 记录时间（毫秒）
 * @param preparingRaw    Preparing 行提取的 SQL 模板（不含前缀）
 * @param parametersRaw   Parameters 行提取的原始参数串（不含前缀）
 */
public record SqlHistoryEntry(String sql, long timestampMillis, String preparingRaw, String parametersRaw) {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public String displayText(int maxSnippetLength) {
        // 使用中括号包裹时间，便于识别
        return "[" + formatTime() + "] " + abbreviate(sql, maxSnippetLength);
    }

    // 仅返回时间文本（用于列表中单独渲染）
    public String timeText() {
        return formatTime();
    }

    // 返回 SQL 摘要文本（用于列表中单独渲染）
    public String snippetText(int maxSnippetLength) {
        return abbreviate(sql, maxSnippetLength);
    }

    /**
     * 输出原始日志内容（Preparing + Parameters）。
     *
     * @return 原始日志文本
     */
    public String rawLogText() {
        String preparing = normalize(preparingRaw);
        String parameters = normalize(parametersRaw);
        if (preparing.isEmpty() && parameters.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (!preparing.isEmpty()) {
            builder.append("Preparing: ").append(preparing);
        }
        if (!parameters.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("Parameters: ").append(parameters);
        }
        return builder.toString();
    }

    private String formatTime() {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis));
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }
}
