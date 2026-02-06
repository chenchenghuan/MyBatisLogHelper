package com.mybatis.loghelper.actions;

/**
 * “Restore & Copy” 右键动作.
 *
 * <p>该动作无条件复制还原结果，不受设置页 autoCopy 开关影响。</p>
 */
public final class RestoreAndCopySqlAction extends AbstractRestoreSqlAction {
    /**
     * 构造动作实例，启用强制复制。
     */
    public RestoreAndCopySqlAction() {
        super(true);
    }
}
