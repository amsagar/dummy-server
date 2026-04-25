package com.pods.agent.agent;

import com.pods.agent.api.dto.ChatState;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * In-memory session state for a single chat conversation.
 */
@Getter
@Setter
public class AgentSession {

    private final String sessionId;
    private final List<Message> messages = new ArrayList<>();

    /** Active SSE emitter for the current streaming request */
    private volatile SseEmitter activeEmitter;

    private ChatState activeState = new ChatState();

    private long createdAt;
    private long lastActiveAt;
    private Path workspacePath;

    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = System.currentTimeMillis();
    }

    public void touch() {
        this.lastActiveAt = System.currentTimeMillis();
    }
}
