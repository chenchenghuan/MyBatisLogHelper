package com.mybatis.loghelper.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlRestorer} 单元测试.
 *
 * <p>覆盖目标：
 * <ul>
 *     <li>基础类型替换（数字、字符串、布尔、日期）</li>
 *     <li>字符串特殊字符处理（逗号、括号、单引号）</li>
 *     <li>参数数量不匹配的 warning 行为</li>
 *     <li>无类型参数与歧义场景回退逻辑</li>
 * </ul>
 */
class SqlRestorerTest {
    /**
     * 被测对象.
     */
    private final SqlRestorer restorer = new SqlRestorer();

    /**
     * 测试辅助方法：使用默认渲染配置执行还原.
     *
     * @param sql    SQL 模板
     * @param params 参数原文
     * @return 还原结果
     */
    private SqlRestoreResult restore(String sql, String params) {
        return restorer.restore(new MyBatisLogBlock(sql, params, 0, 1), SqlRenderOptions.defaults());
    }

    /**
     * 基础场景：Integer + String.
     */
    @Test
    void restoreNumericAndString() {
        SqlRestoreResult result = restore(
                "select * from user where id = ? and name = ?",
                "1(Integer), Tom(String)"
        );
        assertEquals("select * from user where id = 1 and name = 'Tom';", result.restoredSql());
        assertTrue(result.warnings().isEmpty());
    }

    /**
     * null 参数替换为 SQL NULL.
     */
    @Test
    void restoreNull() {
        SqlRestoreResult result = restore(
                "update user set nickname = ? where id = ?",
                "null, 9(Long)"
        );
        assertEquals("update user set nickname = NULL where id = 9;", result.restoredSql());
    }

    /**
     * 字符串中带逗号仍应作为一个参数处理.
     */
    @Test
    void restoreCommaInString() {
        SqlRestoreResult result = restore(
                "insert into note(content) values(?)",
                "hello, world(String)"
        );
        assertEquals("insert into note(content) values('hello, world');", result.restoredSql());
    }

    /**
     * 字符串中带括号仍应正常处理.
     */
    @Test
    void restoreParenthesesInString() {
        SqlRestoreResult result = restore(
                "insert into note(content) values(?)",
                "A(B)C(String)"
        );
        assertEquals("insert into note(content) values('A(B)C');", result.restoredSql());
    }

    /**
     * Timestamp 类型格式化验证.
     */
    @Test
    void restoreDateTime() {
        SqlRestoreResult result = restore(
                "select * from orders where created_at >= ?",
                "2026-02-05 12:30:45(Timestamp)"
        );
        assertEquals("select * from orders where created_at >= '2026-02-05 12:30:45';", result.restoredSql());
    }

    /**
     * Boolean 默认输出 TRUE/FALSE.
     */
    @Test
    void restoreBooleanTrueFalse() {
        SqlRestoreResult result = restore(
                "select * from user where enabled = ?",
                "true(Boolean)"
        );
        assertEquals("select * from user where enabled = TRUE;", result.restoredSql());
    }

    /**
     * Boolean 可按配置输出 1/0.
     */
    @Test
    void restoreBooleanOneZero() {
        SqlRestoreResult result = restorer.restore(
                new MyBatisLogBlock("select * from user where enabled = ?", "false(Boolean)", 0, 1),
                new SqlRenderOptions(true, "yyyy-MM-dd HH:mm:ss")
        );
        assertEquals("select * from user where enabled = 0;", result.restoredSql());
    }

    /**
     * 字符串中的单引号应被正确转义.
     */
    @Test
    void restoreSingleQuoteInString() {
        SqlRestoreResult result = restore(
                "insert into user(name) values(?)",
                "O'Reilly(String)"
        );
        assertEquals("insert into user(name) values('O''Reilly');", result.restoredSql());
    }

    /**
     * 占位符与参数数量不一致时应产生 warning.
     */
    @Test
    void warnForPlaceholderMismatch() {
        SqlRestoreResult result = restore(
                "select * from user where id = ?",
                "1(Integer), 2(Integer)"
        );
        assertEquals("select * from user where id = 1;", result.restoredSql());
        assertFalse(result.warnings().isEmpty());
    }

    /**
     * 顶层逗号切分验证：括号内部逗号不能切开 token.
     */
    @Test
    void tokenizerSplitsOnlyAtTopLevelCommas() {
        SqlRestoreResult result = restore(
                "insert into expr(raw_expr, amount) values(?, ?)",
                "calc(sum(1,2), round(3,4))(String), 7(Integer)"
        );
        assertEquals("insert into expr(raw_expr, amount) values('calc(sum(1,2), round(3,4))', 7);", result.restoredSql());
    }

    /**
     * 双引号值中包含逗号、括号和空格时应正确解析.
     */
    @Test
    void tokenizerSupportsQuotedValueWithCommaParenthesesAndSpaces() {
        SqlRestoreResult result = restore(
                "insert into user(alias) values(?)",
                "\"Tom, (QA) Team\"(String)"
        );
        assertEquals("insert into user(alias) values('Tom, (QA) Team');", result.restoredSql());
    }

    /**
     * typed 与 untyped 参数混用场景.
     */
    @Test
    void parserSupportsValueTypeAndUntypedTogether() {
        SqlRestoreResult result = restore(
                "insert into mixed(name, score, active) values(?, ?, ?)",
                "Tom(String), 98.5, true"
        );
        assertEquals("insert into mixed(name, score, active) values('Tom', 98.5, TRUE);", result.restoredSql());
    }

    /**
     * 无类型 null 参数处理.
     */
    @Test
    void parserSupportsUntypedNull() {
        SqlRestoreResult result = restore(
                "update profile set remark = ? where id = ?",
                "null, 3"
        );
        assertEquals("update profile set remark = NULL where id = 3;", result.restoredSql());
    }

    /**
     * 无类型且带空格/括号的字符串处理.
     */
    @Test
    void parserSupportsUntypedStringWithSpacesAndParentheses() {
        SqlRestoreResult result = restore(
                "insert into memo(content) values(?)",
                "\"draft (v2) done\""
        );
        assertEquals("insert into memo(content) values('draft (v2) done');", result.restoredSql());
    }

    /**
     * 无类型且包含逗号值：应恢复并给出歧义 warning.
     */
    @Test
    void untypedCommaValueRestoresAndWarnsAmbiguity() {
        SqlRestoreResult result = restore(
                "insert into note(content) values(?)",
                "hello, world"
        );
        assertEquals("insert into note(content) values('hello, world');", result.restoredSql());
        assertFalse(result.warnings().isEmpty());
    }

    /**
     * 声明为数值类型但值非数值：应回退到无类型处理并告警.
     */
    @Test
    void numericTypeWithNonNumericValueFallsBackToUntyped() {
        SqlRestoreResult result = restore(
                "insert into note(content) values(?)",
                "hello, 18(Integer)"
        );
        assertEquals("insert into note(content) values('hello, 18');", result.restoredSql());
        assertFalse(result.warnings().isEmpty());
    }
}
