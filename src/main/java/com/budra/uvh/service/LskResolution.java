package com.budra.uvh.service;

import com.budra.uvh.config.ConnectionManager;
import com.budra.uvh.model.LskRepository; // Use the correct repository
import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.ReferenceResolutionException;
import com.budra.uvh.utils.XmlUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement; // Needed for logging insert
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;          // Needed for equals/hashCode in RangeKey class
import java.util.Set;


public class LskResolution {
    private static final Logger log = LoggerFactory.getLogger(LskResolution.class);
    private final LskRepository lskRepository; // Use the correct repository

    // SQL statement to insert the consolidated log entry for a processed range
    private static final String INSERT_LOG_SQL =
            "INSERT INTO LskResolutionLog (dev_email, table_name, column_name, module_name, start_value, end_value) VALUES (?, ?, ?, ?, ?, ?)";

    // Constructor uses the correct repository type
    public LskResolution(LskRepository lskRepository) {
        log.debug("LskResolution instance created with LskRepository.");
        if (lskRepository == null) {
            throw new IllegalArgumentException("LskRepository cannot be null");
        }
        this.lskRepository = lskRepository;
    }

    private static final class RangeKey {
        private final String tableName;
        private final String columnName;
        private final String moduleName;

        public RangeKey(String tableName, String columnName, String moduleName) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.moduleName = moduleName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RangeKey rangeKey = (RangeKey) o;
            return Objects.equals(tableName, rangeKey.tableName) &&
                    Objects.equals(columnName, rangeKey.columnName) &&
                    Objects.equals(moduleName, rangeKey.moduleName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tableName, columnName, moduleName);
        }

