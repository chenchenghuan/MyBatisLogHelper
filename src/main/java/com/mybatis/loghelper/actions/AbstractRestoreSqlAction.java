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
import com.mybatis.loghelper.history.SqlHistoryService;
import com.mybatis.loghelper.parser.LogBlockExtractResult;
import com.mybatis.loghelper.parser.MyBatisLogBlock;
import com.mybatis.loghelper.parser.MyBatisLogBlockExtractor;
import com.mybatis.loghelper.parser.SqlBeautifier;
import com.mybatis.loghelper.parser.SqlRestoreResult;
import com.mybatis.loghelper.parser.SqlRestorer;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import com.mybatis.loghelper.ui.SqlResultPopup;
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
    private final boolean showDialog;

    protected AbstractRestoreSqlAction(boolean forceCopy) {
        this(forceCopy, true);
    }

    protected AbstractRestoreSqlAction(boolean forceCopy, boolean showDialog) {
        this.forceCopy = forceCopy;
        this.showDialog = showDialog;
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
                    .createNotification("MyBatis Log Helper", extracted.errorMessage(), NotificationType.WARNING)
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
        SqlHistoryService.getInstance().add(result.restoredSql());

        // 5) 计算本次是否需要复制
        //    forceCopy=true 表示当前动作始终复制，否则遵循 autoCopy 设置
        boolean shouldCopy = forceCopy || settings.isAutoCopy();
        if (shouldCopy) {
            String toCopy = buildCopyText(result.restoredSql(), settings);
            CopyPasteManager.getInstance().setContents(new StringSelection(toCopy));
            if (!showDialog) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("MyBatis Log Helper")
                        .createNotification("MyBatis Log Helper", "SQL copied to clipboard.", NotificationType.INFORMATION)
                        .notify(e.getProject());
            }
        }

        if (showDialog) {
            // 6) 展示还原结果弹窗
            MyBatisLogBlock block = extracted.block();
            SqlResultPopup popup = new SqlResultPopup(e.getProject(), result.restoredSql(), result.warnings(), shouldCopy);
            popup.setLogBlockInfo(block);
            popup.showPopup(e.getDataContext());
        }
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

    private static String buildCopyText(String restoredSql, MyBatisLogHelperSettings settings) {
        if (!settings.isCopyBeautified()) {
            return restoredSql;
        }
        if (settings.isDialogBeautified()) {
            return new SqlBeautifier().beautify(restoredSql);
        }
        return toSingleLine(restoredSql);
    }

    private static String toSingleLine(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean inSingleQuotedString = false;
        boolean lastWasSpace = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (inSingleQuotedString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                    continue;
                }
                inSingleQuotedString = !inSingleQuotedString;
                out.append(c);
                lastWasSpace = false;
                continue;
            }
            if (inSingleQuotedString) {
                out.append(c);
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (out.length() > 0 && !lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == ' ') {
            out.deleteCharAt(len - 1);
        }
        return out.toString();
    }
}
