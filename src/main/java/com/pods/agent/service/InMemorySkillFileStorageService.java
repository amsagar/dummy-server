package com.pods.agent.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySkillFileStorageService implements SkillFileStorageService {
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public void put(String blobPath, byte[] content, String mimeType) {
        store.put(blobPath, content);
    }

    @Override
    public byte[] get(String blobPath) {
        return store.getOrDefault(blobPath, new byte[0]);
    }

    @Override
    public void delete(String blobPath) {
        store.remove(blobPath);
    }

    @Override
    public void deletePrefix(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
