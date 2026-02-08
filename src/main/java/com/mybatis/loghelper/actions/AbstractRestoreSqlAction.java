package com.mybatis.loghelper.actions;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.ide.CopyPasteManager;
import com.mybatis.loghelper.parser.LogBlockExtractResult;
import com.mybatis.loghelper.parser.MyBatisLogBlock;
import com.mybatis.loghelper.parser.MyBatisLogBlockExtractor;
import com.mybatis.loghelper.parser.SqlBeautifier;
import com.mybatis.loghelper.parser.SqlRestoreResult;
import com.mybatis.loghelper.parser.SqlRestorer;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import com.mybatis.loghelper.ui.SqlResultDialog;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.Locale;

/**
 * 控制台右键动作的抽象基类。
 */
public abstract class AbstractRestoreSqlAction extends AnAction {
    /**
     * 是否强制复制结果到剪贴板。
     * <p>true 时忽略设置页中的 autoCopy 开关。</p>
     */
    private final boolean forceCopy;

    protected AbstractRestoreSqlAction(boolean forceCopy) {
        this.forceCopy = forceCopy;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // 1) 仅在 Console 编辑器中继续执行
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null || !isConsoleEditor(editor)) {
            return;
        }

        // 2) 读取完整日志文本，并使用光标行作为提取锚点
        String text = editor.getDocument().getText();
        int caretLine = editor.getDocument().getLineNumber(editor.getCaretModel().getOffset());

        // 3) 提取最近的 MyBatis 日志块（Preparing + Parameters）
        MyBatisLogBlockExtractor extractor = new MyBatisLogBlockExtractor();
        LogBlockExtractResult extracted = extractor.extract(text, caretLine);
        if (!extracted.isSuccess()) {
            // 提取失败时提示 warning 并终止
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("MyBatis Log Helper")
                    .createNotification(extracted.errorMessage(), NotificationType.WARNING)
                    .notify(e.getProject());
            return;
        }

        // 4) 根据设置执行 SQL 还原
        MyBatisLogHelperSettings settings = MyBatisLogHelperSettings.getInstance();
        SqlRestorer restorer = new SqlRestorer();
        SqlRestoreResult result = restorer.restore(extracted.block(), settings.toRenderOptions());
        if (!settings.isAppendSemicolon()) {
            result = new SqlRestoreResult(trimTrailingSemicolon(result.restoredSql()), result.warnings());
        }

        // 5) 计算本次是否需要复制
        //    forceCopy=true 表示当前动作始终复制，否则遵循 autoCopy 设置
        boolean shouldCopy = forceCopy || settings.isAutoCopy();
        if (shouldCopy) {
            String toCopy = result.restoredSql();
            if (settings.isCopyBeautified() && settings.isDialogBeautified()) {
                toCopy = new SqlBeautifier().beautify(toCopy);
            }
            CopyPasteManager.getInstance().setContents(new StringSelection(toCopy));
        }

        // 6) 展示还原结果弹窗
        MyBatisLogBlock block = extracted.block();
        SqlResultDialog dialog = new SqlResultDialog(e.getProject(), result.restoredSql(), result.warnings(), shouldCopy);
        dialog.setLogBlockInfo(block);
        dialog.show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean enabled = editor != null && isConsoleEditor(editor);
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static boolean isConsoleEditor(Editor editor) {
        if (editor instanceof EditorEx editorEx) {
            try {
                Object kind = editorEx.getClass().getMethod("getEditorKind").invoke(editorEx);
                if (kind != null && "CONSOLE".equals(kind.toString())) {
                    return true;
                }
            } catch (Exception ignored) {
                // 兼容旧版本 API：忽略反射失败
            }
        }
        String editorClass = editor.getClass().getName().toLowerCase(Locale.ROOT);
        return editorClass.contains("console");
    }

    private static String trimTrailingSemicolon(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        int end = sql.length() - 1;
        while (end >= 0 && Character.isWhitespace(sql.charAt(end))) {
            end--;
        }
        if (end >= 0 && sql.charAt(end) == ';') {
            int endWithoutSemicolon = end - 1;
            while (endWithoutSemicolon >= 0 && Character.isWhitespace(sql.charAt(endWithoutSemicolon))) {
                endWithoutSemicolon--;
            }
            return sql.substring(0, endWithoutSemicolon + 1);
        }
        return sql;
    }
}
