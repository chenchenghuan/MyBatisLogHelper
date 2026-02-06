package com.mybatis.loghelper.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.Messages;
import com.mybatis.loghelper.parser.LogBlockExtractResult;
import com.mybatis.loghelper.parser.MyBatisLogBlockExtractor;
import com.mybatis.loghelper.parser.SqlRestoreResult;
import com.mybatis.loghelper.parser.SqlRestorer;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import com.mybatis.loghelper.ui.SqlResultDialog;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * 控制台右键动作的抽象基类.
 *
 * <p>该类负责串联一次“日志还原”完整流程:
 * <ol>
 *     <li>从当前控制台编辑器和光标行获取原始日志文本</li>
 *     <li>定位并提取一组 MyBatis 日志块(Preparing + Parameters)</li>
 *     <li>根据用户配置将占位符 SQL 还原为可执行 SQL</li>
 *     <li>按动作策略决定是否自动复制</li>
 *     <li>弹窗展示还原结果和告警信息</li>
 * </ol>
 *
 * <p>具体“是否强制复制”由子类通过构造参数决定:
 * <ul>
 *     <li>{@code RestoreExecutableSqlAction}: 不强制复制</li>
 *     <li>{@code RestoreAndCopySqlAction}: 强制复制</li>
 * </ul>
 */
public abstract class AbstractRestoreSqlAction extends AnAction {
    /**
     * 是否强制复制结果到剪贴板.
     * <p>true 时忽略设置页中的 autoCopy 开关。</p>
     */
    private final boolean forceCopy;

    /**
     * 构造动作基类.
     *
     * @param forceCopy 是否强制复制
     */
    protected AbstractRestoreSqlAction(boolean forceCopy) {
        this.forceCopy = forceCopy;
    }

    /**
     * 动作执行入口.
     *
     * <p>这里的每一步都尽量“失败即返回”，避免在控制台场景中产生多余中断。</p>
     *
     * @param e IDEA 动作事件
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // 1) 仅在存在控制台 Editor 时继续执行.
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        // 2) 读取完整日志文本，并拿到当前光标所在行号作为提取锚点.
        String text = editor.getDocument().getText();
        int caretLine = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset());

        // 3) 提取最近的 MyBatis 日志块(Preparing + Parameters).
        MyBatisLogBlockExtractor extractor = new MyBatisLogBlockExtractor();
        LogBlockExtractResult extracted = extractor.extract(text, caretLine);
        if (!extracted.isSuccess()) {
            // 提取失败时统一弹 warning 并终止.
            Messages.showWarningDialog(e.getProject(), extracted.errorMessage(), "MyBatis Log Helper");
            return;
        }

        // 4) 根据设置项执行 SQL 还原.
        MyBatisLogHelperSettings settings = MyBatisLogHelperSettings.getInstance();
        SqlRestorer restorer = new SqlRestorer();
        SqlRestoreResult result = restorer.restore(extracted.block(), settings.toRenderOptions());

        // 5) 计算本次是否需要复制:
        //    forceCopy=true 表示当前动作始终复制；否则遵循用户 autoCopy 设置.
        boolean shouldCopy = forceCopy || settings.isAutoCopy();
        if (shouldCopy) {
            CopyPasteManager.getInstance().setContents(new StringSelection(result.restoredSql()));
        }

        // 6) 展示还原结果弹窗.
        new SqlResultDialog(e.getProject(), result.restoredSql(), result.warnings(), shouldCopy).show();
    }

    /**
     * 动态控制动作可用性.
     *
     * <p>当前实现仅要求存在 Editor 即可启用动作。</p>
     *
     * @param e IDEA 动作事件
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getData(CommonDataKeys.EDITOR) != null);
    }

    /**
     * 指定 update 方法运行线程.
     *
     * <p>使用后台线程可避免 UI 线程被频繁 update 调用阻塞。</p>
     *
     * @return 后台线程标记
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
