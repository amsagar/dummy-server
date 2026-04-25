package com.pods.agent.service;

public interface SkillFileStorageService {
    void put(String blobPath, byte[] content, String mimeType);
    byte[] get(String blobPath);
    void delete(String blobPath);
    /** Delete every blob whose path starts with the given prefix (e.g. "skills/{id}/"). */
    void deletePrefix(String prefix);
}
