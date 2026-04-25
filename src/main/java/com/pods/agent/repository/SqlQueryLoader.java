package com.pods.agent.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Properties;

/**
 * Loads SQL queries from sql.properties at startup.
 * Pattern copied directly from cpq-bcp/SqlQueryLoader.java.
 */
@Component
@Slf4j
public class SqlQueryLoader {

    private final Properties sqlQueries = new Properties();

    public SqlQueryLoader() {
        try (var inputStream = getClass().getResourceAsStream("/sql.properties")) {
            if (inputStream != null) {
                sqlQueries.load(inputStream);
                log.info("[SqlQueryLoader] Loaded {} SQL queries", sqlQueries.size());
            } else {
                log.warn("[SqlQueryLoader] sql.properties not found on classpath — DB operations will fail");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load SQL queries from sql.properties", e);
        }
    }

    public String getQuery(String key) {
        String query = sqlQueries.getProperty(key);
        if (query == null) {
            throw new IllegalArgumentException("SQL query not found for key: " + key);
        }
        return query;
    }
}
