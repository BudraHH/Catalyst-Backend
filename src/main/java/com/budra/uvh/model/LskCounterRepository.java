package com.budra.uvh.model;

import com.budra.uvh.exception.LskGenerationException;
import jakarta.inject.Singleton; // Standard Jakarta DI annotation for singleton scope
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Repository responsible for managing persistent counters for Logical Seed Keys (LSKs).
 * Provides atomic operations to retrieve and reserve blocks of sequence values.
 *
 * IMPORTANT: Methods require an active database transaction (autoCommit=false)
 * managed by the calling service layer to ensure atomicity via row locking.
 */
@Service
@Singleton // This repository is stateless and suitable for a Singleton scope
public class LskCounterRepository {
    private static final Logger log = LoggerFactory.getLogger(LskCounterRepository.class);

    // Assumed table and column names - consider making configurable if needed
    private static final String COUNTER_TABLE = "LogicalSeedKeyCounters";
    private static final String TABLE_NAME_COL = "table_name";
    private static final String COLUMN_NAME_COL = "column_name";
    private static final String VALUE_COL = "last_assigned_value";
    private static final String UPDATED_TS_COL = "last_updated"; // Optional

    // SQL templates using assumed names
    // SELECT FOR UPDATE locks the specific row(s) until the transaction ends
    private static final String SELECT_FOR_UPDATE_SQL = String.format(
            "SELECT %s FROM %s WHERE %s = ? AND %s = ? FOR UPDATE",
            VALUE_COL, COUNTER_TABLE, TABLE_NAME_COL, COLUMN_NAME_COL);

    private static final String UPDATE_SQL = String.format(
            "UPDATE %s SET %s = ?, %s = CURRENT_TIMESTAMP WHERE %s = ? AND %s = ?", // Assumes CURRENT_TIMESTAMP function
            COUNTER_TABLE, VALUE_COL, UPDATED_TS_COL, TABLE_NAME_COL, COLUMN_NAME_COL);

    private static final String INSERT_SQL = String.format(
            "INSERT INTO %s (%s, %s, %s, %s) VALUES (?, ?, ?, CURRENT_TIMESTAMP)", // Assumes CURRENT_TIMESTAMP function
            COUNTER_TABLE, TABLE_NAME_COL, COLUMN_NAME_COL, VALUE_COL, UPDATED_TS_COL);

    /**
     * Public constructor required for DI frameworks to instantiate the Singleton.
     */
    public LskCounterRepository() {
        log.debug("LskCounterRepository instance created (@Singleton).");
    }

    /**
     * Atomically retrieves and reserves the next block of LSK values for a given key.
     * Uses SELECT FOR UPDATE for locking. Must be called within an active transaction.
     *
     * @param conn         The active database connection (with autoCommit=false).
     * @param tableName    The table name part of the logical key.
     * @param columnName   The column name part of the logical key.
     * @param count        The number of sequential values to reserve (must be > 0).
     * @return The starting value of the reserved block (the first generated value).
     * @throws SQLException If any database access fails during the operation.
     * @throws LskGenerationException If the update/insert fails unexpectedly after locking,
     *         or if validation fails.
     * @throws IllegalArgumentException If count is not positive or names are invalid (can be
     *         wrapped in LskGenerationException by caller).
     */
    public long getAndReserveNextValueBlock(Connection conn, String tableName, String columnName, int count)
            throws SQLException, LskGenerationException, IllegalArgumentException {

        // --- Input Validation ---
        if (count <= 0) {
            log.warn("Invalid count requested for LSK generation: {}", count);
            // Throwing IllegalArgumentException allows caller (service) to decide if it's a 400 or 500 error
            throw new IllegalArgumentException("Count must be positive.");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            log.warn("Table name is null/empty for LSK generation.");
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }
        if (columnName == null || columnName.trim().isEmpty()) {
            log.warn("Column name is null/empty for LSK generation.");
            throw new IllegalArgumentException("Column name cannot be null or empty.");
        }
        // Consider adding length checks or character validation if needed

        long currentMaxValue = -1;
        boolean found = false;

        // --- 1. Query and Lock (Row Level) ---
        // Use try-with-resources for automatic closing of PreparedStatement and ResultSet
        log.debug("Attempting to lock counter row for key [{}:{}]", tableName, columnName);
        try (PreparedStatement selectStatement = conn.prepareStatement(SELECT_FOR_UPDATE_SQL)) {
            selectStatement.setString(1, tableName);
            selectStatement.setString(2, columnName);

            try (ResultSet rs = selectStatement.executeQuery()) {
                if (rs.next()) {
                    currentMaxValue = rs.getLong(1); // Get by index (or VALUE_COL name)
                    found = true;
                    log.debug("Locked existing counter for key [{}:{}], current max value: {}", tableName, columnName, currentMaxValue);
                } else {
                    // Counter doesn't exist yet for this combination
                    currentMaxValue = 0; // Initialize: The first value generated will be 1
                    found = false;
                    log.debug("No counter found for key [{}:{}], will initialize.", tableName, columnName);
                }
            } // ResultSet closed
        } // PreparedStatement closed; Lock held by transaction

        // --- 2. Calculate New Range ---
        long nextValue = currentMaxValue + 1; // The first value in the new block
        long newMaxValue = currentMaxValue + count; // The new value for last_assigned_value

        // --- 3. Update or Insert Counter ---
        int rowsAffected = 0;
        if (found) {
            // Update existing counter
            log.debug("Updating counter for key [{}:{}] to new max value: {}", tableName, columnName, newMaxValue);
            try (PreparedStatement updateStatement = conn.prepareStatement(UPDATE_SQL)) {
                updateStatement.setLong(1, newMaxValue);        // New last_assigned_value
                updateStatement.setString(2, tableName);    // WHERE table_name = ?
                updateStatement.setString(3, columnName);   // AND column_name = ?
                rowsAffected = updateStatement.executeUpdate();
            } // PreparedStatement closed
        } else {
            // Insert new counter
            log.debug("Inserting new counter for key [{}:{}] with initial max value: {}", tableName, columnName, newMaxValue);
            try (PreparedStatement insertStatement = conn.prepareStatement(INSERT_SQL)) {
                insertStatement.setString(1, tableName);    // table_name
                insertStatement.setString(2, columnName);   // column_name
                insertStatement.setLong(3, newMaxValue);        // last_assigned_value
                rowsAffected = insertStatement.executeUpdate();
            } // PreparedStatement closed
        }

        // --- 4. Verification ---
        if (rowsAffected != 1) {
            // This indicates a serious problem, as the row should have been locked.
            // Could be an unexpected concurrent DDL change, transaction isolation issue, or DB bug.
            String action = found ? "update" : "insert";
            log.error("CRITICAL: Failed to {} counter row for key [{}:{}] after acquiring lock! Rows affected: {}. Transaction integrity compromised.",
                    action, tableName, columnName, rowsAffected);
            // Throw a specific exception indicating potential data inconsistency
            throw new LskGenerationException(String.format(
                    "Database inconsistency: Failed to %s counter row for %s:%s after lock. Rows affected: %d",
                    action, tableName, columnName, rowsAffected));
        }

        log.info("Successfully reserved LSK block starting at {} (new max value: {}) for key [{}:{}]",
                nextValue, newMaxValue, tableName, columnName);

        // Return the STARTING value of the reserved block
        return nextValue;
    }
}