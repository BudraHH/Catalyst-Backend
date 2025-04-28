package com.budra.uvh.dao;

import com.budra.uvh.exception.DataPersistenceException;
import com.budra.uvh.model.ParsedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic Data Access Object for inserting ParsedRecord objects into database tables.
 * Uses dynamic SQL generation based on record structure.
 *
 * WARNING: Basic type handling implemented. Production use requires robust conversion
 *          from String attributes to actual database column types. Security validation
 *          on table/column names derived from XML is also crucial.
 */
// No Scope or Service annotation - Instantiation managed by AbstractBinder
public class GenericDao {
    private static final Logger log = LoggerFactory.getLogger(GenericDao.class);

    // Public No-Arg Constructor - Required by AbstractBinder for direct binding
    public GenericDao() {
        log.debug("GenericDao instance created (manual binding).");
    }

    /**
     * Inserts a batch of records into the specified table.
     */
    public void batchInsert(Connection connection, String tableName, List<ParsedRecord> records) throws DataPersistenceException {
        // --- Input Validation ---
        if (records == null || records.isEmpty()) {
            log.debug("No records for batch insert into '{}'. Skipping.", tableName);
            return;
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new DataPersistenceException("Table name is required for batch insert.", null);
        }
        // Basic validation (review for security if names come from untrusted XML)
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new DataPersistenceException("Invalid table name format: " + tableName, null);
        }

        // --- Determine Columns (based on first record) ---
        ParsedRecord sampleRecord = records.get(0);
        List<String> columnNames = new ArrayList<>();
        List<String> attributeKeys = new ArrayList<>(sampleRecord.getAttributes().keySet());
        List<String> fkKeys = new ArrayList<>(sampleRecord.getResolvedForeignKeys().keySet());
        attributeKeys.sort(String::compareTo); // Ensure consistent order
        fkKeys.sort(String::compareTo);

        String pkColumnName = sampleRecord.getPkAttributeName();
        if (pkColumnName != null) {
            columnNames.add(pkColumnName);
        } else {
            log.warn("No PK attribute defined for table '{}'. Assuming DB handles it.", tableName);
        }
        columnNames.addAll(attributeKeys);
        columnNames.addAll(fkKeys);

        if (columnNames.isEmpty()) {
            log.warn("No columns found for insertion into table '{}'. Skipping.", tableName);
            return;
        }

        // --- Build Dynamic SQL ---
        String quotedTableName = quoteIdentifier(tableName, connection);
        String columnsPart = columnNames.stream()
                .map(col -> quoteIdentifier(col, connection))
                .collect(Collectors.joining(", "));
        String valuesPart = String.join(", ", java.util.Collections.nCopies(columnNames.size(), "?"));
        String insertSql = String.format("INSERT INTO %s (%s) VALUES (%s)", quotedTableName, columnsPart, valuesPart);
        log.debug("Generated SQL for table '{}': {}", tableName, insertSql);

        // --- Execute Batch ---
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            final int BATCH_SIZE = 1000;
            int batchCount = 0;

            for (ParsedRecord record : records) {
                int paramIndex = 1;

                // Set PK
                if (pkColumnName != null) {
                    Long pkValue = record.getGeneratedPkValue();
                    if (pkValue == null) {
                        throw new DataPersistenceException("Null generated PK for " + tableName + ", placeholder: " + record.getPkPlaceholder(), null);
                    }
                    ps.setLong(paramIndex++, pkValue);
                }

                // Set Attributes (Simple - treats all as String)
                for (String attrKey : attributeKeys) {
                    String valueStr = record.getAttributes().get(attrKey);
                    setSimpleTypedParameter(ps, paramIndex++, valueStr); // Use simplified setter
                }

                // Set FKs
                for (String fkKey : fkKeys) {
                    Long fkValue = record.getResolvedForeignKeys().get(fkKey);
                    if (fkValue == null) {
                        log.warn("Null resolved FK '{}' for table '{}'. Setting NULL in DB.", fkKey, tableName);
                        // Assuming FK column is BIGINT and allows NULLs
                        ps.setNull(paramIndex++, Types.BIGINT);
                    } else {
                        ps.setLong(paramIndex++, fkValue);
                    }
                }

                ps.addBatch();
                batchCount++;

                if (batchCount > 0 && batchCount % BATCH_SIZE == 0) {
                    executeBatchAndCheckCounts(ps, BATCH_SIZE); // Use helper
                }
            }

            // Execute remaining batch
            int remaining = batchCount % BATCH_SIZE;
            if (remaining > 0) {
                executeBatchAndCheckCounts(ps, remaining); // Use helper
            }

        } catch (BatchUpdateException bue) {
            log.error("Batch insert failed for table '{}'. SQLState: {}, ErrorCode: {}", tableName, bue.getSQLState(), bue.getErrorCode(), bue);
            throw new DataPersistenceException("Batch update failed for table " + tableName, bue);
        } catch (SQLException e) {
            log.error("SQL error during batch insert for table '{}'. SQLState: {}, ErrorCode: {}", tableName, e.getSQLState(), e.getErrorCode(), e);
            throw new DataPersistenceException("SQL error processing table " + tableName, e);
        } catch (Exception e) { // Catch other unexpected errors
            log.error("Unexpected error during batch insert for table '{}'.", tableName, e);
            throw new DataPersistenceException("Unexpected error processing table " + tableName, e);
        }
    }

    /**
     * Helper to execute batch and log results/potential issues.
     */
    private void executeBatchAndCheckCounts(PreparedStatement ps, int expectedCount) throws SQLException, DataPersistenceException {
        log.debug("Executing batch (expected size: {})...", expectedCount);
        int[] results = ps.executeBatch();
        log.trace("Batch results length: {}", results.length);
        // Basic check (can be enhanced)
        if (results.length != expectedCount) {
            log.warn("Batch execution result count ({}) differs from expected count ({}).", results.length, expectedCount);
            // Decide if this constitutes an error based on JDBC driver behavior
        }
        // Check for failures reported by driver
        for (int result : results) {
            if (result == Statement.EXECUTE_FAILED) {
                log.error("Batch statement failed according to driver result code.");
                throw new DataPersistenceException("Batch execution failed for at least one statement.", null);
            }
        }
        log.debug("Batch executed.");
    }

    /**
     * Simplified parameter setter - Treats everything as String or SQL NULL.
     * WARNING: This is NOT robust for different database column types.
     */
    private void setSimpleTypedParameter(PreparedStatement ps, int index, String valueStr) throws SQLException {
        if (valueStr == null) {
            // Simplistic assumption: set NULL as VARCHAR. May fail on non-text columns.
            ps.setNull(index, Types.VARCHAR);
        } else {
            // Set everything else as String. Will fail if DB column expects numeric, date, etc.
            ps.setString(index, valueStr);
        }
    }

    /**
     * Basic identifier quoting (same as before).
     */
    private String quoteIdentifier(String identifier, Connection connection) {
        // Default to no quoting if metadata access fails
        String quoteString = " "; // Use space if no quote char found
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            quoteString = metaData.getIdentifierQuoteString();
            if (quoteString == null || quoteString.trim().isEmpty()) {
                return identifier; // Database doesn't use quotes or driver returned empty
            }
            quoteString = quoteString.trim(); // Use the trimmed quote char

        } catch (SQLException e) {
            log.warn("Could not get identifier quote string. Returning identifier unquoted.", e);
            return identifier;
        }

        if (identifier.startsWith(quoteString) && identifier.endsWith(quoteString)) {
            return identifier;
        }
        return quoteString + identifier + quoteString;
    }
}