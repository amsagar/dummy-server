package com.pods.agent.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Memory {
    private String id;
    private String userId;
    private String sessionId;
    private String category;
    private String memoryFilePath;
    private String content;
    private List<String> tags;
    private long createdAt;
    private long updatedAt;
}
