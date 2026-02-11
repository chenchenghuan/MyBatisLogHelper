package com.mybatis.loghelper.ui;

import com.intellij.openapi.application.ApplicationManager;
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
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.icons.AllIcons;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.mybatis.loghelper.parser.SqlBeautifier;
import com.mybatis.loghelper.parser.MyBatisLogBlock;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import javax.swing.Icon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * SQL 还原结果轻量气泡（替代 Dialog）。
 *
 * <p>特性：</p>
 * <ul>
 *     <li>无标题栏，点击外部/ESC 关闭</li>
 *     <li>支持 Beautify / Copy / Close 等快速操作</li>
 *     <li>仅展示当前结果，不包含历史 Tab</li>
 * </ul>
 */
public final class SqlResultPopup {
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
    // 插件持久化配置的统一入口
    private final MyBatisLogHelperSettings settings;
    // 当前项目，供 EditorTextField 创建使用
    private final Project project;
    /**
     * 中心文本框组件引用，用于运行时切换原始/美化内容。
     */
    private EditorTextField editorField;
    private JBScrollPane previewScroll;
    private JPanel headerPanel;
    private JComponent actionPanel;
    private boolean contextVisible;
    private JPanel dragHandlePanel;
    /**
     * Beautify 按钮状态：true 表示已美化。
     */
    private boolean beautified;
    private Integer preparingLine;
    private Integer parametersLine;
    private String preparingSnippet;
    private String parametersSnippet;
    private JBPopup popup;
    private static final int MAX_SNIPPET_LENGTH = 160;
    private static final Icon ICON_BEAUTIFY = AllIcons.Actions.ReformatCode;
    private static final Icon ICON_COPY = AllIcons.Actions.Copy;
    private static final Icon ICON_CLOSE = AllIcons.Actions.Close;
    private static final Icon ICON_CONTEXT = AllIcons.General.InspectionsEye;

    /**
     * 构造结果气泡。
     *
     * @param project    当前项目（可空）
     * @param sql        还原后的 SQL
     * @param warnings   warning 列表
     * @param autoCopied 是否已自动复制
     */
    public SqlResultPopup(@Nullable Project project, String sql, List<String> warnings, boolean autoCopied) {
        this.project = project;
        this.settings = MyBatisLogHelperSettings.getInstance();
        this.sql = sql;
        this.singleLineSql = toSingleLine(sql);
        this.warnings = warnings;
        this.autoCopied = autoCopied;
        // 读取上次显示模式
        this.beautified = settings.isDialogBeautified();
    }

    /**
     * 设置日志块定位信息（Preparing/Parameters 行号与片段）。
     *
     * @param block 提取到的日志块
     * @return 当前气泡实例
     */
    public SqlResultPopup setLogBlockInfo(MyBatisLogBlock block) {
        if (block == null) {
            return this;
        }
        this.preparingLine = block.preparingLine() + 1;
        this.parametersLine = block.parametersLine() + 1;
        this.preparingSnippet = block.sqlTemplate();
        this.parametersSnippet = block.parametersRaw();
        updateHeaderPanel();
        return this;
    }

