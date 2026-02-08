package com.mybatis.loghelper.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.time.format.DateTimeFormatter;

/**
 * 插件设置页配置入口.
 *
 * <p>该类负责将 {@link MyBatisLogHelperSettings} 的持久化配置映射到
 * IDEA Settings UI，并处理“是否修改/应用/重置”等生命周期。</p>
 */
public final class MyBatisLogHelperConfigurable implements Configurable {
    /**
     * Boolean 输出风格开关组件.
     */
    private JBCheckBox booleanAsNumberCheckBox;

    /**
     * DateTime 格式输入框组件.
     */
    private JBTextField dateTimeFormatField;

    /**
     * 自动复制开关组件.
     */
    private JBCheckBox autoCopyCheckBox;
    /**
     * 自动追加分号开关组件.
     */
    private JBCheckBox appendSemicolonCheckBox;
    /**
     * 复制后自动关闭弹窗开关组件.
     */
    private JBCheckBox closeAfterCopyCheckBox;
    /**
     * 结果弹窗默认美化开关组件.
     */
    private JBCheckBox beautifyByDefaultCheckBox;
    /**
     * 复制是否保留美化格式开关组件.
     */
    private JBCheckBox copyBeautifiedCheckBox;

    /**
     * 设置页根面板.
     */
    private JPanel panel;

    /**
     * 设置页显示名称.
     *
     * @return 页面标题
     */
    @Override
    public @Nls String getDisplayName() {
        return "MyBatis Log Helper";
    }

    /**
     * 创建设置页 UI 组件.
     *
     * @return 设置页根组件
     */
    @Override
    public @Nullable JComponent createComponent() {
        booleanAsNumberCheckBox = new JBCheckBox("Boolean output style: 1/0 (off = TRUE/FALSE)");
        dateTimeFormatField = new JBTextField();
        autoCopyCheckBox = new JBCheckBox("Auto copy after restore");
        appendSemicolonCheckBox = new JBCheckBox("Append semicolon automatically");
        // 复制后自动关闭弹窗
        closeAfterCopyCheckBox = new JBCheckBox("Close dialog after copy");
        // 结果弹窗默认美化
        beautifyByDefaultCheckBox = new JBCheckBox("Beautify by default in result dialog");
        copyBeautifiedCheckBox = new JBCheckBox("Copy keeps beautified format");

        panel = FormBuilder.createFormBuilder()
                .addComponent(booleanAsNumberCheckBox)
                .addLabeledComponent("DateTime format:", dateTimeFormatField)
                .addComponent(autoCopyCheckBox)
                .addComponent(closeAfterCopyCheckBox)
                .addComponent(copyBeautifiedCheckBox)
                .addComponent(appendSemicolonCheckBox)
                .addComponent(beautifyByDefaultCheckBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        // 初始化 UI 值.
        reset();
        return panel;
    }

    /**
     * 判断当前 UI 值是否与持久化配置不一致.
     *
     * @return true 表示有改动
     */
    @Override
    public boolean isModified() {
        MyBatisLogHelperSettings settings = MyBatisLogHelperSettings.getInstance();
        // 新增设置项也参与修改比较
        return settings.isBooleanAsOneZero() != booleanAsNumberCheckBox.isSelected()
                || settings.isAutoCopy() != autoCopyCheckBox.isSelected()
                || settings.isAppendSemicolon() != appendSemicolonCheckBox.isSelected()
                || settings.isCloseAfterCopy() != closeAfterCopyCheckBox.isSelected()
                || settings.isCopyBeautified() != copyBeautifiedCheckBox.isSelected()
                || settings.isDialogBeautified() != beautifyByDefaultCheckBox.isSelected()
                || !settings.getDateTimeFormat().equals(dateTimeFormatField.getText().trim());
    }

    /**
     * 应用设置页改动到持久化配置.
     *
     * @throws ConfigurationException 当输入格式非法时抛出
     */
    @Override
    public void apply() throws ConfigurationException {
        String pattern = dateTimeFormatField.getText().trim();
        if (pattern.isEmpty()) {
            throw new ConfigurationException("DateTime format cannot be empty.");
        }

        // 在保存前验证格式，避免运行时格式化失败.
        try {
            DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException ex) {
            throw new ConfigurationException("Invalid DateTime format: " + ex.getMessage());
        }

        MyBatisLogHelperSettings settings = MyBatisLogHelperSettings.getInstance();
        settings.setBooleanAsOneZero(booleanAsNumberCheckBox.isSelected());
        settings.setDateTimeFormat(pattern);
        settings.setAutoCopy(autoCopyCheckBox.isSelected());
        settings.setAppendSemicolon(appendSemicolonCheckBox.isSelected());
        // 应用新增配置项
        settings.setCloseAfterCopy(closeAfterCopyCheckBox.isSelected());
        settings.setCopyBeautified(copyBeautifiedCheckBox.isSelected());
        settings.setDialogBeautified(beautifyByDefaultCheckBox.isSelected());
    }

    /**
     * 用持久化配置重置 UI 值.
     */
    @Override
    public void reset() {
        MyBatisLogHelperSettings settings = MyBatisLogHelperSettings.getInstance();
        if (booleanAsNumberCheckBox != null) {
            booleanAsNumberCheckBox.setSelected(settings.isBooleanAsOneZero());
        }
        if (dateTimeFormatField != null) {
            dateTimeFormatField.setText(settings.getDateTimeFormat());
        }
        if (autoCopyCheckBox != null) {
            autoCopyCheckBox.setSelected(settings.isAutoCopy());
        }
        if (appendSemicolonCheckBox != null) {
            appendSemicolonCheckBox.setSelected(settings.isAppendSemicolon());
        }
        // 还原新增设置项
        if (closeAfterCopyCheckBox != null) {
            closeAfterCopyCheckBox.setSelected(settings.isCloseAfterCopy());
        }
        if (copyBeautifiedCheckBox != null) {
            copyBeautifiedCheckBox.setSelected(settings.isCopyBeautified());
        }
        if (beautifyByDefaultCheckBox != null) {
            beautifyByDefaultCheckBox.setSelected(settings.isDialogBeautified());
        }
    }

    /**
     * 释放 UI 资源引用.
     *
     * <p>避免设置页多次打开后产生无效引用。</p>
     */
    @Override
    public void disposeUIResources() {
        panel = null;
        booleanAsNumberCheckBox = null;
        dateTimeFormatField = null;
        autoCopyCheckBox = null;
        appendSemicolonCheckBox = null;
        closeAfterCopyCheckBox = null;
        copyBeautifiedCheckBox = null;
        beautifyByDefaultCheckBox = null;
    }
}
