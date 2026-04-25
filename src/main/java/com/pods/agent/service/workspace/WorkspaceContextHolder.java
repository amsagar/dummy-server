package com.pods.agent.service.workspace;

import java.nio.file.Path;
import java.util.function.Supplier;

public final class WorkspaceContextHolder {
    private static final ThreadLocal<Path> WORKSPACE = new ThreadLocal<>();

    private WorkspaceContextHolder() {}

    public static Path current() {
        return WORKSPACE.get();
    }

    public static void set(Path path) {
        WORKSPACE.set(path);
    }

    public static void clear() {
        WORKSPACE.remove();
    }

    public static <T> T withWorkspace(Path path, Supplier<T> supplier) {
        Path previous = WORKSPACE.get();
        WORKSPACE.set(path);
        try {
            return supplier.get();
        } finally {
            if (previous == null) WORKSPACE.remove();
            else WORKSPACE.set(previous);
        }
    }

    public static void withWorkspace(Path path, Runnable runnable) {
        withWorkspace(path, () -> {
            runnable.run();
            return null;
        });
    }
}

