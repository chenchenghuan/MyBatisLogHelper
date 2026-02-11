package com.mybatis.loghelper.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.ide.DataManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.IconLoader;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.mybatis.loghelper.history.SqlHistoryEntry;
import com.mybatis.loghelper.history.SqlToolWindowHistoryService;
import com.mybatis.loghelper.parser.SqlBeautifier;
import com.mybatis.loghelper.parser.SqlRestoreResult;
import com.mybatis.loghelper.parser.SqlRestorer;
import com.mybatis.loghelper.settings.MyBatisLogHelperConfigurable;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;
import com.mybatis.loghelper.util.EditorUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JSeparator;
import javax.swing.JScrollBar;
import java.awt.BorderLayout;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.swing.SwingConstants;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具窗口面板：
 * <ul>
 *     <li>绑定 Console 编辑器监听日志变化</li>
 *     <li>抽取 Preparing/Parameters 组成 SQL</li>
 *     <li>维护独立的内存历史（最多 30 条）</li>
 * </ul>
 */
public final class SqlToolWindowPanel extends JPanel implements Disposable {
    private static final String NO_CONSOLE_TEXT = "Select Console";
    private static final Icon ICON_REFRESH = AllIcons.Actions.BuildLoadChanges;
    private static final Icon ICON_START = AllIcons.Actions.Execute;
    private static final Icon ICON_STOP = AllIcons.Actions.Suspend;
    private static final Icon ICON_CLEAR = AllIcons.Actions.GC;
    private static final Icon ICON_COPY = AllIcons.Actions.Copy;
    private static final Icon ICON_CONSOLE = AllIcons.Debugger.Console;
    private static final Icon ICON_SETTINGS = AllIcons.General.Settings;
    private static final Icon ICON_RAW = AllIcons.Actions.GroupByFile;
    // 当前平台未提供 Breakpoints.BreakpointMuted，使用可用图标作为状态基底
    private static final Icon ICON_STATUS = AllIcons.Actions.Suspend;
    // 运行态使用偏马卡龙的柔和绿色
    private static final JBColor STATUS_ACTIVE_COLOR = new JBColor(new Color(168, 230, 207), new Color(158, 224, 198));
    private static final Icon ICON_SELECT = AllIcons.Actions.Find;
    private static final Icon ICON_INSERT = AllIcons.Actions.Edit;
    private static final Icon ICON_UPDATE = AllIcons.Actions.Edit;
    private static final Icon ICON_DELETE = AllIcons.Actions.DeleteTag;
    private static final Icon ICON_OTHER = AllIcons.Actions.Minimap;
    private static final JBColor BUTTON_HOVER_BG = new JBColor(new Color(0, 0, 0, 20), new Color(255, 255, 255, 28));
    private static final JBColor BUTTON_PRESSED_BG = new JBColor(new Color(0, 0, 0, 40), new Color(255, 255, 255, 45));
    private static final Pattern SQL_KEYWORD_PATTERN =
            Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);
    // MyBatis 日志关键字（允许 ==> 与不同空格）
    private static final Pattern PREPARING_MARKER_PATTERN =
            Pattern.compile("==>\\s*Preparing:|Preparing:", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMETERS_MARKER_PATTERN =
            Pattern.compile("==>\\s*Parameters:|Parameters:", Pattern.CASE_INSENSITIVE);
    // 常见 SQL 续行前缀关键字
    private static final String[] SQL_CONTINUATION_PREFIXES = {
            "select", "from", "where", "and", "or", "join", "left", "right", "inner", "outer",
            "on", "having", "group", "order", "limit", "offset", "union", "values", "set",
            "insert", "update", "delete", "into"
    };
    // Preparing 与 Parameters 之间允许插入的非 SQL 行数（容忍噪音日志）
    private static final int MAX_INTERLEAVING_LINES = 5;
    // 待匹配块的最大缓存数量（避免只有 Preparing 导致内存增长）
    private static final int MAX_PENDING_BLOCKS = 200;
    // 条件表达式模式（用于判断是否可能是 SQL 续行）
    private static final Pattern SQL_CONDITION_PATTERN = Pattern.compile(
            "^[A-Za-z_`\\[\\]\"]\\S*\\s*(=|<>|!=|>|<|>=|<=|like\\b|in\\b|is\\b).*$",
            Pattern.CASE_INSENSITIVE
    );
    // 常见日志时间前缀（用于分组时去除，避免仅时间不同导致无法匹配）
    private static final Pattern LOG_DATE_TIME_PREFIX = Pattern.compile(
            "^\\s*\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?\\s*"
    );
    private static final Pattern LOG_TIME_ONLY_PREFIX = Pattern.compile(
            "^\\s*\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?\\s*"
    );

    private final Project project;
    private final MyBatisLogHelperSettings settings;
    private final SqlToolWindowHistoryService historyService;
    private final SqlBeautifier beautifier = new SqlBeautifier();

    // 当前绑定的 Console 编辑器与文档
    private Editor boundEditor;
    private Document boundDocument;
    // 只从上次处理的行开始扫描，避免反复解析
    private int lastProcessedLineCount;
    private int lastProcessedParametersLineIndex = -1;
    private String lastProcessedParametersLineText;
    // 是否开启监听
    private boolean capturing;
    // 当前监听器是否已注册到文档，避免重复 add/remove
    private boolean consoleListenerAttached;

    private JBList<SqlHistoryEntry> list;
    private EditorTextField previewField;
    private JBScrollPane previewScrollPane;
    private JBLabel statusLabel;
    private JBLabel recordsLabel;
    private JComboBox<ConsoleItem> consoleCombo;
    private SearchTextField filterField;
    private boolean beautifyEnabled;
    private JButton startButton;
    private JButton stopButton;
    private JButton clearButton;
    private JToggleButton rawToggleButton;
    private JBScrollPane listScrollPane;
    private boolean refreshingConsoleList;
    private String boundConsoleName = "-";
    private String statusMessage = "Not bound";
    private ProcessHandler boundProcessHandler;
    private ProcessListener boundProcessListener;
    private boolean showRawLog;
    private boolean updatingRawToggle;
    private SqlHistoryEntry currentPreviewEntry;
    // 列表刷新期间暂停预览更新，避免 Raw 状态被中途重置
    private boolean suspendPreviewUpdate;
    // Console 发生切换后，下一次 Start 需要清空历史
    private boolean clearHistoryOnStart;
    // 正在等待 Parameters 的 SQL 块（按前缀分组，避免并发错配）
    private final Map<String, Deque<PendingBlock>> pendingBlocks = new LinkedHashMap<>();
    private long pendingSequence;
    // 状态栏图标（运行中/未运行）
    private final Icon statusRunningIcon;
    private final Icon statusStoppedIcon;

    // Console 文档变更监听：每次追加日志都会触发解析
    private final DocumentListener consoleListener = new DocumentListener() {
        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            onConsoleChanged();
        }
    };

    public SqlToolWindowPanel(Project project) {
        super(new BorderLayout(8, 8));
        this.project = project;
        this.settings = MyBatisLogHelperSettings.getInstance();
        this.historyService = SqlToolWindowHistoryService.getInstance();
        this.beautifyEnabled = settings.isDialogBeautified();
        this.statusRunningIcon = createTintedIcon(ICON_STATUS, STATUS_ACTIVE_COLOR);
        Icon disabledIcon = IconLoader.getDisabledIcon(ICON_STATUS);
        this.statusStoppedIcon = disabledIcon != null ? disabledIcon : ICON_STATUS;

        add(createToolbar(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        refreshList();
        updatePreview();
        refreshConsoleList(true);
    }

    private JComponent createToolbar() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        // 顶部工具栏：会话选择、开始/停止、清理、设置
        JButton refreshButton = createIconButton(ICON_REFRESH, "Refresh");
        refreshButton.addActionListener(e -> refreshConsoleList(false));

        consoleCombo = new JComboBox<>();
        consoleCombo.setPreferredSize(new Dimension(220, consoleCombo.getPreferredSize().height));
        consoleCombo.addActionListener(e -> {
            if (!refreshingConsoleList) {
                bindSelectedConsole();
            }
        });
        JBLabel consoleLabel = new JBLabel(ICON_CONSOLE);
        startButton = createIconButton(ICON_START, "Start");
        startButton.addActionListener(e -> startCapture());
        stopButton = createIconButton(ICON_STOP, "Stop");
        stopButton.addActionListener(e -> stopCapture());
        clearButton = createIconButton(ICON_CLEAR, "Clear");
        clearButton.addActionListener(e -> clearHistory());

        ActionToolbar beautifyToolbar = createBeautifyToolbar();

        filterField = new SearchTextField();
        // 搜索框使用占位提示，宽度更符合工具栏布局
        filterField.getTextEditor().getEmptyText().setText("Filter SQL");
        filterField.setToolTipText("Filter SQL");
        filterField.setPreferredSize(JBUI.size(180, filterField.getPreferredSize().height));
        filterField.setMinimumSize(JBUI.size(140, filterField.getPreferredSize().height));
        filterField.getTextEditor().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }
        });

        leftPanel.add(consoleLabel);
        leftPanel.add(consoleCombo);
        leftPanel.add(refreshButton);
        leftPanel.add(startButton);
        leftPanel.add(stopButton);
        leftPanel.add(clearButton);
        leftPanel.add(createToolbarSeparator());
        leftPanel.add(beautifyToolbar.getComponent());
        leftPanel.add(filterField);
        panel.add(leftPanel, BorderLayout.WEST);
        updateCaptureButtons();
        updateClearButton(historyService.list().size());

        return panel;
    }

    private JComponent createContent() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));

        list = new JBList<>() {
            @Override
            public String getToolTipText(MouseEvent event) {
                // 禁用悬浮提示，避免完整 SQL 影响阅读
                return null;
            }
        };
        // 禁用可展开项提示（避免自动显示完整 SQL）
        list.setExpandableItemsEnabled(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getEmptyText().setText("Waiting for MyBatis logs...");
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(
                    @NotNull javax.swing.JList<? extends SqlHistoryEntry> list,
                    SqlHistoryEntry value,
                    int index,
                    boolean selected,
                    boolean hasFocus
            ) {
                if (value == null) {
                    return;
                }
                // 根据 SQL 类型展示不同图标（查询/写入）
                setIcon(resolveSqlIcon(value.sql()));
                append("[" + value.timeText() + "] ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(value.snippetText(120), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        });
        list.addListSelectionListener(e -> updatePreview());

        JPanel listPanel = new JPanel(new BorderLayout(0, 4));
        // 左侧列表仅作为索引，禁用横向滚动条并截断显示
        listScrollPane = new JBScrollPane(list);
        listScrollPane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listPanel.add(listScrollPane, BorderLayout.CENTER);
        listPanel.setPreferredSize(new Dimension(280, 240));

        previewField = createEditorField("");

        JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
        previewPanel.add(createPreviewHeader(), BorderLayout.NORTH);
        // 预览区域使用滚动容器，避免长 SQL 显示不全
        previewScrollPane = new JBScrollPane(previewField);
        previewScrollPane.setBorder(JBUI.Borders.empty());
        previewPanel.add(previewScrollPane, BorderLayout.CENTER);

        panel.add(listPanel, BorderLayout.WEST);
        panel.add(previewPanel, BorderLayout.CENTER);
        return panel;
    }

    private JComponent createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        statusLabel = new JBLabel();
        updateStatusText();
        recordsLabel = new JBLabel();
        updateRecordsLabel(0);
        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(recordsLabel, BorderLayout.EAST);
        panel.setBorder(JBUI.Borders.emptyTop(4));
        return panel;
    }

    private void bindToCurrentConsole() {
        // 只允许绑定当前聚焦的 Console 编辑器
        Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (selected == null || !EditorUtils.isConsoleEditor(selected)) {
            setStatus("No console editor is focused.");
            return;
        }
        selectConsoleInCombo(selected);
        bindEditor(selected);
    }

    private void bindEditor(Editor editor) {
        // 解绑旧监听，绑定新文档
        detachConsoleListener();
        detachProcessListener();
        // 绑定前先清理未完成的解析状态
        resetPendingBlocks();
        // 记录是否切换了 Console，切换后下一次 Start 清空历史
        if (boundEditor != null && boundEditor != editor) {
            clearHistoryOnStart = true;
        }
        boundEditor = editor;
        boundDocument = editor.getDocument();
        boundConsoleName = resolveConsoleDisplayName(editor);
        // 绑定后从当前末尾开始监听，避免一次性扫完整日志
        lastProcessedLineCount = boundDocument.getLineCount();
        lastProcessedParametersLineIndex = -1;
        lastProcessedParametersLineText = null;
        attachProcessListener(editor);
        setStatus("Bound to console. Ready.");
        attachConsoleListenerIfNeeded();
        updateCaptureButtons();
    }

    private void startCapture() {
        // 开始监听：后续变更会触发增量解析
        capturing = true;
        // 未绑定时优先绑定当前下拉选择的会话
        if (boundDocument == null) {
            bindSelectedConsole();
        }
        // 切换 Console 后首次 Start 需要清空历史列表
        if (clearHistoryOnStart) {
            clearHistoryOnStart = false;
            clearHistory();
        }
        if (boundDocument != null) {
            // 重新开始监听时从当前末尾开始，避免重复解析旧日志
            resetPendingBlocks();
            lastProcessedLineCount = boundDocument.getLineCount();
            lastProcessedParametersLineIndex = -1;
            lastProcessedParametersLineText = null;
            attachConsoleListenerIfNeeded();
            setStatus("Capturing...");
            updateCaptureButtons();
            return;
        }
        setStatus("Bind a console first.");
        updateCaptureButtons();
    }

    private void stopCapture() {
        // 停止监听：移除文档监听器
        capturing = false;
        detachConsoleListener();
        setStatus("Stopped.");
        updateCaptureButtons();
    }

    private void onConsoleChanged() {
        if (!capturing || boundDocument == null) {
            return;
        }
        // 仅处理新增行，避免重复扫描
        Document document = boundDocument;
        int lineCount = document.getLineCount();
        if (lineCount == 0) {
            return;
        }
        if (lineCount < lastProcessedLineCount) {
            // Console 被清空或重启，清理解析状态
            resetPendingBlocks();
        }
        int start = resolveStartLine(lineCount);
        for (int i = start; i < lineCount; i++) {
            String lineText = getLineText(document, i);
            if (containsPreparingMarker(lineText)) {
                handlePreparingLine(lineText);
                continue;
            }
            if (containsParametersMarker(lineText)) {
                if (isDuplicateParametersLine(i, lineText)) {
                    continue;
                }
                handleParametersLine(lineText, i);
                lastProcessedParametersLineIndex = i;
                lastProcessedParametersLineText = lineText;
                continue;
            }
            if (!pendingBlocks.isEmpty()) {
                handlePendingLine(lineText);
            }
        }
        lastProcessedLineCount = lineCount;
    }

    private boolean containsPreparingMarker(String line) {
        return containsMarker(line, PREPARING_MARKER_PATTERN);
    }

    private boolean containsParametersMarker(String line) {
        return containsMarker(line, PARAMETERS_MARKER_PATTERN);
    }

    private int resolveStartLine(int lineCount) {
        if (lastProcessedLineCount <= 0) {
            return 0;
        }
        if (lineCount < lastProcessedLineCount) {
            // Console 被清空或重新开始
            return 0;
        }
        if (lineCount == lastProcessedLineCount) {
            // 行数未变，重扫最后一行
            return Math.max(0, lineCount - 1);
        }
        // 新增行，直接从旧行数开始
        return Math.max(0, lastProcessedLineCount);
    }

    private boolean isDuplicateParametersLine(int lineIndex, String lineText) {
        return lineIndex == lastProcessedParametersLineIndex
                && lineText != null
                && lineText.equals(lastProcessedParametersLineText);
    }

    private String getLineText(Document document, int lineIndex) {
        int start = document.getLineStartOffset(lineIndex);
        int end = document.getLineEndOffset(lineIndex);
        if (start >= end) {
            return "";
        }
        return document.getText(new TextRange(start, end));
    }

    private void handlePreparingLine(String lineText) {
        String sqlPart = extractAfterMarker(lineText, PREPARING_MARKER_PATTERN).trim();
        String prefix = extractPrefix(lineText, PREPARING_MARKER_PATTERN);
        PendingBlock block = new PendingBlock(prefix, sqlPart, ++pendingSequence);
        pendingBlocks.computeIfAbsent(normalizePrefix(prefix), key -> new ArrayDeque<>()).addLast(block);
        prunePendingBlocks();
    }

    private void handleParametersLine(String lineText, int lineIndex) {
        String prefix = extractPrefix(lineText, PARAMETERS_MARKER_PATTERN);
        PendingBlock block = pollPendingBlockByPrefix(prefix);
        if (block == null) {
            // 兜底：仅在只存在一个待匹配块时，避免误配
            block = pollOnlyPendingBlock();
        }
        if (block == null) {
            // 兜底：尝试在最近窗口内回扫 Preparing
            block = extractPendingBlockFromWindow(prefix, lineIndex);
            if (block == null) {
                return;
            }
        }
        String parametersRaw = extractAfterMarker(lineText, PARAMETERS_MARKER_PATTERN).trim();
        String sqlTemplate = block.sqlBuilder.toString().trim();
        if (sqlTemplate.isEmpty()) {
            return;
        }
        // 还原 SQL 并追加到工具窗口历史
        SqlRestorer restorer = new SqlRestorer();
        SqlRestoreResult result = restorer.restore(sqlTemplate, parametersRaw, settings.toRenderOptions());
        String restored = result.restoredSql();
        if (!settings.isAppendSemicolon()) {
            restored = trimTrailingSemicolon(restored);
        }
        historyService.add(restored, sqlTemplate, parametersRaw);
        SwingUtilities.invokeLater(this::refreshList);
    }

    private void handlePendingLine(String lineText) {
        if (pendingBlocks.isEmpty()) {
            return;
        }
        String trimmed = lineText.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        PendingBlock matched = findBestMatchingBlock(lineText);
        if (matched != null) {
            if (matched.sqlBuilder.length() > 0) {
                matched.sqlBuilder.append(' ');
            }
            matched.sqlBuilder.append(trimmed);
            // 命中续行后重置噪音计数
            matched.interleavingCount = 0;
        }
        // 非续行行：视为噪音日志，允许一定数量的插入
        incrementInterleavingForOthers(matched);
    }

    // 清理正在解析的 Preparing/Parameters 块
    private void resetPendingBlocks() {
        pendingBlocks.clear();
        pendingSequence = 0;
    }

    // 修剪待匹配块数量，防止长期无 Parameters 导致无限增长
    private void prunePendingBlocks() {
        int total = countPendingBlocks();
        if (total <= MAX_PENDING_BLOCKS) {
            return;
        }
        int removeCount = total - MAX_PENDING_BLOCKS;
        List<PendingBlockHolder> holders = new ArrayList<>();
        for (Map.Entry<String, Deque<PendingBlock>> entry : pendingBlocks.entrySet()) {
            for (PendingBlock block : entry.getValue()) {
                holders.add(new PendingBlockHolder(entry.getKey(), block));
            }
        }
        holders.sort(Comparator.comparingLong(holder -> holder.block.sequence));
        for (int i = 0; i < removeCount && i < holders.size(); i++) {
            removePendingBlock(holders.get(i));
        }
    }

    // 统计待匹配块总量
    private int countPendingBlocks() {
        int total = 0;
        for (Deque<PendingBlock> queue : pendingBlocks.values()) {
            total += queue.size();
        }
        return total;
    }

    // 移除指定待匹配块
    private void removePendingBlock(PendingBlockHolder holder) {
        if (holder == null) {
            return;
        }
        Deque<PendingBlock> queue = pendingBlocks.get(holder.key);
        if (queue == null) {
            return;
        }
        queue.remove(holder.block);
        if (queue.isEmpty()) {
            pendingBlocks.remove(holder.key);
        }
    }

    // 规范化前缀（避免 null 作为 Map key）
    private String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String normalized = LOG_DATE_TIME_PREFIX.matcher(prefix).replaceFirst("");
        normalized = LOG_TIME_ONLY_PREFIX.matcher(normalized).replaceFirst("");
        return normalized.trim();
    }

    // 根据 Parameters 前缀出队对应待匹配块
    private PendingBlock pollPendingBlockByPrefix(String prefix) {
        String key = normalizePrefix(prefix);
        Deque<PendingBlock> queue = pendingBlocks.get(key);
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        PendingBlock block = queue.pollFirst();
        if (queue.isEmpty()) {
            pendingBlocks.remove(key);
        }
        return block;
    }

    // 兜底：当仅有一个待匹配块时直接取出，避免空结果
    private PendingBlock pollOnlyPendingBlock() {
        if (pendingBlocks.size() != 1) {
            return null;
        }
        Map.Entry<String, Deque<PendingBlock>> entry = pendingBlocks.entrySet().iterator().next();
        Deque<PendingBlock> queue = entry.getValue();
        if (queue == null || queue.size() != 1) {
            return null;
        }
        PendingBlock block = queue.pollFirst();
        pendingBlocks.clear();
        return block;
    }

    // 在所有待匹配块中选择“最可能”的续行目标（以最新块为准）
    private PendingBlock findBestMatchingBlock(String lineText) {
        PendingBlock best = null;
        for (Deque<PendingBlock> queue : pendingBlocks.values()) {
            for (PendingBlock block : queue) {
                if (!isContinuationLine(lineText, block.prefix)) {
                    continue;
                }
                if (best == null || block.sequence > best.sequence) {
                    best = block;
                }
            }
        }
        return best;
    }

    // 对未命中的块累计噪音行数，超过阈值则丢弃
    private void incrementInterleavingForOthers(PendingBlock matched) {
        for (java.util.Iterator<Map.Entry<String, Deque<PendingBlock>>> entryIterator =
             pendingBlocks.entrySet().iterator(); entryIterator.hasNext(); ) {
            Map.Entry<String, Deque<PendingBlock>> entry = entryIterator.next();
            Deque<PendingBlock> queue = entry.getValue();
            for (java.util.Iterator<PendingBlock> blockIterator = queue.iterator(); blockIterator.hasNext(); ) {
                PendingBlock block = blockIterator.next();
                if (block == matched) {
                    continue;
                }
                block.interleavingCount++;
                if (block.interleavingCount > MAX_INTERLEAVING_LINES) {
                    blockIterator.remove();
                }
            }
            if (queue.isEmpty()) {
                entryIterator.remove();
            }
        }
    }

    // 当未命中待匹配块时，从最近窗口回扫 Preparing
    private PendingBlock extractPendingBlockFromWindow(String parametersPrefix, int parametersLineIndex) {
        if (boundDocument == null || parametersLineIndex <= 0) {
            return null;
        }
        int start = Math.max(0, parametersLineIndex - 80);
        String normalizedPrefix = normalizePrefix(parametersPrefix);
        int preparingLineIndex = -1;
        String preparingLineText = null;
        // 向上回扫找到最近的 Preparing
        for (int i = parametersLineIndex - 1; i >= start; i--) {
            String line = getLineText(boundDocument, i);
            if (!containsPreparingMarker(line)) {
                continue;
            }
            String prefix = normalizePrefix(extractPrefix(line, PREPARING_MARKER_PATTERN));
            if (!normalizedPrefix.isEmpty() && !normalizedPrefix.equals(prefix)) {
                continue;
            }
            preparingLineIndex = i;
            preparingLineText = line;
            break;
        }
        if (preparingLineIndex < 0 || preparingLineText == null) {
            return null;
        }
        String sqlPart = extractAfterMarker(preparingLineText, PREPARING_MARKER_PATTERN).trim();
        PendingBlock block = new PendingBlock(extractPrefix(preparingLineText, PREPARING_MARKER_PATTERN), sqlPart, ++pendingSequence);
        // 从 Preparing 到 Parameters 之间按续行规则拼接
        int interleaving = 0;
        for (int i = preparingLineIndex + 1; i < parametersLineIndex; i++) {
            String line = getLineText(boundDocument, i);
            if (line == null || line.isBlank()) {
                continue;
            }
            if (containsPreparingMarker(line) || containsParametersMarker(line)) {
                // 中间出现新的块，直接终止
                return null;
            }
            if (isContinuationLine(line, block.prefix)) {
                if (block.sqlBuilder.length() > 0) {
                    block.sqlBuilder.append(' ');
                }
                block.sqlBuilder.append(line.trim());
                interleaving = 0;
            } else {
                interleaving++;
                if (interleaving > MAX_INTERLEAVING_LINES) {
                    return null;
                }
            }
        }
        return block;
    }

    // 判断一行是否可能是 SQL 续行
    private boolean isContinuationLine(String line, String preparingPrefix) {
        if (line == null) {
            return false;
        }
        if (preparingPrefix != null && !preparingPrefix.isEmpty() && line.startsWith(preparingPrefix)) {
            return true;
        }
        if (line.startsWith(" ") || line.startsWith("\t")) {
            return true;
        }
        String trimmed = line.trim();
        if (trimmed.contains("?")) {
            return true;
        }
        if (SQL_CONDITION_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : SQL_CONTINUATION_PREFIXES) {
            if (lower.startsWith(prefix + " ") || lower.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    // 判断一行是否包含标记
    private boolean containsMarker(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return false;
        }
        return pattern.matcher(line).find();
    }

    // 提取标记后面的正文
    private String extractAfterMarker(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return "";
        }
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return line.substring(matcher.end());
        }
        return line;
    }

    // 提取标记前缀（通常是日志头部）
    private String extractPrefix(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return "";
        }
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return line.substring(0, matcher.start());
        }
        return "";
    }

    private void clearHistory() {
        historyService.clear();
        refreshList();
        updatePreview();
    }

    private void refreshList() {
        // 保留当前选中项，避免新增日志后自动跳到最新
        SqlHistoryEntry selectedEntry = list.getSelectedValue();
        int selectedIndex = list.getSelectedIndex();
        boolean wasAtBottom = isListScrollAtBottom();
        Rectangle visibleRect = wasAtBottom ? null : list.getVisibleRect();
        suspendPreviewUpdate = true;

        try {
            // 过滤关键字（简单 contains）
            List<SqlHistoryEntry> entries = historyService.list();
            int totalCount = entries.size();
            String filter = filterField.getText();
            if (filter != null && !filter.isBlank()) {
                String lower = filter.toLowerCase();
                entries = entries.stream()
                        .filter(e -> e.sql() != null && e.sql().toLowerCase().contains(lower))
                        .toList();
            }
            list.setListData(entries.toArray(new SqlHistoryEntry[0]));
            updateRecordsLabel(totalCount);
            updateClearButton(totalCount);

            Integer targetIndex = null;
            if (selectedEntry != null) {
                int index = entries.indexOf(selectedEntry);
                if (index >= 0) {
                    targetIndex = index;
                }
            }
            if (targetIndex == null && selectedIndex >= 0 && selectedIndex < entries.size()) {
                targetIndex = selectedIndex;
            }
            if (targetIndex != null) {
                list.setSelectedIndex(targetIndex);
            }

            // 智能自动滚动：在底部时跟随新增；非底部时保持视野不变
            if (wasAtBottom) {
                scrollListToBottom();
            } else if (visibleRect != null) {
                list.scrollRectToVisible(visibleRect);
            }
        } finally {
            suspendPreviewUpdate = false;
        }
        updatePreview();
    }

    private void updatePreview() {
        if (suspendPreviewUpdate) {
            return;
        }
        if (previewField == null) {
            return;
        }
        SqlHistoryEntry entry = list.getSelectedValue();
        if (entry == null) {
            setShowRawLog(false);
            previewField.setText("");
            currentPreviewEntry = null;
            return;
        }
        if (currentPreviewEntry == null || !currentPreviewEntry.equals(entry)) {
            // 每条记录默认不展示原始日志
            setShowRawLog(false);
            currentPreviewEntry = entry;
        }
        // 预览区支持切换 Raw/Beautify/单行模式
        String text = buildPreviewText(entry);
        previewField.setText(text);
        refreshPreviewScroll();
        ApplicationManager.getApplication().runReadAction(() -> {
            Editor editor = previewField.getEditor();
            if (editor != null) {
                editor.getCaretModel().moveToOffset(0);
            }
        });
    }

    private void copySelected() {
        SqlHistoryEntry entry = list.getSelectedValue();
        if (entry == null) {
            return;
        }
        // 复制当前预览内容：Raw 模式下输出 Preparing/Parameters
        String toCopy = showRawLog ? buildRawLogText(entry) : buildSqlCopyText(entry);
        CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(toCopy));
    }

    /**
     * 刷新预览区域滚动条，避免切换长 SQL 时滚动条延迟出现。
     */
    private void refreshPreviewScroll() {
        if (previewScrollPane == null || previewField == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            previewField.revalidate();
            previewScrollPane.revalidate();
            previewScrollPane.repaint();
        });
    }

    private void setStatus(String text) {
        statusMessage = text;
        updateStatusText();
    }

    private void attachConsoleListenerIfNeeded() {
        if (!capturing || boundDocument == null || consoleListenerAttached) {
            return;
        }
        boundDocument.addDocumentListener(consoleListener);
        consoleListenerAttached = true;
    }

    private void detachConsoleListener() {
        if (boundDocument == null || !consoleListenerAttached) {
            return;
        }
        boundDocument.removeDocumentListener(consoleListener);
        consoleListenerAttached = false;
    }

    // 绑定进程结束监听
    private void attachProcessListener(Editor editor) {
        if (editor == null) {
            return;
        }
        ProcessHandler handler = resolveProcessHandler(editor);
        if (handler == null) {
            return;
        }
        boundProcessHandler = handler;
        if (boundProcessListener == null) {
            boundProcessListener = new ProcessAdapter() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    onProcessTerminated(event);
                }
            };
        }
        handler.addProcessListener(boundProcessListener);
    }

    // 解绑进程结束监听
    private void detachProcessListener() {
        if (boundProcessHandler == null || boundProcessListener == null) {
            boundProcessHandler = null;
            return;
        }
        boundProcessHandler.removeProcessListener(boundProcessListener);
        boundProcessHandler = null;
    }

    // 进程结束时自动停止监听
    private void onProcessTerminated(@NotNull ProcessEvent event) {
        if (boundProcessHandler == null || event.getProcessHandler() != boundProcessHandler) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(this::handleProcessTerminated);
    }

    // 进程结束后的状态清理与自动重绑定
    private void handleProcessTerminated() {
        // 清理当前绑定，避免继续监听已结束的 Console
        detachConsoleListener();
        detachProcessListener();
        boundEditor = null;
        boundDocument = null;
        boundConsoleName = "-";
        capturing = false;
        // 进程结束后（即使 Console 复用），下一次 Start 需要清空历史
        clearHistoryOnStart = true;
        // 重新刷新 Console 列表并尝试自动绑定
        refreshConsoleList(true);
        updateCaptureButtons();
        if (boundEditor == null) {
            setStatus("Process terminated.");
        }
    }

    private void updateStatusText() {
        String name = boundConsoleName == null || boundConsoleName.isBlank() ? "-" : boundConsoleName;
        statusLabel.setText("Console: " + name + " | " + statusMessage);
        // 仅在 Start 后（capturing=true）才显示绿色状态
        boolean running = capturing && isBoundProcessRunning();
        statusLabel.setIcon(running ? statusRunningIcon : statusStoppedIcon);
        statusLabel.setIconTextGap(JBUI.scale(6));
    }

    // 判断列表滚动条是否在底部
    private boolean isListScrollAtBottom() {
        if (listScrollPane == null) {
            return true;
        }
        JScrollBar bar = listScrollPane.getVerticalScrollBar();
        if (bar == null) {
            return true;
        }
        int value = bar.getValue();
        int extent = bar.getModel().getExtent();
        int max = bar.getMaximum();
        int threshold = JBUI.scale(2);
        return value + extent >= max - threshold;
    }

    // 滚动到列表底部
    private void scrollListToBottom() {
        if (listScrollPane == null) {
            return;
        }
        JScrollBar bar = listScrollPane.getVerticalScrollBar();
        if (bar == null) {
            return;
        }
        bar.setValue(bar.getMaximum());
    }

    // 刷新开始/停止按钮状态，保证互斥
    private void updateCaptureButtons() {
        if (startButton == null || stopButton == null) {
            return;
        }
        boolean hasConsole = false;
        if (consoleCombo != null && consoleCombo.isEnabled()) {
            ConsoleItem item = (ConsoleItem) consoleCombo.getSelectedItem();
            hasConsole = item != null && item.editor != null;
        }
        startButton.setEnabled(!capturing && hasConsole);
        stopButton.setEnabled(capturing);
    }

    // 状态栏右侧记录条数提示
    private void updateRecordsLabel(int totalCount) {
        if (recordsLabel == null) {
            return;
        }
        // 记录条数显示随配置上限变化
        int limit = settings.getToolWindowHistoryLimit();
        recordsLabel.setText("Records: " + totalCount + "/" + limit);
    }

    // 根据记录数刷新清理按钮可用状态
    // 根据记录数刷新清理按钮可用状态
    private void updateClearButton(int totalCount) {
        if (clearButton == null) {
            return;
        }
        clearButton.setEnabled(totalCount > 0);
    }

    // 工具栏分隔线
    private JComponent createToolbarSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(JBUI.scale(8), JBUI.scale(18)));
        return separator;
    }

    // 根据 SQL 关键词判断类型并返回图标
    private Icon resolveSqlIcon(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return ICON_OTHER;
        }
        String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
        return switch (keyword) {
            case "SELECT" -> ICON_SELECT;
            case "INSERT" -> ICON_INSERT;
            case "UPDATE" -> ICON_UPDATE;
            case "DELETE" -> ICON_DELETE;
            default -> ICON_OTHER;
        };
    }

    // 预览区域头部：标题 + Raw 切换 + 复制按钮
    private JComponent createPreviewHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JBLabel label = new JBLabel("[SQL Preview]");
        rawToggleButton = createIconToggleButton(ICON_RAW, "Show Source Raw Log");
        rawToggleButton.addActionListener(e -> {
            if (updatingRawToggle) {
                return;
            }
            showRawLog = rawToggleButton.isSelected();
            updatePreview();
        });
        JButton copyButton = createIconButton(ICON_COPY, "Copy");
        copyButton.addActionListener(e -> copySelected());
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftPanel.add(label);
        leftPanel.add(rawToggleButton);
        leftPanel.add(copyButton);
        // 按钮紧挨 “SQL Preview” 文字右侧
        header.add(leftPanel, BorderLayout.WEST);
        header.setBorder(JBUI.Borders.emptyBottom(2));
        return header;
    }

    // Beautify 切换 Action，保持与 IDE 行为一致
    private ActionToolbar createBeautifyToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new BeautifyToggleAction());
        group.add(new OpenSettingsAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("MyBatisLogHelperToolbar", group, true);
        toolbar.setTargetComponent(this);
        return toolbar;
    }

    // Beautify 切换逻辑
    private final class BeautifyToggleAction extends ToggleAction {
        private BeautifyToggleAction() {
            super("Beautify", "Beautify", AllIcons.Actions.ReformatCode);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return beautifyEnabled;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            beautifyEnabled = state;
            updatePreview();
        }
    }

    // 打开设置页，允许用户调整历史记录上限等配置
    private final class OpenSettingsAction extends AnAction {
        private OpenSettingsAction() {
            super("Settings", "Settings", ICON_SETTINGS);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MyBatisLogHelperConfigurable.class);
            // 设置变更后刷新列表与状态，确保上限立即生效
            refreshList();
        }
    }

    // 统一创建图标按钮，保持扁平化风格
    private JButton createIconButton(Icon icon, String tooltip) {
        JButton button = new JButton(icon) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (getModel().isPressed() || getModel().isArmed()) {
                        g2.setColor(BUTTON_PRESSED_BG);
                        int arc = JBUI.scale(6);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                    } else if (getModel().isRollover()) {
                        g2.setColor(BUTTON_HOVER_BG);
                        int arc = JBUI.scale(6);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                    }
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setFocusable(false);
        button.setOpaque(false);
        button.setMargin(JBUI.insets(0));
        button.setBorder(JBUI.Borders.empty(2));
        button.setPreferredSize(JBUI.size(22, 22));
        button.setMinimumSize(JBUI.size(22, 22));
        button.setMaximumSize(JBUI.size(22, 22));
        // 使用灰度图标强化不可用态的视觉区分
        Icon disabledIcon = IconLoader.getDisabledIcon(icon);
        if (disabledIcon != null) {
            button.setDisabledIcon(disabledIcon);
            button.setDisabledSelectedIcon(disabledIcon);
        }
        return button;
    }

    // 统一创建图标切换按钮，用于 Raw 模式开关
    private JToggleButton createIconToggleButton(Icon icon, String tooltip) {
        JToggleButton button = new JToggleButton(icon) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (isSelected() || getModel().isPressed() || getModel().isArmed()) {
                        g2.setColor(BUTTON_PRESSED_BG);
                        int arc = JBUI.scale(6);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                    } else if (getModel().isRollover()) {
                        g2.setColor(BUTTON_HOVER_BG);
                        int arc = JBUI.scale(6);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                    }
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        button.setToolTipText(tooltip);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setFocusable(false);
        button.setOpaque(false);
        button.setMargin(JBUI.insets(0));
        button.setBorder(JBUI.Borders.empty(2));
        button.setPreferredSize(JBUI.size(22, 22));
        button.setMinimumSize(JBUI.size(22, 22));
        button.setMaximumSize(JBUI.size(22, 22));
        Icon disabledIcon = IconLoader.getDisabledIcon(icon);
        if (disabledIcon != null) {
            button.setDisabledIcon(disabledIcon);
            button.setDisabledSelectedIcon(disabledIcon);
        }
        return button;
    }

    // 生成预览区显示文本
    private String buildPreviewText(SqlHistoryEntry entry) {
        if (showRawLog) {
            String raw = buildRawLogText(entry);
            return raw == null ? "" : raw;
        }
        return beautifyEnabled
                ? beautifier.beautify(entry.sql())
                : toSingleLine(entry.sql());
    }

    // 生成 Raw 日志文本
    private String buildRawLogText(SqlHistoryEntry entry) {
        String raw = entry.rawLogText();
        return raw == null ? "" : raw;
    }

    // 复制 SQL 的文本逻辑，遵循用户配置
    private String buildSqlCopyText(SqlHistoryEntry entry) {
        if (settings.isCopyBeautified()) {
            return beautifyEnabled
                    ? beautifier.beautify(entry.sql())
                    : toSingleLine(entry.sql());
        }
        return entry.sql();
    }

    // 设置 Raw 开关状态，避免重复触发事件
    private void setShowRawLog(boolean enable) {
        showRawLog = enable;
        if (rawToggleButton != null) {
            updatingRawToggle = true;
            rawToggleButton.setSelected(enable);
            updatingRawToggle = false;
        }
    }

    // 判断当前绑定的进程是否处于运行状态
    private boolean isBoundProcessRunning() {
        ProcessHandler handler = boundProcessHandler;
        if (handler == null) {
            return false;
        }
        Boolean terminated = toBoolean(invokeMethod(handler, "isProcessTerminated"));
        if (terminated != null && terminated) {
            return false;
        }
        Boolean terminating = toBoolean(invokeMethod(handler, "isProcessTerminating"));
        if (terminating != null && terminating) {
            return false;
        }
        Boolean running = toBoolean(invokeMethod(handler, "isProcessRunning"));
        if (running != null) {
            return running;
        }
        Object process = invokeMethod(handler, "getProcess");
        if (process instanceof Process) {
            return ((Process) process).isAlive();
        }
        return capturing;
    }

    @Override
    public void dispose() {
        // 工具窗口销毁时释放监听器，避免内存泄漏或重复回调
        detachConsoleListener();
        detachProcessListener();
        boundEditor = null;
        boundDocument = null;
        resetPendingBlocks();
    }

    // 为状态栏图标创建可着色版本（用于运行态绿色显示）
    private static Icon createTintedIcon(Icon base, Color color) {
        if (base == null) {
            return null;
        }
        int width = base.getIconWidth();
        int height = base.getIconHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            base.paintIcon(null, g, 0, 0);
            g.setComposite(AlphaComposite.SrcAtop);
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
    }

    private void refreshConsoleList(boolean autoBind) {
        List<ConsoleItem> items = buildConsoleItems();
        refreshingConsoleList = true;
        if (items.isEmpty()) {
            consoleCombo.setModel(new DefaultComboBoxModel<>(new ConsoleItem[]{new ConsoleItem(null, NO_CONSOLE_TEXT)}));
            consoleCombo.setEnabled(false);
            refreshingConsoleList = false;
            if (autoBind) {
                boundConsoleName = "-";
                setStatus("No console found.");
            }
            return;
        }
        consoleCombo.setModel(new DefaultComboBoxModel<>(items.toArray(new ConsoleItem[0])));
        consoleCombo.setEnabled(true);
        ConsoleItem target = null;
        if (boundEditor != null) {
            target = findItem(items, boundEditor);
        }
        if (target == null && autoBind) {
            Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (selected != null && EditorUtils.isConsoleEditor(selected)) {
                target = findItem(items, selected);
            }
        }
        if (target == null) {
            target = items.get(0);
        }
        consoleCombo.setSelectedItem(target);
        refreshingConsoleList = false;
        // 当尚未绑定或当前绑定已失效时，自动绑定到列表选中项
        boolean shouldBind = autoBind || boundEditor == null || !containsEditor(items, boundEditor);
        if (shouldBind && target.editor != null) {
            bindEditor(target.editor);
        }
        updateCaptureButtons();
        updateClearButton(historyService.list().size());
    }

    private void selectConsoleInCombo(Editor editor) {
        if (consoleCombo == null || editor == null) {
            return;
        }
        DefaultComboBoxModel<ConsoleItem> model = (DefaultComboBoxModel<ConsoleItem>) consoleCombo.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            ConsoleItem item = model.getElementAt(i);
            if (item != null && item.editor == editor) {
                consoleCombo.setSelectedItem(item);
                return;
            }
        }
    }

    private void bindSelectedConsole() {
        if (consoleCombo == null) {
            return;
        }
        ConsoleItem item = (ConsoleItem) consoleCombo.getSelectedItem();
        if (item == null || item.editor == null) {
            setStatus("No console selected.");
            updateCaptureButtons();
            return;
        }
        bindEditor(item.editor);
    }

    private List<Editor> findConsoleEditors() {
        Editor[] editors = EditorFactory.getInstance().getAllEditors();
        List<Editor> consoles = new ArrayList<>();
        for (Editor editor : editors) {
            if (editor == null || editor.getProject() != project) {
                continue;
            }
            if (EditorUtils.isConsoleEditor(editor)) {
                consoles.add(editor);
            }
        }
        return consoles;
    }

    private List<ConsoleItem> buildConsoleItems() {
        // 优先从 Run/Debug 的 descriptor 里取“真正的运行名称/PID”
        Map<Editor, ConsoleDescriptorInfo> infoByEditor = new LinkedHashMap<>();
        try {
            RunContentManager contentManager = ExecutionManager.getInstance(project).getContentManager();
            for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
                if (descriptor == null) {
                    continue;
                }
                if (!isDescriptorActive(descriptor)) {
                    continue;
                }
                ExecutionConsole console = descriptor.getExecutionConsole();
                Editor editor = extractEditor(console);
                if (editor != null && EditorUtils.isConsoleEditor(editor)) {
                    ConsoleDescriptorInfo info = new ConsoleDescriptorInfo();
                    info.name = descriptor.getDisplayName();
                    info.mode = resolveModeFromConsole(console);
                    if (info.mode == null || info.mode.isBlank()) {
                        info.mode = resolveMode(descriptor);
                    }
                    info.pid = resolvePid(descriptor);
                    infoByEditor.put(editor, info);
                }
            }
        } catch (Exception ignored) {
            // 执行环境不可用时回退为编辑器扫描
        }

        List<ConsoleItem> items = new ArrayList<>();
        if (!infoByEditor.isEmpty()) {
            // 有 descriptor 信息时只展示这些 Console，避免重复/无名称的条目
            for (Map.Entry<Editor, ConsoleDescriptorInfo> entry : infoByEditor.entrySet()) {
                items.add(new ConsoleItem(entry.getKey(), buildDisplayName(entry.getValue())));
            }
            return items;
        }

        // 尝试从 Editor 的 DataContext 反查 RunContentDescriptor
        Map<Editor, ConsoleDescriptorInfo> infoFromContext = new LinkedHashMap<>();
        for (Editor editor : findConsoleEditors()) {
            ConsoleDescriptorInfo info = resolveDescriptorInfoFromEditor(editor);
            if (info != null) {
                infoFromContext.put(editor, info);
            }
        }
        if (!infoFromContext.isEmpty()) {
            for (Map.Entry<Editor, ConsoleDescriptorInfo> entry : infoFromContext.entrySet()) {
                items.add(new ConsoleItem(entry.getKey(), buildDisplayName(entry.getValue())));
            }
            return items;
        }

        // 无法解析到名称时不展示，避免出现多个 Console 的误导项
        return items;
    }

    private Editor extractEditor(ExecutionConsole console) {
        if (console == null) {
            return null;
        }
        try {
            // 先尝试从 Console 组件的数据上下文中获取 Editor
            DataContext context = getConsoleDataContext(console);
            if (context != null) {
                Editor editor = CommonDataKeys.EDITOR.getData(context);
                if (editor != null) {
                    return editor;
                }
                // 再尝试：从 Console 组件树中反查 Editor
                JComponent component = console.getPreferredFocusableComponent();
                if (component == null) {
                    component = console.getComponent();
                }
                Editor componentEditor = findEditorInComponent(component);
                if (componentEditor != null) {
                    return componentEditor;
                }
            }
        } catch (Exception ignored) {
            // 继续走反射兜底
        }
        try {
            // ConsoleViewImpl 有 getEditor()，这里用反射避免依赖实现类
            Object editor = invokeMethod(console, "getEditor");
            if (editor instanceof Editor) {
                return (Editor) editor;
            }
        } catch (Exception ignored) {
            // 没有 getEditor 方法则忽略
        }
        return null;
    }

    private String resolveConsoleName(Editor editor) {
        if (editor == null) {
            return NO_CONSOLE_TEXT;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file != null) {
            String presentableName = file.getPresentableName();
            if (presentableName != null && !presentableName.isBlank()) {
                return presentableName;
            }
            String name = file.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Console";
    }

    private String resolveConsoleDisplayName(Editor editor) {
        ConsoleDescriptorInfo info = findDescriptorInfo(editor);
        if (info != null) {
            return buildDisplayName(info);
        }
        return resolveConsoleName(editor);
    }

    private ConsoleDescriptorInfo findDescriptorInfo(Editor editor) {
        if (editor == null) {
            return null;
        }
        try {
            RunContentManager contentManager = ExecutionManager.getInstance(project).getContentManager();
            for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
                if (descriptor == null) {
                    continue;
                }
                if (!isDescriptorActive(descriptor)) {
                    continue;
                }
                Editor consoleEditor = extractEditor(descriptor.getExecutionConsole());
                if (consoleEditor == editor) {
                    ConsoleDescriptorInfo info = new ConsoleDescriptorInfo();
                    info.name = descriptor.getDisplayName();
                    String mode = resolveModeFromConsole(descriptor.getExecutionConsole());
                    info.mode = mode == null || mode.isBlank() ? resolveMode(descriptor) : mode;
                    info.pid = resolvePid(descriptor);
                    return info;
                }
            }
        } catch (Exception ignored) {
            // 解析失败时直接回退
        }
        // 兜底：从 Editor 的数据上下文反查
        return resolveDescriptorInfoFromEditor(editor);
    }

    private String buildDisplayName(ConsoleDescriptorInfo info) {
        String name = info == null || info.name == null || info.name.isBlank() ? "Console" : info.name;
        String pid = info == null || info.pid == null || info.pid.isBlank() ? "-" : info.pid;
        if (pid.equals("-")) {
            return name;
        }
        return name + " [" + pid + "]";
    }

    private String resolveMode(RunContentDescriptor descriptor) {
        if (descriptor == null) {
            return "-";
        }
        // 1) ToolWindowId（Run/Debug）
        String toolWindowId = readStringMethod(descriptor, "getContentToolWindowId", "getToolWindowId");
        if (toolWindowId == null || toolWindowId.isBlank()) {
            // 某些版本字段不是公开方法
            toolWindowId = readStringField(descriptor, "myContentToolWindowId", "myToolWindowId");
        }
        if (toolWindowId != null && !toolWindowId.isBlank()) {
            return normalizeMode(toolWindowId);
        }

        // 2) Executor 的名称/ID
        Object executor = invokeMethod(descriptor, "getExecutor");
        if (executor == null) {
            executor = readField(descriptor, "myExecutor");
        }
        if (executor != null) {
            String id = readStringMethod(executor, "getId", "getActionName");
            if (id != null && !id.isBlank()) {
                return normalizeMode(id);
            }
        }

        // 3) 类名兜底（Debug 相关类名）
        if (containsDebugHint(descriptor) || containsDebugHint(descriptor.getExecutionConsole())) {
            return "DEBUG";
        }
        return "RUN";
    }

    private String resolvePid(RunContentDescriptor descriptor) {
        if (descriptor == null) {
            return "-";
        }
        try {
            Object handler = invokeMethod(descriptor, "getProcessHandler");
            if (handler == null) {
                return "-";
            }
            // 常见实现提供 getProcessId()
            try {
                Object pid = invokeMethod(handler, "getProcessId");
                if (pid != null) {
                    return pid.toString();
                }
            } catch (Exception ignored) {
                // 忽略
            }
            // 尝试 getProcess().pid()
            try {
                Object process = invokeMethod(handler, "getProcess");
                if (process instanceof Process) {
                    return String.valueOf(((Process) process).pid());
                }
            } catch (Exception ignored) {
                // 忽略
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return "-";
    }

    // 根据 Console 组件树反查内部的 Editor
    private Editor findEditorInComponent(JComponent component) {
        if (component == null) {
            return null;
        }
        Editor[] editors = EditorFactory.getInstance().getAllEditors();
        for (Editor editor : editors) {
            if (editor == null || editor.getProject() != project) {
                continue;
            }
            JComponent content = editor.getContentComponent();
            if (content != null && SwingUtilities.isDescendingFrom(content, component)) {
                return editor;
            }
        }
        return null;
    }

    // 获取 Console 组件对应的数据上下文
    private DataContext getConsoleDataContext(ExecutionConsole console) {
        if (console == null) {
            return null;
        }
        try {
            JComponent component = console.getPreferredFocusableComponent();
            if (component == null) {
                component = console.getComponent();
            }
            if (component == null) {
                return null;
            }
            return DataManager.getInstance().getDataContext(component);
        } catch (Exception ignored) {
            return null;
        }
    }

    // 从 Console 的 DataContext 中解析运行模式
    private String resolveModeFromConsole(ExecutionConsole console) {
        DataContext context = getConsoleDataContext(console);
        return resolveModeFromContext(context);
    }

    // 从 DataContext 里解析运行模式（Run/Debug）
    private String resolveModeFromContext(DataContext context) {
        if (context == null) {
            return null;
        }
        try {
            Executor executor = (Executor) getDataByKey(context, "com.intellij.execution.ExecutionDataKeys", "EXECUTOR");
            if (executor != null) {
                String id = executor.getId();
                if (id != null && !id.isBlank()) {
                    return normalizeMode(id);
                }
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    // 从 Editor 的 DataContext 反查 RunContentDescriptor
    private ConsoleDescriptorInfo resolveDescriptorInfoFromEditor(Editor editor) {
        if (editor == null) {
            return null;
        }
        try {
            DataContext context = DataManager.getInstance().getDataContext(editor.getComponent());
            RunContentDescriptor descriptor =
                    (RunContentDescriptor) getDataByKey(context, "com.intellij.execution.ExecutionDataKeys", "RUN_CONTENT_DESCRIPTOR");
            if (descriptor == null) {
                return null;
            }
            if (!isDescriptorActive(descriptor)) {
                return null;
            }
            ConsoleDescriptorInfo info = new ConsoleDescriptorInfo();
            info.name = descriptor.getDisplayName();
            String mode = resolveModeFromContext(context);
            info.mode = mode == null || mode.isBlank() ? resolveMode(descriptor) : mode;
            info.pid = resolvePid(descriptor);
            return info;
        } catch (Exception ignored) {
            return null;
        }
    }

    // 解析当前 Console 对应的 ProcessHandler
    private ProcessHandler resolveProcessHandler(Editor editor) {
        if (editor == null) {
            return null;
        }
        try {
            RunContentManager contentManager = ExecutionManager.getInstance(project).getContentManager();
            for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
                if (descriptor == null || !isDescriptorActive(descriptor)) {
                    continue;
                }
                Editor consoleEditor = extractEditor(descriptor.getExecutionConsole());
                if (consoleEditor == editor) {
                    Object handler = invokeMethod(descriptor, "getProcessHandler");
                    if (handler instanceof ProcessHandler) {
                        return (ProcessHandler) handler;
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败继续兜底
        }
        try {
            DataContext context = DataManager.getInstance().getDataContext(editor.getComponent());
            RunContentDescriptor descriptor =
                    (RunContentDescriptor) getDataByKey(context, "com.intellij.execution.ExecutionDataKeys", "RUN_CONTENT_DESCRIPTOR");
            if (descriptor == null || !isDescriptorActive(descriptor)) {
                return null;
            }
            Object handler = invokeMethod(descriptor, "getProcessHandler");
            if (handler instanceof ProcessHandler) {
                return (ProcessHandler) handler;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    // 通过 DataKey 反射获取 DataContext 中的数据
    private Object getDataByKey(DataContext context, String keyClassName, String fieldName) {
        if (context == null) {
            return null;
        }
        try {
            Class<?> keyClass = Class.forName(keyClassName);
            Field field = keyClass.getField(fieldName);
            Object key = field.get(null);
            if (key instanceof DataKey) {
                return ((DataKey<?>) key).getData(context);
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    // 判断运行是否仍然活跃，避免停止后仍显示
    private boolean isDescriptorActive(RunContentDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        Object handler = invokeMethod(descriptor, "getProcessHandler");
        if (handler == null) {
            return false;
        }
        Boolean terminated = toBoolean(invokeMethod(handler, "isProcessTerminated"));
        if (terminated != null && terminated) {
            return false;
        }
        Boolean terminating = toBoolean(invokeMethod(handler, "isProcessTerminating"));
        if (terminating != null && terminating) {
            return false;
        }
        Boolean running = toBoolean(invokeMethod(handler, "isProcessRunning"));
        if (running != null) {
            return running;
        }
        Object process = invokeMethod(handler, "getProcess");
        if (process instanceof Process) {
            return ((Process) process).isAlive();
        }
        // 无法判断时默认可见
        return true;
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    // 依次调用方法，返回首个非空字符串
    private String readStringMethod(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }
        for (String method : methodNames) {
            Object result = invokeMethod(target, method);
            if (result != null) {
                String text = result.toString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    // 通过反射调用方法，兼容 public / declared 方法
    private Object invokeMethod(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            try {
                Method method = target.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    // 依次读取字段，返回首个非空字符串
    private String readStringField(Object target, String... fieldNames) {
        Object value = readField(target, fieldNames);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    // 通过反射读取字段，兼容不同版本字段命名
    private Object readField(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) {
            return null;
        }
        for (String name : fieldNames) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null) {
                    return value;
                }
            } catch (Exception ignored) {
                // continue
            }
        }
        return null;
    }

    private String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("debug")) {
            return "DEBUG";
        }
        if (lower.contains("run")) {
            return "RUN";
        }
        return raw;
    }

    private boolean containsDebugHint(Object obj) {
        if (obj == null) {
            return false;
        }
        String name = obj.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("debug") || name.contains("debugger");
    }

    private ConsoleItem findItem(List<ConsoleItem> items, Editor editor) {
        for (ConsoleItem item : items) {
            if (item.editor == editor) {
                return item;
            }
        }
        return null;
    }

    // 判断列表中是否包含指定 Editor
    private boolean containsEditor(List<ConsoleItem> items, Editor editor) {
        if (editor == null) {
            return false;
        }
        for (ConsoleItem item : items) {
            if (item != null && item.editor == editor) {
                return true;
            }
        }
        return false;
    }

    private static final class ConsoleItem {
        private final Editor editor;
        private final String name;

        private ConsoleItem(Editor editor, String name) {
            this.editor = editor;
            this.name = name == null || name.isBlank() ? "Console" : name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class ConsoleDescriptorInfo {
        private String name;
        private String mode;
        private String pid;
    }

    /**
     * 待匹配的日志块信息（Preparing 已到，等待 Parameters）。
     */
    private static final class PendingBlock {
        private final String prefix;
        private final StringBuilder sqlBuilder;
        private final long sequence;
        private int interleavingCount;

        private PendingBlock(String prefix, String sqlPart, long sequence) {
            this.prefix = prefix;
            this.sqlBuilder = new StringBuilder(sqlPart == null ? "" : sqlPart);
            this.sequence = sequence;
        }
    }

    /**
     * 待匹配块的索引包装，用于排序清理。
     */
    private static final class PendingBlockHolder {
        private final String key;
        private final PendingBlock block;

        private PendingBlockHolder(String key, PendingBlock block) {
            this.key = key;
            this.block = block;
        }
    }

    private EditorTextField createEditorField(String text) {
        // SQL 语法高亮（不可用则回退到纯文本）
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension("sql");
        if (fileType == UnknownFileType.INSTANCE) {
            fileType = PlainTextFileType.INSTANCE;
        }
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
