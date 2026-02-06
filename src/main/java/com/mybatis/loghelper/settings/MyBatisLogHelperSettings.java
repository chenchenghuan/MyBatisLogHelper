package com.mybatis.loghelper.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.mybatis.loghelper.parser.SqlRenderOptions;
import org.jetbrains.annotations.NotNull;

/**
 * 插件应用级持久化配置服务.
 *
 * <p>该服务通过 IntelliJ 平台的 {@link PersistentStateComponent} 将用户配置持久化到
 * {@code mybatis-log-helper.xml}，并在启动时自动恢复。</p>
 */
@Service(Service.Level.APP)
@State(name = "MyBatisLogHelperSettings", storages = @Storage("mybatis-log-helper.xml"))
public final class MyBatisLogHelperSettings implements PersistentStateComponent<MyBatisLogHelperSettings.StateValue> {
    /**
     * 持久化字段载体对象.
     *
     * <p>仅包含可序列化字段，不包含业务逻辑。</p>
     */
    public static final class StateValue {
        /**
         * Boolean 是否输出为 1/0.
         */
        public boolean booleanAsOneZero = false;

        /**
         * 日期时间输出格式.
         */
        public String dateTimeFormat = "yyyy-MM-dd HH:mm:ss";

        /**
         * 是否在还原后自动复制.
         */
        public boolean autoCopy = false;
        // 点击复制按钮时，是否自动关闭弹窗
        public boolean closeAfterCopy = false;
        // 弹窗是否默认使用美化模式显示
        public boolean dialogBeautified = false;
        // 保存的弹窗宽度
        public int dialogWidth = 760;
        // 保存的弹窗高度
        public int dialogHeight = 320;
    }

    /**
     * 当前内存态配置.
     */
    private StateValue state = new StateValue();

    /**
     * 获取应用级单例服务.
     *
     * @return 配置服务单例
     */
    public static MyBatisLogHelperSettings getInstance() {
        return ApplicationManager.getApplication().getService(MyBatisLogHelperSettings.class);
    }

    /**
     * 返回持久化所需状态对象.
     *
     * @return 当前状态
     */
    @Override
    public StateValue getState() {
        return state;
    }

    /**
     * 加载持久化状态.
     *
     * @param state 从存储恢复的状态
     */
    @Override
    public void loadState(@NotNull StateValue state) {
        this.state = state;

        // 防御式兜底，避免历史配置缺失导致空值.
        if (this.state.dateTimeFormat == null || this.state.dateTimeFormat.isBlank()) {
            this.state.dateTimeFormat = "yyyy-MM-dd HH:mm:ss";
        }
        // 兼容旧配置：宽高非法时回退默认值
        if (this.state.dialogWidth <= 0) {
            this.state.dialogWidth = 760;
        }
        if (this.state.dialogHeight <= 0) {
            this.state.dialogHeight = 320;
        }
    }

    /**
     * @return Boolean 是否输出 1/0
     */
    public boolean isBooleanAsOneZero() {
        return state.booleanAsOneZero;
    }

    /**
     * @param booleanAsOneZero 设置 Boolean 输出风格
     */
    public void setBooleanAsOneZero(boolean booleanAsOneZero) {
        state.booleanAsOneZero = booleanAsOneZero;
    }

    /**
     * @return 日期时间格式
     */
    public String getDateTimeFormat() {
        return state.dateTimeFormat;
    }

    /**
     * @param dateTimeFormat 设置日期时间格式
     */
    public void setDateTimeFormat(String dateTimeFormat) {
        state.dateTimeFormat = dateTimeFormat;
    }

    /**
     * @return 是否自动复制
     */
    public boolean isAutoCopy() {
        return state.autoCopy;
    }

    /**
     * @param autoCopy 设置是否自动复制
     */
    public void setAutoCopy(boolean autoCopy) {
        state.autoCopy = autoCopy;
    }

    /**
     * @return 点击复制后是否自动关闭弹窗
     */
    public boolean isCloseAfterCopy() {
        return state.closeAfterCopy;
    }

    /**
     * @param closeAfterCopy 设置复制后是否自动关闭弹窗
     */
    public void setCloseAfterCopy(boolean closeAfterCopy) {
        state.closeAfterCopy = closeAfterCopy;
    }

    /**
     * @return 弹窗是否默认使用美化模式显示
     */
    public boolean isDialogBeautified() {
        return state.dialogBeautified;
    }

    /**
     * @param dialogBeautified 设置弹窗默认使用美化模式显示
     */
    public void setDialogBeautified(boolean dialogBeautified) {
        state.dialogBeautified = dialogBeautified;
    }

    /**
     * @return 保存的弹窗宽度
     */
    public int getDialogWidth() {
        return state.dialogWidth;
    }

    /**
     * @return 保存的弹窗高度
     */
    public int getDialogHeight() {
        return state.dialogHeight;
    }

    /**
     * @param dialogWidth 设置保存的弹窗宽度
     */
    public void setDialogWidth(int dialogWidth) {
        state.dialogWidth = dialogWidth;
    }

    /**
     * @param dialogHeight 设置保存的弹窗高度
     */
    public void setDialogHeight(int dialogHeight) {
        state.dialogHeight = dialogHeight;
    }

    /**
     * 将持久化状态转换为渲染选项对象.
     *
     * @return SQL 渲染选项
     */
    public SqlRenderOptions toRenderOptions() {
        return new SqlRenderOptions(state.booleanAsOneZero, state.dateTimeFormat);
    }
}
