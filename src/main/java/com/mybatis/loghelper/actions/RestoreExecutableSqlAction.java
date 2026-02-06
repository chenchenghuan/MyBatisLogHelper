package com.mybatis.loghelper.actions;

/**
 * “Restore Executable SQL” 右键动作.
 *
 * <p>该动作执行还原后是否复制，取决于设置页中的 autoCopy 开关。</p>
 */
public final class RestoreExecutableSqlAction extends AbstractRestoreSqlAction {
    /**
     * 构造动作实例，不强制复制。
     */
    public RestoreExecutableSqlAction() {
        super(false);
    }
}
