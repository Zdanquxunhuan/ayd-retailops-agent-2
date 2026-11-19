package com.lbaby.orders;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class CaseEvidence {
    record Item(UUID id, UUID caseId, String source, String kind, String requestRef, Instant eventTime,
                Instant collectedAt, String excerpt) {}

    private final JdbcTemplate jdbc;

    CaseEvidence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Item> timeline(UUID caseId) {
        return jdbc.query("""
                SELECT * FROM evidence WHERE case_id=?
                ORDER BY COALESCE(event_time,collected_at), collected_at, id
                """, this::map, caseId);
    }

    Item read(UUID caseId, UUID id) {
        return jdbc.query("SELECT * FROM evidence WHERE case_id=? AND id=?", this::map, caseId, id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    List<Item> snapshots(UUID caseId, String reference) {
        return jdbc.query("""
                SELECT * FROM evidence WHERE case_id=? AND kind='SNAPSHOT' AND request_ref=?
                ORDER BY collected_at, source DESC
                """, this::map, caseId, reference);
    }

    Item save(UUID caseId, String sourceKey, String source, String kind, String ref,
              Instant eventTime, Instant collectedAt, String excerpt) {
        UUID id = UUID.nameUUIDFromBytes((caseId + "/" + source + "/" + sourceKey).getBytes(StandardCharsets.UTF_8));
        try {
            jdbc.update("""
                    INSERT INTO evidence(id,case_id,source,kind,request_ref,event_time,collected_at,excerpt)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, id, caseId, source, kind, ref, eventTime, collectedAt, excerpt);
        } catch (DuplicateKeyException existing) {
            // Repeated investigation retains the original collection timestamp and snapshot.
        }
        return read(caseId, id);
    }

    private Item map(ResultSet row, int index) throws SQLException {
        var time = row.getTimestamp("event_time");
        return new Item(row.getObject("id", UUID.class), row.getObject("case_id", UUID.class),
                row.getString("source"), row.getString("kind"), row.getString("request_ref"),
                time == null ? null : time.toInstant(), row.getTimestamp("collected_at").toInstant(),
                row.getString("excerpt"));
    }
}
