package com.mybatis.loghelper.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.ColorUtil;
import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.util.IconLoader;

import javax.swing.UIManager;

public final class SqlToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 按主题显式选择图标，避免暗色主题选中态回退到亮色图标
        toolWindow.setIcon(resolveToolWindowIcon());
        // 创建工具窗口主面板
        SqlToolWindowPanel panel = new SqlToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * 按当前主题选择 ToolWindow 图标（亮色/暗色）。
     *
     * @return 图标实例
     */
    private static javax.swing.Icon resolveToolWindowIcon() {
        boolean dark = isDarkTheme();
        String path = dark ? "/icon/svg_dark.svg" : "/icon/svg.svg";
        return IconLoader.getIcon(path, SqlToolWindowFactory.class);
    }

    /**
     * 基于 UI 颜色判断当前主题是否偏暗。
     *
     * @return true 表示暗色主题
     */
    private static boolean isDarkTheme() {
        java.awt.Color background = UIManager.getColor("Panel.background");
        if (background == null) {
            return false;
        }
        return ColorUtil.isDark(background);
    }
}
