package com.payverse.recon.repository;

import com.payverse.recon.model.FeedRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Staging table for one run's two sides. */
@Repository
public class ReconRecordStore {

    /** Both sides per reference; the compare step reads this with a cursor. */
    public static final String PAIRS_SQL = """
            SELECT COALESCE(l.reference, r.reference) AS reference,
                   l.amount AS left_amount, l.in_window AS left_in_window,
                   r.amount AS right_amount, r.in_window AS right_in_window
            FROM (SELECT * FROM recon_records WHERE run_id = ? AND side = 'LEFT') l
            FULL OUTER JOIN (SELECT * FROM recon_records WHERE run_id = ? AND side = 'RIGHT') r ON l.reference = r.reference
            ORDER BY 1
            """;

    private final JdbcTemplate jdbc;

    public ReconRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Duplicate references on one side keep the first record. */
    public int insert(UUID runId, String side, List<FeedRecord> records, Instant windowFrom, Instant windowTo) {
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO recon_records (run_id, side, reference, amount, occurred_at, in_window) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, records, 500, (ps, r) -> {
            ps.setObject(1, runId);
            ps.setString(2, side);
            ps.setString(3, r.reference());
            ps.setBigDecimal(4, r.amount());
            ps.setTimestamp(5, Timestamp.from(r.occurredAt()));
            ps.setBoolean(6, !r.occurredAt().isBefore(windowFrom) && r.occurredAt().isBefore(windowTo));
        });
        int inserted = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                inserted += Math.max(c, 0);
            }
        }
        return inserted;
    }

    public int count(UUID runId, String side, boolean inWindowOnly) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM recon_records WHERE run_id = ? AND side = ?" + (inWindowOnly ? " AND in_window" : ""),
                Integer.class, runId, side);
        return n == null ? 0 : n;
    }

    public int matched(UUID runId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM recon_records l JOIN recon_records r ON r.run_id = l.run_id AND r.side = 'RIGHT' AND r.reference = l.reference
                WHERE l.run_id = ? AND l.side = 'LEFT' AND l.amount = r.amount
                """, Integer.class, runId);
        return n == null ? 0 : n;
    }
}