    /**
     * 在最佳位置展示气泡（参考 Quick Doc 体验）。
     *
     * @param context 事件上下文
     */
    public void showPopup(@NotNull com.intellij.openapi.actionSystem.DataContext context) {
        if (popup != null && !popup.isDisposed()) {
            popup.cancel();
        }
        JComponent content = createContentPanel();
        JComponent focusComponent = editorField != null ? editorField : content;
        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, focusComponent)
                .setRequestFocus(true)
                .setFocusable(true)
                // 允许拖动气泡位置
                .setMovable(true)
                .setCancelOnClickOutside(true)
                .setCancelOnOtherWindowOpen(true)
                .setCancelKeyEnabled(true)
                .createPopup();
        installDragHandler(dragHandlePanel);
        // 关闭时保存显示偏好与尺寸
        Disposer.register(popup, () -> persistPopupState(content.getSize()));
        popup.showInBestPositionFor(context);
        centerPopupInIdeFrame();
    }

    /**
     * 创建气泡中心面板。
     *
     * @return 中心面板组件
     */
    private JComponent createContentPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));

        panel.add(createResultPanel(), BorderLayout.CENTER);

        panel.setPreferredSize(new Dimension(settings.getDialogWidth(), settings.getDialogHeight()));
        return panel;
    }

    private JComponent createResultPanel() {
        JPanel resultPanel = new JPanel(new BorderLayout(0, 8));

        // 顶部：日志信息 + 操作按钮
        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        updateHeaderPanel();
        actionPanel = createLeftToolbarPanel();
        JComponent closePanel = createRightToolbarPanel();
        JPanel toolbarRow = new JPanel(new BorderLayout(8, 0));
        toolbarRow.add(actionPanel, BorderLayout.WEST);
        toolbarRow.add(closePanel, BorderLayout.EAST);
        topPanel.add(toolbarRow, BorderLayout.NORTH);
        // Log block 区域放到按钮下方
        topPanel.add(headerPanel, BorderLayout.CENTER);
        // 顶部作为拖动区域，便于移动气泡
        dragHandlePanel = topPanel;
        resultPanel.add(topPanel, BorderLayout.NORTH);

        // 中间显示 SQL 文本框（只读）
        String initialText = beautified ? beautifier.beautify(sql) : singleLineSql;
        editorField = createEditorField(initialText);
        // 让 Log block 与按钮区域对齐（以 SQL 文本左边距为基准）
        int leftIndent = resolveContentLeftInset(editorField);
        toolbarRow.setBorder(JBUI.Borders.emptyLeft(leftIndent));
        headerPanel.setBorder(JBUI.Borders.emptyLeft(leftIndent));
        // 使用滚动容器包裹，避免长 SQL 无法完整查看
        previewScroll = new JBScrollPane(editorField);
        previewScroll.setBorder(JBUI.Borders.empty());
        resultPanel.add(previewScroll, BorderLayout.CENTER);

        // 底部提示：自动复制与历史入口
        resultPanel.add(createFooterPanel(), BorderLayout.SOUTH);
        return resultPanel;
    }

    private JComponent createLeftToolbarPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        // 左侧工具栏：Raw/Context/Copy & Close
        DefaultActionGroup leftGroup = new DefaultActionGroup();
        leftGroup.add(new RawToggleAction());
        leftGroup.add(new ContextToggleAction());
        leftGroup.addSeparator();
        leftGroup.add(new CopyAction());
        ActionToolbar leftToolbar = ActionManager.getInstance()
                .createActionToolbar("MyBatisLogHelperPopupToolbarLeft", leftGroup, true);
        leftToolbar.setTargetComponent(panel);
        panel.add(leftToolbar.getComponent(), BorderLayout.WEST);
        return panel;
    }

    private JComponent createRightToolbarPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        DefaultActionGroup rightGroup = new DefaultActionGroup();
        rightGroup.add(new CloseAction());
        ActionToolbar rightToolbar = ActionManager.getInstance()
                .createActionToolbar("MyBatisLogHelperPopupToolbarRight", rightGroup, true);
        rightToolbar.setTargetComponent(panel);
        panel.add(rightToolbar.getComponent(), BorderLayout.EAST);
        return panel;
    }

    private boolean hasLogBlockInfo() {
        return preparingLine != null && parametersLine != null;
    }

    private void updateHeaderPanel() {
        if (headerPanel == null) {
            return;
        }
        headerPanel.removeAll();
        if (hasLogBlockInfo() && contextVisible) {
            JBLabel contextLabel = new JBLabel(buildLogBlockHtml());
            applySmallGrayStyle(contextLabel);
            headerPanel.add(contextLabel);
        }
        if (!warnings.isEmpty()) {
            String warningHtml = "<html><b>WARNING:</b><br/>- " + String.join("<br/>- ", warnings) + "</html>";
            JBLabel warningLabel = new JBLabel(warningHtml);
            headerPanel.add(warningLabel);
        }
        headerPanel.setVisible(headerPanel.getComponentCount() > 0);
        headerPanel.revalidate();
        headerPanel.repaint();
    }

    private String buildLogBlockHtml() {
        String preparingText = abbreviate(preparingSnippet, MAX_SNIPPET_LENGTH);
        String parametersText = abbreviate(parametersSnippet, MAX_SNIPPET_LENGTH);
        String preparingLineText = preparingLine == null ? "-" : preparingLine.toString();
        String parametersLineText = parametersLine == null ? "-" : parametersLine.toString();
        return "<html><b>Log block:</b><br/>"
                + "Preparing (line " + preparingLineText + "): "
                + escapeHtml(preparingText)
                + "<br/>Parameters (line " + parametersLineText + "): "
                + escapeHtml(parametersText)
                + "</html>";
    }

    /**
     * 关闭气泡并释放资源。
     */
    private void closePopup() {
        if (popup != null && !popup.isDisposed()) {
            popup.cancel();
        }
    }

    /**
     * 为指定区域绑定拖拽逻辑，模拟轻量气泡的可移动体验。
     *
     * @param target 可拖拽区域
     */
    private void installDragHandler(@Nullable JComponent target) {
        if (target == null) {
            return;
        }
        MouseAdapter adapter = new MouseAdapter() {
            private Point pressScreenPoint;
            private Point popupScreenPoint;

            @Override
            public void mousePressed(MouseEvent e) {
                if (popup == null || popup.isDisposed()) {
                    return;
                }
                pressScreenPoint = e.getLocationOnScreen();
                popupScreenPoint = popup.getLocationOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (popup == null || popup.isDisposed() || pressScreenPoint == null || popupScreenPoint == null) {
                    return;
                }
                Point current = e.getLocationOnScreen();
                int dx = current.x - pressScreenPoint.x;
                int dy = current.y - pressScreenPoint.y;
                popup.setLocation(new Point(popupScreenPoint.x + dx, popupScreenPoint.y + dy));
            }
        };
        target.addMouseListener(adapter);
        target.addMouseMotionListener(adapter);
    }

    /**
     * 将气泡居中到 IDE 主窗口，保证默认打开位置更稳定。
     */
    private void centerPopupInIdeFrame() {
        if (popup == null || popup.isDisposed()) {
            return;
        }
        java.awt.Window frame = com.intellij.openapi.wm.WindowManager.getInstance()
                .getFrame(project);
        if (frame == null) {
            return;
        }
        java.awt.Rectangle bounds = frame.getBounds();
        java.awt.Dimension size = popup.getSize();
        if (size == null) {
            return;
        }
        int x = bounds.x + (bounds.width - size.width) / 2;
        int y = bounds.y + (bounds.height - size.height) / 2;
        popup.setLocation(new java.awt.Point(x, y));
    }

    /**
     * 关闭前记录气泡大小和显示模式
     */
    private void persistPopupState(@Nullable Dimension size) {
        settings.setDialogBeautified(beautified);
        if (size != null && size.width > 0 && size.height > 0) {
            settings.setDialogWidth(size.width);
            settings.setDialogHeight(size.height);
        }
    }

    /**
     * 统一复制逻辑，避免不同按钮重复代码
     */
    private void copyToClipboard() {
        String toCopy;
        if (settings.isCopyBeautified()) {
            if (editorField != null) {
                toCopy = editorField.getText();
            } else {
                toCopy = beautified ? beautifier.beautify(sql) : sql;
            }
        } else {
            toCopy = sql;
        }
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
     *     <li>美化状态：SQL 美化显示</li>
     *     <li>非美化状态：SQL 单行显示</li>
     * </ul>
     */
    private void setBeautified(boolean newValue) {
        if (editorField == null) {
            return;
        }
        beautified = newValue;
        if (beautified) {
            editorField.setText(beautifier.beautify(sql));
        } else {
            editorField.setText(singleLineSql);
        }
        refreshPreviewScroll();
        ApplicationManager.getApplication().runReadAction(() -> {
            Editor editor = editorField.getEditor();
            if (editor != null) {
                editor.getCaretModel().moveToOffset(0);
            }
        });
    }

    /**
     * Raw/Beautify 切换 Action。
     */
    private final class RawToggleAction extends ToggleAction {
        private RawToggleAction() {
            super("Raw / Beautify", "Raw / Beautify", ICON_BEAUTIFY);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            // 选中表示 Raw（非美化）
            return !beautified;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            // state=true 表示 Raw
            setBeautified(!state);
        }
    }

    /**
     * Context 展示切换 Action。
     */
    private final class ContextToggleAction extends ToggleAction {
        private ContextToggleAction() {
            super("Show Source Context", "Show Source Context", ICON_CONTEXT);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return contextVisible;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            contextVisible = state;
            updateHeaderPanel();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            super.update(e);
            boolean hasContext = hasLogBlockInfo();
            e.getPresentation().setEnabled(hasContext);
            e.getPresentation().setVisible(hasContext);
        }
    }

    /**
     * Copy Action（主按钮）。
     */
    private final class CopyAction extends AnAction {
        private CopyAction() {
            super("Copy", "Copy", ICON_COPY);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            copyToClipboard();
        }
    }

    /**
     * 关闭气泡 Action。
     */
    private final class CloseAction extends AnAction {
        private CloseAction() {
            super("Close", "Close", ICON_CLOSE);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            closePopup();
        }
    }

    /**
     * 创建底部提示区（自动复制 + 历史提示）。
     */
    private JComponent createFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout());
        if (autoCopied) {
            footer.add(new JBLabel("Copied to clipboard."), BorderLayout.WEST);
        }
        JBLabel hint = new JBLabel("History is available in Tool Window.");
        applySmallGrayStyle(hint);
        footer.add(hint, BorderLayout.EAST);
        return footer;
    }

    /**
     * 统一应用小号灰色样式，降低视觉噪声。
     */
    private void applySmallGrayStyle(JComponent component) {
        if (component == null) {
            return;
        }
        Font base = component.getFont();
        float smallSize = UIUtil.getFontSize(UIUtil.FontSize.SMALL);
        component.setFont(base.deriveFont(smallSize));
        component.setForeground(JBColor.GRAY);
    }

    /**
     * 刷新预览区域滚动条，避免切换长 SQL 时滚动条延迟出现。
     */
    private void refreshPreviewScroll() {
        if (previewScroll == null || editorField == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            editorField.revalidate();
            previewScroll.revalidate();
            previewScroll.repaint();
        });
    }

    /**
     * 计算内容左侧缩进，用于对齐工具栏与日志文本。
     *
     * @param field SQL 编辑器组件
     * @return 左侧缩进像素
     */
    private int resolveContentLeftInset(@Nullable EditorTextField field) {
        if (field == null) {
            return JBUI.scale(6);
        }
        int inset = field.getInsets().left;
        return Math.max(JBUI.scale(6), inset);
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
