package com.mybatis.loghelper.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 还原器.
 *
 * <p>负责将 SQL 模板中的 {@code ?} 占位符按顺序替换为参数字面量，
 * 并在数量不匹配时给出可读 warning。</p>
 */
public final class SqlRestorer {
    /**
     * 参数解析器.
     */
    private final MyBatisParametersParser parametersParser = new MyBatisParametersParser();

    /**
     * 使用日志块对象执行还原.
     *
     * @param block   已提取日志块
     * @param options 渲染选项
     * @return 还原结果
     */
    public SqlRestoreResult restore(MyBatisLogBlock block, SqlRenderOptions options) {
        ParameterParseResult parseResult = parametersParser.parse(block.parametersRaw(), options);
        return restore(block.sqlTemplate(), parseResult);
    }

    /**
     * 使用原始 SQL 模板和参数串执行还原.
     *
     * @param sqlTemplate   SQL 模板（包含 ?）
     * @param parametersRaw 参数原文
     * @param options       渲染选项
     * @return 还原结果
     */
    public SqlRestoreResult restore(String sqlTemplate, String parametersRaw, SqlRenderOptions options) {
        ParameterParseResult parseResult = parametersParser.parse(parametersRaw, options);
        return restore(sqlTemplate, parseResult);
    }

    /**
     * 核心替换逻辑.
     *
     * <p>注意：仅替换 SQL 字符串字面量外部的 {@code ?}，
     * 字符串内部的 {@code ?} 必须保留。</p>
     *
     * @param sqlTemplate SQL 模板
     * @param parseResult 参数解析结果
     * @return 还原结果
     */
    private SqlRestoreResult restore(String sqlTemplate, ParameterParseResult parseResult) {
        List<ParameterValue> params = parseResult.values();
        List<String> warnings = new ArrayList<>(parseResult.warnings());

        StringBuilder out = new StringBuilder();
        int placeholderCount = 0;
        int paramIndex = 0;
        boolean inSingleQuotedString = false;

        for (int i = 0; i < sqlTemplate.length(); i++) {
            char c = sqlTemplate.charAt(i);
            if (c == '\'') {
                // SQL 中两个连续单引号表示转义，不切换字符串状态.
                if (inSingleQuotedString && i + 1 < sqlTemplate.length() && sqlTemplate.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                    continue;
                }
                inSingleQuotedString = !inSingleQuotedString;
                out.append(c);
                continue;
            }

            if (c == '?' && !inSingleQuotedString) {
                placeholderCount++;
                if (paramIndex < params.size()) {
                    out.append(params.get(paramIndex).sqlLiteral());
                } else {
                    // 参数不足时保留原 ?，并通过 warning 告知用户.
                    out.append('?');
                }
                paramIndex++;
                continue;
            }

            out.append(c);
        }

        // 占位符数量和参数数量不一致时，结果仍返回，但明确告警.
        if (placeholderCount != params.size()) {
            warnings.add(
                    "Placeholder count (" + placeholderCount + ") does not match parameter count ("
                            + params.size() + "). Result is partially restored."
            );
        }

        String restored = out.toString().trim();
        // 统一补分号，便于直接执行.
        if (!restored.endsWith(";")) {
            restored = restored + ";";
        }
        return new SqlRestoreResult(restored, warnings);
    }
}
