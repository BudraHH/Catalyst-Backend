package com.budra.uvh.model;

import com.budra.uvh.exception.LskGenerationException; // Keep if needed for consistency
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Objects;

/**
 * Repository responsible ONLY for atomically determining the NEXT available LSK starting value
 * based on the LskResolutionLog table, using advisory locks for concurrency control.
 * It does NOT insert log records.
 */
public class LskRepository {
    private static final Logger log = LoggerFactory.getLogger(LskRepository.class);

    // SQL to find the current max end_value for a specific table/column in the LOG table
    private static final String SELECT_MAX_VALUE_SQL =
            "SELECT MAX(end_value) FROM LskResolutionLog WHERE table_name = ? AND column_name = ?";

    // SQL for PostgreSQL Advisory Lock (Transaction-scoped)
    private static final String ACQUIRE_ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";
    // Advisory lock ensures atomic read for MAX() calculation

    public LskRepository() {
        log.debug("LskRepository instance created (for next value lookup using LskResolutionLog).");
    }

    /**
     * Atomically determines the NEXT available starting LSK value based on LskResolutionLog,
     * using an advisory lock for concurrency control. Does NOT reserve or log.
     * MUST be called within an active transaction managed by the caller.
     * The lock is acquired BEFORE querying MAX() to ensure atomicity of the lookup.
     *
     * @param conn         The active database connection (autoCommit=false).
     * @param tableName    Target table name.
     * @param columnName   Target column name.
     * @return The next available starting value based on the current maximum in the log.
     * @throws SQLException If database access or locking fails.
     * @throws IllegalArgumentException If names are invalid.
     */
    public long getNextStartingValue(Connection conn, String tableName, String columnName)
            throws SQLException, IllegalArgumentException {

        // --- Input Validation ---
        if (tableName == null || tableName.trim().isEmpty()) throw new IllegalArgumentException("Table name cannot be null or empty.");
        if (columnName == null || columnName.trim().isEmpty()) throw new IllegalArgumentException("Column name cannot be null or empty.");

        long currentMaxValue = 0; // Start at 1 if no previous logs exist

        // --- Acquire Advisory Lock ---
        // Lock based only on table_name and column_name
        int lockKey1 = Objects.hash("LSK_NEXT_VAL_LOCK", tableName);
        int lockKey2 = columnName.hashCode();

        log.debug("Attempting to acquire advisory lock for next value lookup key ({}, {}) [Table: {}, Col: {}]",
                lockKey1, lockKey2, tableName, columnName);
        try (PreparedStatement lockStatement = conn.prepareStatement(ACQUIRE_ADVISORY_LOCK_SQL)) {
            lockStatement.setInt(1, lockKey1);
            lockStatement.setInt(2, lockKey2);
            lockStatement.execute(); // Acquire lock
            log.debug("Advisory lock acquired for next value lookup key ({}, {})", lockKey1, lockKey2);
        } catch (SQLException e) {
            log.error("Failed to acquire advisory lock for LSK key ({}, {}): {}", lockKey1, lockKey2, e.getMessage());
            throw e; // Propagate error, transaction should rollback
        }
        // --- Lock is now held for the remainder of this transaction ---

        // --- Query Max Existing Value from Log Table (Under Lock) ---
        try (PreparedStatement selectStatement = conn.prepareStatement(SELECT_MAX_VALUE_SQL)) {
            selectStatement.setString(1, tableName);
            selectStatement.setString(2, columnName);
            log.debug("Querying MAX(end_value) from LskResolutionLog for {}:{} under lock", tableName, columnName);

            ResultSet rs = selectStatement.executeQuery();
            if (rs.next()) {
                long maxVal = rs.getLong(1);
                if (!rs.wasNull()) { // Check if MAX() found a non-NULL value
                    currentMaxValue = maxVal;
                }
            }
            log.debug("Current MAX(end_value) found for {}:{} is {}", tableName, columnName, currentMaxValue);

        } catch (SQLException e) {
            log.error("SQL Exception querying MAX(end_value) for {}:{}: {}", tableName, columnName, e.getMessage());
            throw e; // Propagate error, transaction will rollback
        }

        // --- Calculate and Return Next Value ---
        // Lock is released automatically when the transaction commits/rolls back.
        long nextValue = currentMaxValue + 1;
        log.debug("Calculated next starting value for {}:{} as {}", tableName, columnName, nextValue);
        return nextValue;
    }
}