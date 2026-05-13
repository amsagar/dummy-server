package com.pods.agent.vendorRationalization;

import com.pods.agent.repository.SqlQueryLoader;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Singleton-row store for the vendor-rationalization tunable config (lever
 * assignments, savings buckets, KPI targets, insight thresholds, insight
 * templates, Pareto threshold). The entire config is serialized as JSON
 * into one column so the Settings admin page can read/write it as a unit.
 *
 * <p>Created lazily by {@link VendorRationalizationConfigSeeder} on first
 * boot. Subsequent restarts never overwrite — business edits must survive.
 */
@Repository
public class VendorRationalizationConfigRepository {

    public record Row(String payloadJson, long updatedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlQueryLoader sql;

    public VendorRationalizationConfigRepository(NamedParameterJdbcTemplate jdbc, SqlQueryLoader sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public Optional<Row> find() {
        return jdbc.query(sql.getQuery("VR_CONFIG.SELECT"), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new Row(rs.getString("payload_json"), rs.getLong("updated_at")));
        });
    }

    public Row upsert(String payloadJson) {
        long now = Instant.now().toEpochMilli();
        jdbc.update(sql.getQuery("VR_CONFIG.UPSERT"),
                new MapSqlParameterSource()
                        .addValue("payloadJson", payloadJson)
                        .addValue("updatedAt", now));
        return new Row(payloadJson, now);
    }

    /** Used by the seeder — never overwrites an existing row. */
    public void insertIfAbsent(String payloadJson) {
        long now = Instant.now().toEpochMilli();
        jdbc.update(sql.getQuery("VR_CONFIG.INSERT_IF_ABSENT"),
                new MapSqlParameterSource()
                        .addValue("payloadJson", payloadJson)
                        .addValue("updatedAt", now));
    }
}