        // Optional: toString for better debugging
        @Override
        public String toString() {
            return "RangeKey{" +
                    "tableName='" + tableName + '\'' +
                    ", columnName='" + columnName + '\'' +
                    ", moduleName='" + moduleName + '\'' +
                    '}';
        }
    }
    private static class RangeInfo {
        long firstValue;
        long lastValue;
        String tableName;
        String columnName;
        String moduleName;

        RangeInfo(long initialValue, String table, String column, String module) {
            this.firstValue = initialValue;
            this.lastValue = initialValue;
            this.tableName = table;
            this.columnName = column;
            this.moduleName = module;
        }

        public void setLastValue(long lastValue) { this.lastValue = lastValue; }

        public long getFirstValue() { return firstValue; }
        public long getLastValue() { return lastValue; }
        public String getTableName() { return tableName; }
        public String getColumnName() { return columnName; }
        public String getModuleName() { return moduleName; }
    }


    public String processAndResolveXml(String inputXml, String devEmail)
            throws PlaceholderFormatException, LskGenerationException, ReferenceResolutionException, SQLException {

        log.info("Starting LSK/Reference resolution process for user: {}", devEmail);

        // --- Input Validation ---
        if (this.lskRepository == null || devEmail == null || devEmail.trim().isEmpty() || inputXml == null || inputXml.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository, DevEmail, and InputXML must not be null or empty.");
        }

        // --- Find Placeholders ---
        Set<String> uniquePkPlaceholders = XmlUtils.findUniquePkPlaceholders(inputXml);
        Map<String, String> fkReferences = XmlUtils.findFkReferences(inputXml);
        if (uniquePkPlaceholders.isEmpty() && fkReferences.isEmpty()) {
            log.info("No LSK placeholders or FK references found for user {}. Returning original XML.", devEmail);
            return inputXml;
        }
        log.info("Found {} unique PK placeholders and {} unique FK references for user {}",
                uniquePkPlaceholders.size(), fkReferences.size(), devEmail);

        // --- Resolve Primary Keys ---
        Map<String, String> pkResolutionMap = new HashMap<>(); // For XML replacement map
        Map<RangeKey, RangeInfo> rangesProcessed = new HashMap<>(); // For consolidated logging map

        Connection connection = null;
        boolean transactionSuccess = false;

        if (!uniquePkPlaceholders.isEmpty()) {
            try {
                connection = ConnectionManager.getConnection();
                connection.setAutoCommit(false);
                log.debug("DB transaction started for LSK generation.");

                // Tracks the *next* sequential value to assign for a given prefix *within this transaction*.
                Map<RangeKey, Long> nextValueTracker = new HashMap<>();

                // Loop through every unique PK placeholder string found in the XML
                for (String pkPlaceholder : uniquePkPlaceholders) {
                    String tableName;
                    String columnName;
                    String moduleName;

                    // Parse "Table:Column:LogicalId" format
                    try {
                        String[] parts = pkPlaceholder.split(":", 4);
                        if (parts.length < 4 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                            throw new PlaceholderFormatException("Invalid PK format: " + pkPlaceholder);
                        }
                        tableName = parts[0];
                        columnName = parts[1];
                        moduleName = parts[2];
                    } catch (Exception e) {
                        throw new PlaceholderFormatException("Error parsing PK placeholder: " + pkPlaceholder, e);
                    }

                    // Use the RangeKey class (instead of record)
                    RangeKey currentKey = new RangeKey(tableName, columnName, moduleName);
                    long valueToAssign; // The specific LSK value for *this* placeholder instance

                    // Determine the next value for this table/column prefix
                    if (nextValueTracker.containsKey(currentKey)) {
                        // Use the next value already tracked for this prefix within this transaction
                        valueToAssign = nextValueTracker.get(currentKey);
                        log.trace("Using tracked next value {} for {}:{}:{}", valueToAssign, tableName, columnName, moduleName);
                    } else {
                        // First time for this prefix in this transaction: query the DB for the starting point
                        log.debug("Requesting initial starting value via LskRepository for {}:{}:{} (User: {})", tableName, columnName, moduleName, devEmail);
                        valueToAssign = this.lskRepository.getNextStartingValue(connection, tableName, columnName,moduleName);
                        log.trace("Received initial starting value {} for {}:{}", valueToAssign, tableName, columnName);
                    }

                    // Track the range details (first/last values) for consolidated logging
                    if (!rangesProcessed.containsKey(currentKey)) {
                        // First time using this prefix in this request. Create a new RangeInfo object.
                        rangesProcessed.put(currentKey, new RangeInfo(valueToAssign, tableName, columnName, moduleName));
                        log.trace("Tracking new range for {}:{}, starting at {}", tableName, columnName, valueToAssign);
                    } else {
                        // Already tracking this prefix. Update the 'lastValue' for this range.
                        rangesProcessed.get(currentKey).setLastValue(valueToAssign);
                        log.trace("Updating range for {}:{}, last value now {}", tableName, columnName, valueToAssign);
                    }

                    // Increment the tracker for the *next* time this loop encounters the same prefix
                    nextValueTracker.put(currentKey, valueToAssign + 1);

                    // Prepare map for final XML replacement
                    String resolvedLsk = tableName + ":" + columnName + ":" + valueToAssign;
                    pkResolutionMap.put(pkPlaceholder, resolvedLsk);
                    log.debug("Resolved PK placeholder '{}' -> Resolved LSK '{}' (Value assigned: {})", pkPlaceholder, resolvedLsk, valueToAssign);

                } // End placeholder loop

                // --- Stage 2.5: Log Consolidated Ranges ---
                log.info("Logging consolidated ranges to LskResolutionLog for user {}...", devEmail);
                for (RangeInfo range : rangesProcessed.values()) {
                    logConsolidatedResolution(connection, devEmail, range.getTableName(), range.getColumnName(), range.getModuleName(), range.getFirstValue(), range.getLastValue());
                }
                log.info("Finished logging consolidated ranges.");

                // --- Commit Transaction ---
                connection.commit();
                transactionSuccess = true;
                log.debug("DB transaction committed successfully (incl. consolidated logs).");

            } catch (SQLException | LskGenerationException | PlaceholderFormatException | IllegalArgumentException e) {
                // Centralized error handling
                log.error("Error during LSK resolution transaction for user {}: {}", devEmail, e.getMessage(), e);
                if (e instanceof SQLException || e instanceof IllegalArgumentException) {
                    throw new LskGenerationException("Error during LSK processing: " + e.getMessage(), e);
                } else {
                    throw e;
                }
            } finally {
                // Transaction Management
                if (connection != null) {
                    try { if (!transactionSuccess) connection.rollback(); } catch (SQLException ex) { log.error("Rollback failed", ex); } finally { try { connection.setAutoCommit(true); connection.close(); log.debug("DB connection closed."); } catch (SQLException e) { log.error("Conn close failed", e); } }
                }
            }
        } else {
            log.info("No PK placeholders found to resolve for user {}.", devEmail);
        }

        // --- Resolve Foreign Keys ---
        Map<String, String> finalReplacements = new HashMap<>(pkResolutionMap);
        if (!fkReferences.isEmpty()) {
            log.debug("Resolving {} FK references for user {}...", fkReferences.size(), devEmail);
            for (Map.Entry<String, String> fkEntry : fkReferences.entrySet()) {
                String fullRefString = fkEntry.getKey(); String targetPkPlaceholder = fkEntry.getValue();
                String resolvedTargetLsk = pkResolutionMap.get(targetPkPlaceholder);
                if (resolvedTargetLsk == null) { throw new ReferenceResolutionException("Cannot resolve reference '" + fullRefString + "'. Target placeholder '" + targetPkPlaceholder + "' was not found/resolved."); }
                finalReplacements.put(fullRefString, resolvedTargetLsk);
                log.trace("Mapped FK '{}' -> LSK '{}'", fullRefString, resolvedTargetLsk);
            }
        } else { log.debug("No FK references found for user {}.", devEmail); }


        // --- Perform XML Replacements ---
        log.info("Performing final XML replacements for user {}...", devEmail);
        String resolvedXml = XmlUtils.replaceAllPlaceholders(inputXml, finalReplacements);

        // --- Finish ---
        log.info("LSK/Reference resolution service finished successfully for user: {}", devEmail);
        return resolvedXml;
    }


    // --- Private Helper Method to Insert CONSOLIDATED Log Record ---
    private void logConsolidatedResolution(Connection conn, String email, String table, String column, String module, long startVal, long endVal)
            throws SQLException {
        // (Implementation identical to previous answer - performs the INSERT)
        log.debug("Logging consolidated LSK range: User={}, Table={}, Column={}, Module={}, Range=[{}-{}]", email, table, column, module, startVal, endVal);
        try (PreparedStatement logStatement = conn.prepareStatement(INSERT_LOG_SQL)) {
            logStatement.setString(1, email);
            logStatement.setString(2, table);
            logStatement.setString(3, column);
            logStatement.setString(4,module);
            logStatement.setLong(5, startVal);
            logStatement.setLong(6, endVal);
            int rowsAffected = logStatement.executeUpdate();
            if (rowsAffected != 1) { log.error("Failed to insert consolidated log record! Rows affected: {}", rowsAffected); throw new SQLException("Failed log insert, rows affected: " + rowsAffected); }
            log.trace("Successfully inserted consolidated log record for {}:{}.", table, column);
        } catch (SQLException e) { log.error("SQL Exception inserting consolidated log for User={}, Table={}, Column={}: {}", email, table, column, e.getMessage()); throw e; }
    }
}