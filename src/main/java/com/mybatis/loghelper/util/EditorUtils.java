package com.mybatis.loghelper.util;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;

import java.util.Locale;

public final class EditorUtils {
    private EditorUtils() {
    }

    public static boolean isConsoleEditor(Editor editor) {
        if (editor instanceof EditorEx editorEx) {
            try {
                Object kind = editorEx.getClass().getMethod("getEditorKind").invoke(editorEx);
                if (kind != null && "CONSOLE".equals(kind.toString())) {
                    return true;
                }
            } catch (Exception ignored) {
                // 兼容旧版本 API：忽略反射失败
            }
        }
        String editorClass = editor.getClass().getName().toLowerCase(Locale.ROOT);
        return editorClass.contains("console");
    }
}
