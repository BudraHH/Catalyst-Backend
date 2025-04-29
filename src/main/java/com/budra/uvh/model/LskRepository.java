package com.budra.uvh.model;

import com.budra.uvh.exception.LskGenerationException; // Although not thrown here, keep for consistency if desired
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Objects;

public class LskRepository {
    private static final Logger log = LoggerFactory.getLogger(LskRepository.class);

    // --- UPDATED SQL: Include module_name in WHERE clause ---
    private static final String SELECT_MAX_VALUE_SQL =
            "SELECT MAX(end_value) FROM LskResolutionLog WHERE table_name = ? AND column_name = ? AND module_name = ?"; // Added module_name filter

    // --- UPDATED SQL: Advisory lock keys should distinguish by module ---
    // Using a combined hash for the second key to fit into the 2-integer function
    private static final String ACQUIRE_ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    public LskRepository() {
        log.debug("LskRepository instance created (for next value lookup using LskResolutionLog).");
    }

    public long getNextStartingValue(Connection conn, String tableName, String columnName, String moduleName)
            throws SQLException, IllegalArgumentException {

        if (tableName == null || tableName.trim().isEmpty()) throw new IllegalArgumentException("Table name cannot be null or empty.");
        if (columnName == null || columnName.trim().isEmpty()) throw new IllegalArgumentException("Column name cannot be null or empty.");
        if (moduleName == null || moduleName.trim().isEmpty()) throw new IllegalArgumentException("Module name cannot be null or empty."); // <<< ADDED check

        long currentMaxValue = 0; // Start sequence at 1 if no previous logs exist for this combo


        int lockKey1 = Objects.hash("LSK_NEXT_VAL_LOCK_V2", tableName);
        int lockKey2 = Objects.hash(columnName, moduleName);

        log.debug("Attempting to acquire advisory lock for next value lookup key ({}, {}) [Tbl: {}, Col: {}, Mod: {}]",
                lockKey1, lockKey2, tableName, columnName, moduleName);
        try (PreparedStatement lockStatement = conn.prepareStatement(ACQUIRE_ADVISORY_LOCK_SQL)) {
            lockStatement.setInt(1, lockKey1);
            lockStatement.setInt(2, lockKey2);
            lockStatement.execute(); // Acquire lock (blocks if held)
            log.debug("Advisory lock acquired for next value lookup key ({}, {})", lockKey1, lockKey2);
        } catch (SQLException e) {
            log.error("Failed to acquire advisory lock for LSK key ({}, {}): {}", lockKey1, lockKey2, e.getMessage());
            throw e;
        }

        try (PreparedStatement selectStatement = conn.prepareStatement(SELECT_MAX_VALUE_SQL)) {
            selectStatement.setString(1, tableName);
            selectStatement.setString(2, columnName);
            selectStatement.setString(3, moduleName);
            log.debug("Querying MAX(end_value) from LskResolutionLog for {}:{}:{} under lock", tableName, columnName, moduleName); // Update log

            ResultSet rs = selectStatement.executeQuery();
            if (rs.next()) {
                long maxVal = rs.getLong(1);
                if (!rs.wasNull()) { // Check if MAX() found a non-NULL value
                    currentMaxValue = maxVal;
                }
                // else currentMaxValue remains 0
            }
            log.debug("Current MAX(end_value) found for {}:{}:{} is {}", tableName, columnName, moduleName, currentMaxValue); // Update log

        } catch (SQLException e) {
            log.error("SQL Exception querying MAX(end_value) for {}:{}:{}: {}", tableName, columnName, moduleName, e.getMessage()); // Update log
            throw e; // Propagate error, transaction will rollback
        }

        // --- Calculate and Return Next Value ---
        // Lock is released automatically when the transaction commits/rolls back by the caller.
        long nextValue = currentMaxValue + 1;
        log.debug("Calculated next starting value for {}:{}:{} as {}", tableName, columnName, moduleName, nextValue); // Update log
        return nextValue;
    }
}