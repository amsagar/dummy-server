package com.pods.agent.service;

import java.util.concurrent.Callable;

public final class UserContextHolder {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static String currentUserId() {
        return USER_ID.get();
    }

    public static <T> T withUser(String userId, Callable<T> callable) {
        String previous = USER_ID.get();
        USER_ID.set(userId);
        try {
            return callable.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (previous == null) USER_ID.remove();
            else USER_ID.set(previous);
        }
    }
}
