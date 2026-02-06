package com.mybatis.loghelper.parser;

import java.util.List;

/**
 * 参数解析结果对象.
 *
 * @param values   解析后的参数值列表（与 SQL 占位符按顺序对应）
 * @param warnings 解析过程中的告警信息
 */
public record ParameterParseResult(List<ParameterValue> values, List<String> warnings) {
    /**
     * 规范化构造器.
     *
     * <p>通过不可变拷贝避免外部持有集合引用后修改内部状态。</p>
     */
    public ParameterParseResult {
        values = List.copyOf(values);
        warnings = List.copyOf(warnings);
    }
}
