package com.budra.uvh.model; // Correct package

// --- Import Scope annotation ---
// Using jakarta.inject.Singleton for standard DI
import jakarta.inject.Singleton;
// OR use jakarta.enterprise.context.ApplicationScoped if using full CDI
// import jakarta.enterprise.context.ApplicationScoped;

// Import your custom exception
import com.budra.uvh.exception.LskGenerationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

// --- ADD Scope Annotation ---
// @Singleton ensures only one instance of this repository is created for the application.
// This is appropriate as the repository itself likely holds no state.
@Singleton
// Alternatively, if using full CDI: @ApplicationScoped
public class LskCounterRepository {
    private static final Logger log = LoggerFactory.getLogger(LskCounterRepository.class);

    // SQL Constants remain the same
    private static final String SELECT_FOR_UPDATE_SQL = "SELECT last_assigned_value FROM LogicalSeedKeyCounters WHERE table_name = ? AND column_name = ? FOR UPDATE";
    private static final String UPDATE_SQL = "UPDATE LogicalSeedKeyCounters SET last_assigned_value = ?, last_updated = CURRENT_TIMESTAMP WHERE table_name = ? AND column_name = ?";
    private static final String INSERT_SQL = "INSERT INTO LogicalSeedKeyCounters (table_name, column_name, last_assigned_value) VALUES (?, ?, ?)";

    // --- Public No-Arg Constructor ---
    // This is required for HK2/CDI to instantiate the Singleton instance automatically,
    // as there is no @Inject annotated constructor.
    public LskCounterRepository() {
        // Updated log message to reflect DI container creation
        log.debug("LskCounterRepository instance created by DI container (@Singleton).");
    }


    /**
     * Atomically retrieves and reserves the next block of LSK values for a given prefix.
     * Uses SELECT FOR UPDATE for locking to ensure atomicity and prevent race conditions.
     * IMPORTANT: This method MUST be called within an active database transaction
     * managed by the calling service layer (e.g., LskResolutionService),
     * with autoCommit set to false.
     *
     * @param conn         The active database connection (with autoCommit=false).
     * @param tableName    The table name part of the logical key prefix.
     * @param columnName   The column name part of the logical key prefix.
     * @param count        The number of sequential values to reserve (must be > 0).
     * @return The starting value of the reserved block (the first generated value).
     * @throws SQLException If any database access fails during the operation.
     * @throws LskGenerationException If the update/insert fails unexpectedly after locking.
     * @throws IllegalArgumentException If count is not positive or names are invalid.
     */
    // Method signature and body remain exactly the same
    public long getAndReserveNextValueBlock(Connection conn, String tableName, String columnName, int count)
            throws SQLException, LskGenerationException, IllegalArgumentException {

        // --- Input Validation ---
        if (count <= 0) {
            log.warn("Invalid count requested for LSK generation: {}", count);
            throw new IllegalArgumentException("Count must be positive.");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            log.warn("Table name is null/empty for Lsk generation.");
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }
        if (columnName == null || columnName.trim().isEmpty()) {
            log.warn("Column name is null/empty for Lsk generation.");
            throw new IllegalArgumentException("Column name cannot be null or empty.");
        }

        long currentMaxValue = -1;
        boolean found = false;

        // --- 1. Query and Lock ---
        // Use try-with-resources for automatic closing of PreparedStatement
        try (PreparedStatement selectStatement = conn.prepareStatement(SELECT_FOR_UPDATE_SQL)) {
            selectStatement.setString(1, tableName);
            selectStatement.setString(2, columnName);
            log.debug("Attempting to lock counter for: {}:{}", tableName, columnName);

            // Use try-with-resources for ResultSet as well
            try (ResultSet rs = selectStatement.executeQuery()) {
                if (rs.next()) {
                    currentMaxValue = rs.getLong("last_assigned_value");
                    found = true;
                    log.debug("Locked existing counter for {}:{}, current max: {}", tableName, columnName, currentMaxValue);
                } else {
                    currentMaxValue = 0; // Start sequence at 1 if not found
                    log.debug("No counter found for {}:{}, will start at 1.", tableName, columnName);
                }
            } // ResultSet closed automatically
        } // PreparedStatement closed automatically; Lock released when transaction commits/rolls back

        // --- 2. Calculate New Range ---
        long nextValue = currentMaxValue + 1;
        long endValue = nextValue + count - 1;

        // --- 3. Update or Insert Counter ---
        int rowsAffected = 0; // Initialize rowsAffected
        if (found) {
            try (PreparedStatement updateStatement = conn.prepareStatement(UPDATE_SQL)) {
                updateStatement.setLong(1, endValue);
                updateStatement.setString(2, tableName);
                updateStatement.setString(3, columnName);
                log.debug("Updating counter for {}:{} to last_assigned_value: {}", tableName, columnName, endValue);
                rowsAffected = updateStatement.executeUpdate();
            } // PreparedStatement closed automatically
        } else {
            try (PreparedStatement insertStatement = conn.prepareStatement(INSERT_SQL)) {
                insertStatement.setString(1, tableName);
                insertStatement.setString(2, columnName);
                insertStatement.setLong(3, endValue);
                log.debug("Inserting new counter for {}:{} with last_assigned_value: {}", tableName, columnName, endValue);
                rowsAffected = insertStatement.executeUpdate();
            } // PreparedStatement closed automatically
        }

        // --- 4. Verification ---
        if (rowsAffected != 1) {
            // Log specific values for better debugging
            log.error("Critical error: Failed to {} counter row for {}:{} after acquiring lock! Rows affected: {}. Transaction will be rolled back.",
                    (found ? "update" : "insert"), tableName, columnName, rowsAffected);
            throw new LskGenerationException(String.format("Failed to %s counter row for %s:%s. Concurrency issue or DB error?",
                    (found ? "update" : "insert"), tableName, columnName));
        }

        log.info("Successfully reserved LSK block [{}-{}] for {}:{}", nextValue, endValue, tableName, columnName);
        return nextValue; // Return STARTING value
    }
}