package com.mybatis.loghelper.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MyBatisLogBlockExtractor} 的单元测试.
 *
 * <p>重点验证：
 * <ul>
 *     <li>多行 SQL 续行识别能力</li>
 *     <li>日志块边界判定能力</li>
 *     <li>光标附近最近 Preparing 行选择逻辑</li>
 * </ul>
 */
class MyBatisLogBlockExtractorTest {
    /**
     * 被测对象.
     */
    private final MyBatisLogBlockExtractor extractor = new MyBatisLogBlockExtractor();

    /**
     * 验证无缩进续行（以 SQL 关键字开头）可被识别.
     */
    @Test
    void extractsWithKeywordContinuationWithoutIndent() {
        String text = String.join("\n",
                "2026-02-04 10:00:01 [DEBUG] - ==>  Preparing: select id, name",
                "from t_user",
                "where status = ?",
                "2026-02-04 10:00:01 [DEBUG] - ==> Parameters: 1(Integer)"
        );

        LogBlockExtractResult result = extractor.extract(text, 1);
        assertTrue(result.isSuccess());
        assertNotNull(result.block());
        assertEquals("select id, name from t_user where status = ?", result.block().sqlTemplate());
    }

    /**
     * 验证仅有 Preparing 时，不会跨越边界去匹配下一段无关 Parameters.
     */
    @Test
    void failsForOnlyPreparingWithoutCrossingToDetachedParameters() {
        String text = String.join("\n",
                "2026-02-04 10:00:10 [DEBUG] - ==>  Preparing: select * from t_user where id = ?",
                "========== CASE NEXT ==========",
                "2026-02-04 10:00:11 [DEBUG] - ==> Parameters: 1(Integer)"
        );

        LogBlockExtractResult result = extractor.extract(text, 0);
        assertFalse(result.isSuccess());
        assertEquals("Preparing found, but matching Parameters line was not found.", result.errorMessage());
    }

    /**
     * 验证仅有 Parameters 时，返回“未找到 Preparing”.
     */
    @Test
    void failsForOnlyParameters() {
        String text = "2026-02-04 10:00:20 [DEBUG] - ==> Parameters: 1(Integer)";
        LogBlockExtractResult result = extractor.extract(text, 0);

        assertFalse(result.isSuccess());
        assertEquals("No Preparing line found near the caret.", result.errorMessage());
    }

    /**
     * 验证中间出现明显无关日志时，会提前判定当前日志块结束.
     */
    @Test
    void failsWhenAnotherLogRecordStartsBeforeParameters() {
        String text = String.join("\n",
                "2026-02-04 10:00:30 [DEBUG] - ==>  Preparing: select * from t_user where id = ?",
                "2026-02-04 10:00:31 [INFO]  - business log line",
                "2026-02-04 10:00:32 [DEBUG] - ==> Parameters: 1(Integer)"
        );

        LogBlockExtractResult result = extractor.extract(text, 0);
        assertFalse(result.isSuccess());
        assertEquals("Preparing found, but matching Parameters line was not found.", result.errorMessage());
    }

    /**
     * 验证“光标附近最近 Preparing”选择逻辑.
     */
    @Test
    void picksNearestPreparingAroundCaret() {
        String text = String.join("\n",
                "2026-02-04 09:00:00 [DEBUG] - ==>  Preparing: select * from t_a where id = ?",
                "2026-02-04 09:00:00 [DEBUG] - ==> Parameters: 1(Integer)",
                "random middle line",
                "2026-02-04 09:01:00 [DEBUG] - ==>  Preparing: select * from t_b where id = ?",
                "2026-02-04 09:01:00 [DEBUG] - ==> Parameters: 2(Integer)"
        );

        LogBlockExtractResult result = extractor.extract(text, 2);
        assertTrue(result.isSuccess());
        assertNotNull(result.block());
        assertEquals("select * from t_b where id = ?", result.block().sqlTemplate());
    }
}
