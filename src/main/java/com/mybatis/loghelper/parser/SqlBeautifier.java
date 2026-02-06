package com.mybatis.loghelper.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 美化器.
 *
 * <p>该类用于将单行或可读性较差的 SQL 文本做基础排版，
 * 便于在结果弹窗中阅读和复制。</p>
 *
 * <p>设计目标：
 * <ul>
 *     <li>不依赖数据库方言库，保持轻量</li>
 *     <li>对常见 SQL（SELECT/UPDATE/INSERT/DELETE）提供可读换行</li>
 *     <li>避免破坏字符串字面量内容</li>
 * </ul>
 */
public final class SqlBeautifier {
    /**
     * 需要独立成行的主要子句关键字.
     */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET",
            "GROUP BY", "ORDER BY", "INSERT INTO", "VALUES",
            "UPDATE", "SET", "DELETE FROM", "JOIN", "LEFT JOIN",
            "RIGHT JOIN", "INNER JOIN", "OUTER JOIN", "ON",
            "UNION", "UNION ALL"
    );

    /**
     * 逻辑连接词关键字.
     */
    private static final Set<String> LOGICAL_KEYWORDS = Set.of("AND", "OR");

    /**
     * 适合在逗号后换行的子句.
     */
    private static final Set<String> COMMA_BREAK_CLAUSES = Set.of("SELECT", "SET", "VALUES");

    /**
     * 4 空格缩进.
     */
    private static final String INDENT = "    ";

    /**
     * 对 SQL 进行格式化.
     *
     * @param sql 原始 SQL
     * @return 美化后的 SQL；当输入为空时返回原值
     */
    public String beautify(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }

        boolean hasSemicolon = sql.trim().endsWith(";");
        List<String> tokens = mergeCompoundTokens(tokenize(sql));
        if (tokens.isEmpty()) {
            return sql;
        }

        StringBuilder out = new StringBuilder();
        int indentLevel = 0;
        boolean lineStart = true;
        String currentClause = "";

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (";".equals(token)) {
                continue;
            }

            String upper = token.toUpperCase(Locale.ROOT);

            if ("(".equals(token)) {
                if (!lineStart && needsSpaceBeforeSymbol(out)) {
                    out.append(' ');
                }
                out.append('(');
                indentLevel++;
                lineStart = false;
                continue;
            }

            if (")".equals(token)) {
                indentLevel = Math.max(0, indentLevel - 1);
                out.append(')');
                lineStart = false;
                continue;
            }

            if (",".equals(token)) {
                out.append(',');
                if (COMMA_BREAK_CLAUSES.contains(currentClause)) {
                    newLine(out);
                    lineStart = true;
                } else {
                    out.append(' ');
                    lineStart = false;
                }
                continue;
            }

            if (CLAUSE_KEYWORDS.contains(upper) || LOGICAL_KEYWORDS.contains(upper)) {
                if (!lineStart) {
                    newLine(out);
                    lineStart = true;
                }

                int clauseIndent = LOGICAL_KEYWORDS.contains(upper) ? indentLevel + 1 : indentLevel;
                appendIndent(out, clauseIndent);
                out.append(upper);
                out.append(' ');
                lineStart = false;

                if (!LOGICAL_KEYWORDS.contains(upper)) {
                    currentClause = upper;
                }
                continue;
            }

            if (lineStart) {
                appendIndent(out, indentLevel + 1);
                lineStart = false;
            } else if (needsSpaceBeforeToken(out)) {
                out.append(' ');
            }

            out.append(token);
        }

        String formatted = out.toString().replaceAll("[ \\t]+\\n", "\n").trim();
        if (hasSemicolon && !formatted.endsWith(";")) {
            formatted = formatted + ";";
        }
        return formatted;
    }

    /**
     * 词法切分 SQL.
     *
     * <p>会保护单引号字符串，不在字符串内部拆分逗号和关键字。</p>
     */
    private List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);

            if (inSingleQuote) {
                current.append(ch);
                if (ch == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append(sql.charAt(i + 1));
                    i++;
                    continue;
                }
                if (ch == '\'') {
                    inSingleQuote = false;
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            if (ch == '\'') {
                flushCurrent(tokens, current);
                inSingleQuote = true;
                current.append(ch);
                continue;
            }

            if (Character.isWhitespace(ch)) {
                flushCurrent(tokens, current);
                continue;
            }

            if (ch == ',' || ch == '(' || ch == ')' || ch == ';') {
                flushCurrent(tokens, current);
                tokens.add(String.valueOf(ch));
                continue;
            }

            current.append(ch);
        }

        flushCurrent(tokens, current);
        return tokens;
    }

    /**
     * 合并多词关键字，便于格式化阶段统一识别.
     */
    private List<String> mergeCompoundTokens(List<String> tokens) {
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String current = tokens.get(i);
            String upper = current.toUpperCase(Locale.ROOT);

            if (i + 1 < tokens.size()) {
                String nextUpper = tokens.get(i + 1).toUpperCase(Locale.ROOT);
                if ("GROUP".equals(upper) && "BY".equals(nextUpper)) {
                    merged.add("GROUP BY");
                    i++;
                    continue;
                }
                if ("ORDER".equals(upper) && "BY".equals(nextUpper)) {
                    merged.add("ORDER BY");
                    i++;
                    continue;
                }
                if ("INSERT".equals(upper) && "INTO".equals(nextUpper)) {
                    merged.add("INSERT INTO");
                    i++;
                    continue;
                }
                if ("DELETE".equals(upper) && "FROM".equals(nextUpper)) {
                    merged.add("DELETE FROM");
                    i++;
                    continue;
                }
                if ("LEFT".equals(upper) && "JOIN".equals(nextUpper)) {
                    merged.add("LEFT JOIN");
                    i++;
                    continue;
                }
                if ("RIGHT".equals(upper) && "JOIN".equals(nextUpper)) {
                    merged.add("RIGHT JOIN");
                    i++;
                    continue;
                }
                if ("INNER".equals(upper) && "JOIN".equals(nextUpper)) {
                    merged.add("INNER JOIN");
                    i++;
                    continue;
                }
                if ("OUTER".equals(upper) && "JOIN".equals(nextUpper)) {
                    merged.add("OUTER JOIN");
                    i++;
                    continue;
                }
                if ("UNION".equals(upper) && "ALL".equals(nextUpper)) {
                    merged.add("UNION ALL");
                    i++;
                    continue;
                }
            }

            merged.add(current);
        }
        return merged;
    }

    /**
     * 追加换行符.
     */
    private void newLine(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
        out.append('\n');
    }

    /**
     * 追加缩进.
     */
    private void appendIndent(StringBuilder out, int level) {
        for (int i = 0; i < Math.max(0, level); i++) {
            out.append(INDENT);
        }
    }

    /**
     * 在合适时机刷新 token 缓冲.
     */
    private void flushCurrent(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    /**
     * 判断符号前是否需要空格.
     */
    private boolean needsSpaceBeforeSymbol(StringBuilder out) {
        if (out.length() == 0) {
            return false;
        }
        char last = out.charAt(out.length() - 1);
        return last != '\n' && last != ' ' && last != '(';
    }

    /**
     * 判断普通 token 前是否需要空格.
     */
    private boolean needsSpaceBeforeToken(StringBuilder out) {
        if (out.length() == 0) {
            return false;
        }
        char last = out.charAt(out.length() - 1);
        return last != '\n' && last != ' ' && last != '(';
    }
}
