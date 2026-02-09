package com.mybatis.loghelper.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * SQL 历史记录（仅内存）。
 */
@Service(Service.Level.APP)
public final class SqlHistoryService {
    private static final int MAX_ENTRIES = 20;
    private final Deque<SqlHistoryEntry> entries = new ArrayDeque<>();

    public static SqlHistoryService getInstance() {
        return ApplicationManager.getApplication().getService(SqlHistoryService.class);
    }

    public synchronized void add(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        // 结果弹窗历史不需要保存原始 Preparing/Parameters
        entries.addFirst(new SqlHistoryEntry(sql, System.currentTimeMillis(), null, null));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public synchronized List<SqlHistoryEntry> list() {
        // 按时间顺序返回（从早到晚）
        List<SqlHistoryEntry> result = new ArrayList<>(entries);
        result.sort(Comparator.comparingLong(SqlHistoryEntry::timestampMillis));
        return result;
    }
}
