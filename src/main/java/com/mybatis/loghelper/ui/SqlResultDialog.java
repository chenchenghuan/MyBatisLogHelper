package com.mybatis.loghelper.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.mybatis.loghelper.parser.SqlBeautifier;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * SQL 还原结果弹窗。
 *
 * <p>该弹窗负责：
 * <ul>
 *     <li>显示最终可执行 SQL</li>
 *     <li>显示 warning 列表（置顶）</li>
 *     <li>提供手动复制按钮</li>
 *     <li>自动复制开启时显示提示文案</li>
 * </ul>
 */
public final class SqlResultDialog extends DialogWrapper {
    /**
     * 还原后的 SQL 文本。
     */
    private final String sql;
    /**
     * 将 SQL 压缩为单行后的文本，用于非 Beautify 状态显示。
     */
    private final String singleLineSql;
    /**
     * 警告信息列表。
     */
    private final List<String> warnings;
    /**
     * 本次是否已自动复制。
     */
    private final boolean autoCopied;
    /**
     * SQL 美化器，用于长 SQL 的格式化显示。
     */
    private final SqlBeautifier beautifier = new SqlBeautifier();
    // 鎻掍欢鎸佷箙鍖栭厤缃殑缁熶竴鍏ュ彛
    private final MyBatisLogHelperSettings settings;
    // 褰撳墠椤圭洰锛屼緵 EditorTextField 鍒涘缓浣跨敤
    private final Project project;
    /**
     * 中心文本框组件引用，用于运行时切换原始/美化内容。
     */
    private EditorTextField editorField;
    /**
     * Beautify 按钮状态：true 表示已美化。
     */
    private boolean beautified;

    /**
     * 构造结果弹窗。
     *
     * @param project    当前项目（可空）
     * @param sql        还原后的 SQL
     * @param warnings   warning 列表
     * @param autoCopied 是否已自动复制
     */
    public SqlResultDialog(@Nullable Project project, String sql, List<String> warnings, boolean autoCopied) {
        super(project);
        this.project = project;
        this.settings = MyBatisLogHelperSettings.getInstance();
        this.sql = sql;
        this.singleLineSql = toSingleLine(sql);
        this.warnings = warnings;
        this.autoCopied = autoCopied;
        // 读取上次对话框的显示模式
        this.beautified = settings.isDialogBeautified();
        setTitle("MyBatis Log Helper");
        init();
    }

    /**
     * 创建弹窗中心面板。
     *
     * @return 中心面板组件
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));

        // 顶部统一展示 warning。
        if (!warnings.isEmpty()) {
            String warningHtml = "<html><b>WARNING:</b><br/>- " + String.join("<br/>- ", warnings) + "</html>";
            panel.add(new JBLabel(warningHtml), BorderLayout.NORTH);
        }

        // 中间显示 SQL 文本框（只读）。
        // 根据上次偏好决定默认显示原始单行还是美化后的 SQL
        String initialText = beautified ? beautifier.beautify(sql) : singleLineSql;
        editorField = createEditorField(initialText);
        panel.add(editorField, BorderLayout.CENTER);

        // 底部显示“已自动复制”提示。
        if (autoCopied) {
            panel.add(new JBLabel("Copied to clipboard."), BorderLayout.SOUTH);
        }

        panel.setPreferredSize(new Dimension(settings.getDialogWidth(), settings.getDialogHeight()));
        return panel;
    }

    /**
     * 创建弹窗操作按钮。
     *
     * @return 按钮数组（Beautify + Copy + OK）
     */
    @Override
    protected Action[] createActions() {
        Action beautifyAction = new DialogWrapperAction("Beautify") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                toggleBeautify(this);
            }
        };
        beautifyAction.putValue(Action.NAME, beautified ? "Raw" : "Beautify");

        // 根据设置决定是否把 Copy 直接变成 Copy & Close
        boolean closeAfterCopy = settings.isCloseAfterCopy();
        Action copyAction = new DialogWrapperAction(closeAfterCopy ? "Copy & Close" : "Copy") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                copyToClipboard();
                if (closeAfterCopy) {
                    close(OK_EXIT_CODE);
                }
            }
        };
        // 未开启“复制后关闭”时，额外提供一个独立的 Copy & Close
        Action copyAndCloseAction = new DialogWrapperAction("Copy & Close") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                copyToClipboard();
                close(OK_EXIT_CODE);
            }
        };
        if (closeAfterCopy) {
            return new Action[]{beautifyAction, copyAction, getOKAction()};
        }
        return new Action[]{beautifyAction, copyAction, copyAndCloseAction, getOKAction()};
    }

    @Override
    public void dispose() {
        persistDialogState();
        super.dispose();
    }

    /**
     * 关闭前记录对话框大小和显示模式
     */
    private void persistDialogState() {
        settings.setDialogBeautified(beautified);
        Dimension size = getSize();
        if (size != null && size.width > 0 && size.height > 0) {
            settings.setDialogWidth(size.width);
            settings.setDialogHeight(size.height);
        }
    }

    /**
     * 统一复制逻辑，避免不同按钮重复代码
     */
    private void copyToClipboard() {
        String toCopy = editorField == null ? sql : editorField.getText();
        CopyPasteManager.getInstance().setContents(new StringSelection(toCopy));
    }

    /**
     * 创建带语法高亮的编辑器展示（优先 SQL，高亮不可用则退化为纯文本）
     */
    private EditorTextField createEditorField(String text) {
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension("sql");
        if (fileType == UnknownFileType.INSTANCE) {
            fileType = PlainTextFileType.INSTANCE;
        }
        // 使用 Editor 文档，避免直接 TextArea 带来的样式限制
        Document document = EditorFactory.getInstance().createDocument(text);
        EditorTextField field = new EditorTextField(document, project, fileType, true, false);
        field.setFontInheritedFromLAF(false);
        field.addSettingsProvider(editor -> {
            EditorSettings editorSettings = editor.getSettings();
            editorSettings.setLineNumbersShown(false);
            editorSettings.setUseSoftWraps(true);
            editorSettings.setCaretRowShown(false);
        });
        return field;
    }

    /**
     * 切换 Beautify 状态。
     * <ul>
     *     <li>美化状态：按钮变绿 + SQL 美化显示</li>
     *     <li>非美化状态：按钮恢复默认 + SQL 单行显示</li>
     * </ul>
     *
     * @param action Beautify 按钮 Action
     */
    private void toggleBeautify(Action action) {
        if (editorField == null) {
            return;
        }
        beautified = !beautified;
        if (beautified) {
            editorField.setText(beautifier.beautify(sql));
        } else {
            editorField.setText(singleLineSql);
        }
        action.putValue(Action.NAME, beautified ? "Raw" : "Beautify");
        Editor editor = editorField.getEditor();
        if (editor != null) {
            editor.getCaretModel().moveToOffset(0);
        }
    }

    /**
     * 将 SQL 统一为单行显示，只保留单个空白。
     *
     * @param sql 源 SQL
     * @return 压缩后的单行 SQL
     */
    private static String toSingleLine(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }
}
