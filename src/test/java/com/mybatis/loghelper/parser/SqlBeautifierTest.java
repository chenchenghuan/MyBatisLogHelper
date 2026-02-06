package com.mybatis.loghelper.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlBeautifier} 单元测试.
 */
class SqlBeautifierTest {
    /**
     * 被测对象.
     */
    private final SqlBeautifier beautifier = new SqlBeautifier();

    /**
     * 验证常见 SELECT 语句会按子句换行.
     */
    @Test
    void beautifySelectStatement() {
        String sql = "select id,name from t_user where age > ? and status = ? order by id desc;";
        String formatted = beautifier.beautify(sql);

        assertTrue(formatted.contains("SELECT"));
        assertTrue(formatted.contains("\nFROM "));
        assertTrue(formatted.contains("\nWHERE "));
        assertTrue(formatted.contains("\nORDER BY "));
    }

    /**
     * 验证字符串字面量中的逗号不会被破坏.
     */
    @Test
    void preserveCommaInsideQuotedLiteral() {
        String sql = "select * from t_user where nickname = 'A,B' and id = ?;";
        String formatted = beautifier.beautify(sql);

        assertTrue(formatted.contains("'A,B'"));
        assertTrue(formatted.contains("\nWHERE "));
    }
}
