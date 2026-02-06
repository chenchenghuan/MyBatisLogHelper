package com.mybatis.loghelper.parser;

/**
 * 单个参数值对象.
 *
 * @param originalToken Parameters 行中的原始 token（便于排障）
 * @param sqlLiteral    转换后的 SQL 字面量文本
 */
public record ParameterValue(String originalToken, String sqlLiteral) {
}
