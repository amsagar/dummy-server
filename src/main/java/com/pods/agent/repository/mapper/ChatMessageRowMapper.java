package com.pods.agent.repository.mapper;

import com.pods.agent.domain.ChatMessage;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class ChatMessageRowMapper implements RowMapper<ChatMessage> {

    @Override
    public ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        return ChatMessage.builder()
                .id(rs.getString("id"))
                .sessionId(rs.getString("session_id"))
                .role(rs.getString("role"))
                .content(rs.getString("content"))
                .createdAt(rs.getLong("created_at"))
                .build();
    }
}
