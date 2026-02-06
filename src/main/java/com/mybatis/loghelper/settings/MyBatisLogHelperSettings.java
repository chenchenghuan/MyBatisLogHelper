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
     * 将持久化状态转换为渲染选项对象.
     *
     * @return SQL 渲染选项
     */
    public SqlRenderOptions toRenderOptions() {
        return new SqlRenderOptions(state.booleanAsOneZero, state.dateTimeFormat);
    }
}
