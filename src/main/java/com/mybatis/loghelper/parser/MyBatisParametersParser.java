package com.mybatis.loghelper.parser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameters 行解析器.
 *
 * <p>该类将 MyBatis 日志中的 Parameters 文本解析为结构化参数，并转换为 SQL 可直接替换的字面量。</p>
 *
 * <p>核心特性：
 * <ul>
 *     <li>支持 {@code value(type)} 和仅 {@code value} 两种输入格式</li>
 *     <li>采用“括号层级安全”分词：仅在括号层级为 0 时按逗号切分</li>
 *     <li>支持值中包含逗号、括号、空格等复杂内容</li>
 *     <li>支持数字、布尔、日期时间等类型输出规则</li>
 *     <li>在歧义场景生成 warning，保证“尽可能可用”的回退行为</li>
 * </ul>
 */
public final class MyBatisParametersParser {
    /**
     * 日期格式非法时使用的回退格式.
     */
    private static final String FALLBACK_DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * typed token 匹配模式：value(type).
     */
    private static final Pattern TOKEN_WITH_TYPE_PATTERN = Pattern.compile("^(.*)\\(([^()]+)\\)$");

    /**
     * 类型名合法字符模式.
     */
    private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_.$]*$");

    /**
     * 数字字面量模式（支持小数和科学计数法）.
     */
    private static final Pattern NUMERIC_LITERAL_PATTERN = Pattern.compile("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");

    /**
     * 解析 Parameters 原文.
     *
     * @param parametersRaw Parameters 行中“Parameters:”后面的原始内容
     * @param options       SQL 渲染选项
     * @return 参数解析结果（包含参数列表和警告列表）
     */
    public ParameterParseResult parse(String parametersRaw, SqlRenderOptions options) {
        List<ParameterValue> values = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (parametersRaw == null || parametersRaw.isBlank()) {
            return new ParameterParseResult(values, warnings);
        }

        List<String> tokens = splitTokens(parametersRaw, warnings);
        for (String token : tokens) {
            values.add(toSqlLiteral(token.trim(), options, warnings));
        }
        return new ParameterParseResult(values, warnings);
    }

    /**
     * 将原始参数串拆分为 token 列表.
     *
     * <p>切分策略：
     * <ul>
     *     <li>仅在“括号层级为 0”且“不在双引号中”时，逗号才作为分隔符</li>
     *     <li>先切分为 segments，再做一次“完整 token 合并”处理</li>
 * </ul>
     *
     * @param raw      原始参数串
     * @param warnings warning 收集器
     * @return token 列表
     */
    private List<String> splitTokens(String raw, List<String> warnings) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        boolean inDoubleQuote = false;

        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) {
                inDoubleQuote = !inDoubleQuote;
                current.append(ch);
                continue;
            }

            if (!inDoubleQuote) {
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')' && parenDepth > 0) {
                    parenDepth--;
                } else if (ch == ',' && parenDepth == 0) {
                    addToken(segments, current);
                    continue;
                }
            }
            current.append(ch);
        }

        addToken(segments, current);
        return mergeSegments(segments, warnings);
    }

    /**
     * 将当前缓冲区加入 token 列表并清空缓冲.
     *
     * @param tokens 目标列表
     * @param current 当前缓冲
     */
    private void addToken(List<String> tokens, StringBuilder current) {
        String token = current.toString().trim();
        if (!token.isEmpty()) {
            tokens.add(token);
        }
        current.setLength(0);
    }

    /**
     * 将预切分片段合并为“尽可能完整”的 token.
     *
     * <p>例如：{@code hello, world(String)} 会先切成两个 segment，
     * 再合并回一个 token.</p>
     *
     * @param segments segment 列表
     * @param warnings warning 收集器
     * @return 合并后的 token 列表
     */
    private List<String> mergeSegments(List<String> segments, List<String> warnings) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String segment : segments) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(segment);

            String candidate = buffer.toString().trim();
            if (isLikelyCompleteToken(candidate)) {
                tokens.add(candidate);
                buffer.setLength(0);
            }
        }

        // 如果最后仍有残留，按一个 token 输出，同时记录歧义 warning.
        if (buffer.length() > 0) {
            String unresolved = buffer.toString().trim();
            if (unresolved.contains(",")) {
                warnings.add("Ambiguous parameter tokenization near: " + unresolved);
            }
            tokens.add(unresolved);
        }
        return tokens;
    }

    /**
     * 判断字符串是否“看起来像一个完整 token”.
     *
     * @param token 待判断文本
     * @return true 表示可作为完整 token
     */
    private boolean isLikelyCompleteToken(String token) {
        if (isQuotedToken(token)) {
            return true;
        }
        if (isBooleanLiteral(token) || NUMERIC_LITERAL_PATTERN.matcher(token).matches() || "null".equalsIgnoreCase(token)) {
            return true;
        }

        Matcher matcher = TOKEN_WITH_TYPE_PATTERN.matcher(token);
        if (!matcher.matches()) {
            return false;
        }

        String rawValue = unquoteDouble(matcher.group(1).trim());
        String type = matcher.group(2).trim().toLowerCase(Locale.ROOT);
        return looksLikeType(type) && isTypeCompatible(rawValue, type);
    }

    /**
     * 判断是否是完整引号包裹的 token.
     *
     * @param token 待判断文本
     * @return true 表示首尾引号匹配
     */
    private boolean isQuotedToken(String token) {
        return (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\""))
                || (token.length() >= 2 && token.startsWith("'") && token.endsWith("'"));
    }

    /**
     * 将单个 token 转换为 SQL 字面量.
     *
     * @param token    单个参数 token
     * @param options  渲染选项
     * @param warnings warning 收集器
     * @return 参数值对象（保留原 token + 转换后的 SQL 字面量）
     */
    private ParameterValue toSqlLiteral(String token, SqlRenderOptions options, List<String> warnings) {
        String normalizedToken = token.trim();
        if ("null".equalsIgnoreCase(normalizedToken)) {
            return new ParameterValue(token, "NULL");
        }

        Matcher matcher = TOKEN_WITH_TYPE_PATTERN.matcher(normalizedToken);
        if (!matcher.matches()) {
            return new ParameterValue(token, toUntypedLiteral(normalizedToken, options, warnings));
        }

        String rawValue = unquoteDouble(matcher.group(1).trim());
        String type = matcher.group(2).trim().toLowerCase(Locale.ROOT);
        if (!looksLikeType(type)) {
            return new ParameterValue(token, toUntypedLiteral(normalizedToken, options, warnings));
        }
        if (!isTypeCompatible(rawValue, type)) {
            warnings.add("Type/value mismatch, treated as untyped value: " + token);
            return new ParameterValue(token, toUntypedLiteral(rawValue, options, warnings));
        }

        if ("null".equalsIgnoreCase(rawValue)) {
            return new ParameterValue(token, "NULL");
        }
        if (isNumericType(type)) {
            return new ParameterValue(token, rawValue);
        }
        if (isBooleanType(type)) {
            return new ParameterValue(token, formatBoolean(rawValue, options.booleanAsOneZero(), warnings));
        }
        if (isDateType(type)) {
            return new ParameterValue(token, formatDateTime(rawValue, options.dateTimePattern(), warnings));
        }
        return new ParameterValue(token, quote(rawValue));
    }

    /**
     * 判断“值与类型”是否基本匹配.
     *
     * @param rawValue 参数原始值
     * @param type     类型名（小写）
     * @return true 表示兼容
     */
    private boolean isTypeCompatible(String rawValue, String type) {
        if ("null".equalsIgnoreCase(rawValue)) {
            return true;
        }
        if (isNumericType(type)) {
            return NUMERIC_LITERAL_PATTERN.matcher(rawValue).matches();
        }
        if (isBooleanType(type)) {
            return isBooleanLiteral(rawValue);
        }
        return true;
    }

    /**
     * 无类型 token 的字面量转换规则.
     *
     * @param token    token 文本
     * @param options  渲染选项
     * @param warnings warning 收集器
     * @return SQL 字面量
     */
    private String toUntypedLiteral(String token, SqlRenderOptions options, List<String> warnings) {
        String rawValue = unquote(token);
        if ("null".equalsIgnoreCase(rawValue)) {
            return "NULL";
        }
        if (isBooleanLiteral(rawValue)) {
            return formatBoolean(rawValue, options.booleanAsOneZero(), warnings);
        }
        if (NUMERIC_LITERAL_PATTERN.matcher(rawValue).matches()) {
            return rawValue;
        }
        return quote(rawValue);
    }

    /**
     * 判断是否是布尔字面量.
     *
     * @param value 值文本
     * @return true 表示 true/false/1/0
     */
    private boolean isBooleanLiteral(String value) {
        return "true".equalsIgnoreCase(value)
                || "false".equalsIgnoreCase(value)
                || "1".equals(value)
                || "0".equals(value);
    }

    /**
     * 判断类型名是否是插件可识别类型.
     *
     * @param type 类型名（小写）
     * @return true 表示可识别
     */
    private boolean looksLikeType(String type) {
        if (!TYPE_NAME_PATTERN.matcher(type).matches()) {
            return false;
        }
        String normalized = type.toLowerCase(Locale.ROOT);
        return isNumericType(normalized)
                || isBooleanType(normalized)
                || isDateType(normalized)
                || normalized.contains("string")
                || normalized.contains("char")
                || normalized.equals("object");
    }

    /**
     * 判断是否为数值类型.
     *
     * @param type 类型名（小写）
     * @return true 表示数值类型
     */
    private boolean isNumericType(String type) {
        return type.contains("int")
                || type.contains("long")
                || type.contains("short")
                || type.contains("byte")
                || type.contains("double")
                || type.contains("float")
                || type.contains("bigdecimal")
                || type.contains("biginteger")
                || type.contains("decimal")
                || type.contains("number");
    }

    /**
     * 判断是否为布尔类型.
     *
     * @param type 类型名（小写）
     * @return true 表示布尔类型
     */
    private boolean isBooleanType(String type) {
        return type.contains("boolean");
    }

    /**
     * 判断是否为日期时间类型.
     *
     * @param type 类型名（小写）
     * @return true 表示日期时间类型
     */
    private boolean isDateType(String type) {
        return type.contains("date")
                || type.contains("time")
                || type.contains("timestamp")
                || type.contains("localdatetime")
                || type.contains("localdate")
                || type.contains("localtime");
    }

    /**
     * 格式化布尔值.
     *
     * @param rawValue  布尔原文
     * @param asOneZero 是否输出为 1/0
     * @param warnings  warning 收集器
     * @return SQL 字面量文本
     */
    private String formatBoolean(String rawValue, boolean asOneZero, List<String> warnings) {
        if ("true".equalsIgnoreCase(rawValue) || "1".equals(rawValue)) {
            return asOneZero ? "1" : "TRUE";
        }
        if ("false".equalsIgnoreCase(rawValue) || "0".equals(rawValue)) {
            return asOneZero ? "0" : "FALSE";
        }
        warnings.add("Invalid boolean value, treated as string: " + rawValue);
        return quote(rawValue);
    }

    /**
     * 格式化日期时间值.
     *
     * @param rawValue 原始值
     * @param pattern  目标格式
     * @param warnings warning 收集器
     * @return SQL 字面量文本
     */
    private String formatDateTime(String rawValue, String pattern, List<String> warnings) {
        LocalDateTime parsed = parseDateTime(rawValue);
        if (parsed == null) {
            return quote(rawValue);
        }

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException ex) {
            warnings.add("Invalid DateTime pattern, fallback to " + FALLBACK_DATE_TIME_PATTERN);
            formatter = DateTimeFormatter.ofPattern(FALLBACK_DATE_TIME_PATTERN);
        }
        return quote(parsed.format(formatter));
    }

    /**
     * 尝试解析日期时间文本.
     *
     * <p>解析顺序：
     * <ol>
     *     <li>常见 LocalDateTime 文本格式</li>
     *     <li>ISO LocalDate（补零点）</li>
     *     <li>13 位毫秒时间戳</li>
     *     <li>10 位秒时间戳</li>
     *     <li>java.util.Date.toString() 风格文本</li>
     * </ol>
     *
     * @param rawValue 原始日期文本
     * @return 解析成功返回 LocalDateTime，失败返回 null
     */
    private LocalDateTime parseDateTime(String rawValue) {
        List<DateTimeFormatter> dateTimePatterns = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        );
        for (DateTimeFormatter formatter : dateTimePatterns) {
            try {
                return LocalDateTime.parse(rawValue, formatter);
            } catch (DateTimeParseException ignored) {
                // 当前格式不匹配，继续尝试下一种格式.
            }
        }

        try {
            return LocalDate.parse(rawValue, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试时间戳和其他格式.
        }

        if (rawValue.matches("\\d{13}")) {
            long epochMilli = Long.parseLong(rawValue);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
        }
        if (rawValue.matches("\\d{10}")) {
            long epochSecond = Long.parseLong(rawValue);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneId.systemDefault());
        }

        try {
            DateTimeFormatter javaUtilDateFormatter = DateTimeFormatter.ofPattern(
                    "EEE MMM dd HH:mm:ss zzz yyyy",
                    Locale.ENGLISH
            );
            ZonedDateTime zonedDateTime = ZonedDateTime.parse(rawValue, javaUtilDateFormatter);
            return zonedDateTime.toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * 将普通文本转成 SQL 字符串字面量.
     *
     * <p>SQL 单引号转义规则：{@code ' -> ''}</p>
     *
     * @param value 原始值
     * @return 带单引号包裹的 SQL 字面量
     */
    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 去除外层单引号或双引号（若存在）.
     *
     * @param value 原始文本
     * @return 去引号后的文本
     */
    private String unquote(String value) {
        String unquotedDouble = unquoteDouble(value);
        if (unquotedDouble.length() >= 2 && unquotedDouble.startsWith("'") && unquotedDouble.endsWith("'")) {
            return unquotedDouble.substring(1, unquotedDouble.length() - 1);
        }
        return unquotedDouble;
    }

    /**
     * 去除外层双引号（若存在）.
     *
     * @param value 原始文本
     * @return 去双引号后的文本
     */
    private String unquoteDouble(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
