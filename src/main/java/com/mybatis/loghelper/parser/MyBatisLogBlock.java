package com.mybatis.loghelper.parser;

/**
 * MyBatis 日志块数据对象.
 *
 * @param sqlTemplate   从 Preparing 行(含中间续行)提取出的 SQL 模板
 * @param parametersRaw 从 Parameters 行提取出的原始参数串
 * @param preparingLine Preparing 行在控制台文本中的行号(0 基)
 * @param parametersLine Parameters 行在控制台文本中的行号(0 基)
 */
public record MyBatisLogBlock(
        String sqlTemplate,
        String parametersRaw,
        int preparingLine,
        int parametersLine
) {
}
