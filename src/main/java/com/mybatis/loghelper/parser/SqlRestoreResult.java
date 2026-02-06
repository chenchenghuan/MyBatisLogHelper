package com.mybatis.loghelper.parser;

import java.util.List;

/**
 * SQL 还原结果对象.
 *
 * @param restoredSql 还原后的 SQL 文本
 * @param warnings    还原过程中的告警列表
 */
public record SqlRestoreResult(String restoredSql, List<String> warnings) {
    /**
     * 规范化构造器.
     *
     * <p>将 warning 列表转为不可变拷贝，避免外部修改。</p>
     */
    public SqlRestoreResult {
        warnings = List.copyOf(warnings);
    }
}
