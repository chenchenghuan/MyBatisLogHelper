package com.mybatis.loghelper.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.mybatis.loghelper.parser.SqlBeautifier;
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
    /**
     * 中心文本框组件引用，用于运行时切换原始/美化内容。
     */
    private JBTextArea textArea;
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
        this.sql = sql;
        this.singleLineSql = toSingleLine(sql);
        this.warnings = warnings;
        this.autoCopied = autoCopied;
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
        textArea = new JBTextArea(singleLineSql);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        panel.add(new JBScrollPane(textArea), BorderLayout.CENTER);

        // 底部显示“已自动复制”提示。
        if (autoCopied) {
            panel.add(new JBLabel("Copied to clipboard."), BorderLayout.SOUTH);
        }

        panel.setPreferredSize(new Dimension(760, 320));
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

        Action copyAction = new DialogWrapperAction("Copy") {
            @Override
            protected void doAction(java.awt.event.ActionEvent e) {
                String toCopy = textArea == null ? sql : textArea.getText();
                CopyPasteManager.getInstance().setContents(new StringSelection(toCopy));
            }
        };
        return new Action[]{beautifyAction, copyAction, getOKAction()};
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
        if (textArea == null) {
            return;
        }
        beautified = !beautified;
        if (beautified) {
            textArea.setText(beautifier.beautify(sql));
        } else {
            textArea.setText(singleLineSql);
        }
        textArea.setCaretPosition(0);
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
