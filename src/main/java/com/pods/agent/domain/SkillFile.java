package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillFile {
    private String id;
    private String skillId;
    private String filePath;
    private String blobPath;
    private String mimeType;
    private String contentSha256;
    private long sizeBytes;
    private long createdAt;
    private long updatedAt;
}
