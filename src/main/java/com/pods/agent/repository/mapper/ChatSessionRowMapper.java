package com.pods.agent.repository.mapper;

import com.pods.agent.domain.ChatSession;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ChatSessionRowMapper implements RowMapper<ChatSession> {

    @Override
    public ChatSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        return ChatSession.builder()
                .sessionId(rs.getString("session_id"))
                .userId(rs.getString("user_id"))
                .createdAt(rs.getLong("created_at"))
                .lastActive(rs.getLong("last_active"))
                .timezone(rs.getString("timezone"))
                .title(rs.getString("title"))
                .archivedAt(rs.getObject("archived_at", Long.class))
                .build();
    }
}
