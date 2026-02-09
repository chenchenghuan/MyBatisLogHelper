package com.mybatis.loghelper.history;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlHistoryServiceTest {
    @Test
    void shouldKeepLatestTwentyEntries() {
        SqlHistoryService service = new SqlHistoryService();
        for (int i = 0; i < 25; i++) {
            service.add("sql-" + i);
        }

        List<SqlHistoryEntry> entries = service.list();
        assertEquals(20, entries.size());
        assertEquals("sql-24", entries.get(0).sql());
        assertEquals("sql-5", entries.get(19).sql());
    }
}
