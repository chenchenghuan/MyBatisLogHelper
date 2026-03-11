package com.mybatis.loghelper.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.mybatis.loghelper.settings.MyBatisLogHelperSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 工具面板 SQL 历史记录（仅内存，上限可配置）。
 */
@Service(Service.Level.APP)
public final class SqlToolWindowHistoryService {
    private final Deque<SqlHistoryEntry> entries = new ArrayDeque<>();

    public static SqlToolWindowHistoryService getInstance() {
        return ApplicationManager.getApplication().getService(SqlToolWindowHistoryService.class);
    }

    public synchronized void add(String sql) {
        add(sql, null, null);
    }

    public synchronized void add(String sql, String preparingRaw, String parametersRaw) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        // 工具窗口记录同时保存原始 Preparing/Parameters，便于预览原文
        entries.addFirst(new SqlHistoryEntry(sql, System.currentTimeMillis(), preparingRaw, parametersRaw));
        // 按配置上限裁剪，保证内存队列不会无限增长
        trimToLimit(resolveMaxEntries());
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized List<SqlHistoryEntry> list() {
        // 每次读取时都按最新配置裁剪，便于动态调整上限
        trimToLimit(resolveMaxEntries());
        // 按时间倒序返回（从晚到早）
        List<SqlHistoryEntry> result = new ArrayList<>(entries);
        result.sort(Comparator.comparingLong(SqlHistoryEntry::timestampMillis).reversed());
        return result;
    }

    /**
     * 获取当前配置的历史记录上限.
     *
     * @return 上限条数
     */
    private int resolveMaxEntries() {
        return MyBatisLogHelperSettings.getInstance().getToolWindowHistoryLimit();
    }

    /**
     * 按上限裁剪队列，移除最老的数据.
     *
     * @param limit 上限条数
     */
    private void trimToLimit(int limit) {
        while (entries.size() > limit) {
            entries.removeLast();
        }
    }
}
