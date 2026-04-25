package com.pods.agent.repository;

import com.pods.agent.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final SqlQueryLoader sqlQueryLoader;

    public UserRepository(JdbcTemplate jdbc,
                          NamedParameterJdbcTemplate namedJdbc,
                          SqlQueryLoader sqlQueryLoader) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.sqlQueryLoader = sqlQueryLoader;
    }

    public User save(User user) {
        String sql = sqlQueryLoader.getQuery("USER.INSERT");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", user.getId())
                .addValue("email", user.getEmail())
                .addValue("passwordHash", user.getPasswordHash())
                .addValue("createdAt", user.getCreatedAt())
                .addValue("updatedAt", user.getUpdatedAt());
        namedJdbc.update(sql, params);
        return user;
    }

    public Optional<User> findByEmail(String email) {
        String sql = sqlQueryLoader.getQuery("USER.FIND_BY_EMAIL");
        List<User> rows = jdbc.query(sql, (rs, n) -> User.builder()
                .id(rs.getString("id"))
                .email(rs.getString("email"))
                .passwordHash(rs.getString("password_hash"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<User> findById(String id) {
        String sql = sqlQueryLoader.getQuery("USER.FIND_BY_ID");
        List<User> rows = jdbc.query(sql, (rs, n) -> User.builder()
                .id(rs.getString("id"))
                .email(rs.getString("email"))
                .passwordHash(rs.getString("password_hash"))
                .createdAt(rs.getLong("created_at"))
                .updatedAt(rs.getLong("updated_at"))
                .build(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
