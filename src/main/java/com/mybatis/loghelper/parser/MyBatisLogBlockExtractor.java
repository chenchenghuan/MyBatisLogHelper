package com.mybatis.loghelper.parser;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MyBatis 日志块提取器.
 *
 * <p>职责：在控制台全文中，从“光标附近”定位一组日志块：
 * <ul>
 *     <li>Preparing 行：包含 SQL 模板</li>
 *     <li>Parameters 行：包含参数列表</li>
 * </ul>
 *
 * <p>提取时支持多行 SQL 续行，并尽量避免串到下一段无关日志。</p>
 */
public final class MyBatisLogBlockExtractor {
    /**
     * Preparing 行识别关键字.
     */
    private static final String[] PREPARING_MARKERS = {"==>  Preparing:", "Preparing:"};

    /**
     * Parameters 行识别关键字.
     */
    private static final String[] PARAMETERS_MARKERS = {"==> Parameters:", "Parameters:"};

    /**
     * 常见 SQL 续行前缀关键字（用于无缩进续行识别）。
     */
    private static final String[] SQL_CONTINUATION_PREFIXES = {
            "select", "from", "where", "and", "or", "join", "left", "right", "inner", "outer",
            "on", "having", "group", "order", "limit", "offset", "union", "values", "set",
            "insert", "update", "delete", "into"
    };

    /**
     * 条件表达式模式（用于判断某行可能是 SQL 续行）。
     */
    private static final Pattern SQL_CONDITION_PATTERN = Pattern.compile(
            "^[A-Za-z_`\\[\\]\"]\\S*\\s*(=|<>|!=|>|<|>=|<=|like\\b|in\\b|is\\b).*$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 从控制台全文提取日志块.
     *
     * @param consoleText 控制台全文
     * @param caretLine   当前光标行号（0 基）
     * @return 提取结果（成功返回 block，失败返回错误信息）
     */
    public LogBlockExtractResult extract(String consoleText, int caretLine) {
        String[] lines = consoleText.split("\\R", -1);
        if (lines.length == 0) {
            return LogBlockExtractResult.failure("Console is empty.");
        }

        // 1) 光标行归一化，避免越界.
        int safeCaret = Math.max(0, Math.min(caretLine, lines.length - 1));

        // 2) 先找离光标最近的 Preparing 行.
        int preparingLine = findNearestPreparingLine(lines, safeCaret);
        if (preparingLine < 0) {
            return LogBlockExtractResult.failure("No Preparing line found near the caret.");
        }

        // 3) 再从 Preparing 往下找同一块对应的 Parameters 行.
        String preparingPrefix = extractPrefix(lines[preparingLine], PREPARING_MARKERS);
        int parametersLine = findParametersLine(lines, preparingLine + 1, preparingPrefix);
        if (parametersLine < 0) {
            return LogBlockExtractResult.failure("Preparing found, but matching Parameters line was not found.");
        }

        // 4) 拼接 SQL：Preparing 到 Parameters 之间的非空续行会拼到同一个 SQL 模板中.
        StringBuilder sql = new StringBuilder(extractAfterMarker(lines[preparingLine], PREPARING_MARKERS).trim());
        for (int i = preparingLine + 1; i < parametersLine; i++) {
            String part = lines[i].trim();
            if (!part.isEmpty()) {
                if (sql.length() > 0) {
                    sql.append(' ');
                }
                sql.append(part);
            }
        }

        // 5) 提取 Parameters 原文.
        String parametersRaw = extractAfterMarker(lines[parametersLine], PARAMETERS_MARKERS).trim();
        if (sql.isEmpty()) {
            return LogBlockExtractResult.failure("Preparing line did not contain a SQL template.");
        }

        return LogBlockExtractResult.success(
                new MyBatisLogBlock(sql.toString(), parametersRaw, preparingLine, parametersLine)
        );
    }

    /**
     * 以光标行为中心，按“距离扩散”查找最近 Preparing 行.
     *
     * @param lines     控制台按行数组
     * @param caretLine 光标行
     * @return 找到返回行号，未找到返回 -1
     */
    private int findNearestPreparingLine(String[] lines, int caretLine) {
        for (int delta = 0; delta < lines.length; delta++) {
            int up = caretLine - delta;
            if (up >= 0 && containsAny(lines[up], PREPARING_MARKERS)) {
                return up;
            }

            int down = caretLine + delta;
            if (delta > 0 && down < lines.length && containsAny(lines[down], PREPARING_MARKERS)) {
                return down;
            }
        }
        return -1;
    }

    /**
     * 从指定起点向下查找 Parameters 行.
     *
     * <p>查找过程中如果遇到下一条 Preparing，或者遇到明显非续行内容，
     * 判定当前日志块已经结束，返回 -1。</p>
     *
     * @param lines          控制台按行数组
     * @param startLine      起始扫描行（通常为 Preparing 下一行）
     * @param preparingPrefix Preparing 行前缀（如时间戳/日志级别）
     * @return 找到返回行号，未找到返回 -1
     */
    private int findParametersLine(String[] lines, int startLine, String preparingPrefix) {
        for (int i = startLine; i < lines.length; i++) {
            if (containsAny(lines[i], PARAMETERS_MARKERS)) {
                return i;
            }
            if (containsAny(lines[i], PREPARING_MARKERS)) {
                return -1;
            }

            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!isContinuationLine(lines[i], preparingPrefix)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 判断一行是否可视为 SQL 续行.
     *
     * <p>判定规则依次为：
     * <ol>
     *     <li>与 Preparing 行共享同一日志前缀</li>
     *     <li>行首为空白（缩进续行）</li>
     *     <li>包含占位符 '?'（常见 SQL 模板）</li>
     *     <li>匹配条件表达式模式</li>
     *     <li>以常见 SQL 关键字开头</li>
     * </ol>
     *
     * @param line            当前行
     * @param preparingPrefix Preparing 行前缀
     * @return true 表示是续行，false 表示可能是无关日志
     */
    private boolean isContinuationLine(String line, String preparingPrefix) {
        if (!preparingPrefix.isEmpty() && line.startsWith(preparingPrefix)) {
            return true;
        }
        if (line.startsWith(" ") || line.startsWith("\t")) {
            return true;
        }

        String trimmed = line.trim();
        if (trimmed.contains("?")) {
            return true;
        }
        if (SQL_CONDITION_PATTERN.matcher(trimmed).matches()) {
            return true;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : SQL_CONTINUATION_PREFIXES) {
            if (lower.startsWith(prefix + " ") || lower.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一行是否包含任一标记.
     *
     * @param line    当前行
     * @param markers 标记数组
     * @return 是否命中
     */
    private boolean containsAny(String line, String[] markers) {
        for (String marker : markers) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取某个标记后面的正文.
     *
     * @param line    原始行
     * @param markers 标记数组
     * @return 标记后正文；若未命中标记则返回原行
     */
    private String extractAfterMarker(String line, String[] markers) {
        for (String marker : markers) {
            int index = line.indexOf(marker);
            if (index >= 0) {
                return line.substring(index + marker.length());
            }
        }
        return line;
    }

    /**
     * 提取标记前缀（通常是日志头部）.
     *
     * @param line    原始行
     * @param markers 标记数组
     * @return 前缀；若未命中标记返回空串
     */
    private String extractPrefix(String line, String[] markers) {
        for (String marker : markers) {
            int index = line.indexOf(marker);
            if (index >= 0) {
                return line.substring(0, index);
            }
        }
        return "";
    }
}
