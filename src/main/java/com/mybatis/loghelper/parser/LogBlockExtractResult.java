package com.mybatis.loghelper.parser;

import org.jetbrains.annotations.Nullable;

/**
 * 日志块提取结果对象.
 *
 * @param block        提取成功时的日志块；失败时为 null
 * @param errorMessage 提取失败时的错误信息；成功时为 null
 */
public record LogBlockExtractResult(@Nullable MyBatisLogBlock block, @Nullable String errorMessage) {
    /**
     * 创建成功结果.
     *
     * @param block 已提取日志块
     * @return 成功结果实例
     */
    public static LogBlockExtractResult success(MyBatisLogBlock block) {
        return new LogBlockExtractResult(block, null);
    }

    /**
     * 创建失败结果.
     *
     * @param errorMessage 失败原因
     * @return 失败结果实例
     */
    public static LogBlockExtractResult failure(String errorMessage) {
        return new LogBlockExtractResult(null, errorMessage);
    }

    /**
     * 判断当前结果是否成功.
     *
     * @return true 表示成功，false 表示失败
     */
    public boolean isSuccess() {
        return block != null;
    }
}
