package com.mybatis.loghelper.parser;

/**
 * SQL 渲染配置项.
 *
 * @param booleanAsOneZero 是否将 Boolean 渲染为 1/0（否则为 TRUE/FALSE）
 * @param dateTimePattern  日期时间输出格式
 */
public record SqlRenderOptions(boolean booleanAsOneZero, String dateTimePattern) {
    /**
     * 默认渲染配置.
     *
     * @return 默认配置实例
     */
    public static SqlRenderOptions defaults() {
        return new SqlRenderOptions(false, "yyyy-MM-dd HH:mm:ss");
    }
}
