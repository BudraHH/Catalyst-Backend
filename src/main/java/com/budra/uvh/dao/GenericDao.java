package com.budra.uvh.dao;

import com.budra.uvh.exception.DataPersistenceException;
import com.budra.uvh.model.ParsedRecord;
import jakarta.inject.Singleton; // Typically stateless, suitable for Singleton
import org.jvnet.hk2.annotations.Service;
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
 * WARNING: Dynamic SQL generation requires careful validation or trust of the input
 *          source (XML structure) to prevent SQL injection vulnerabilities if table/column
 *          names could be manipulated maliciously.
 *
 * WARNING: Basic type handling implemented. Production use requires robust conversion
 *          from String attributes to actual database column types.
 */
@Service
@Singleton
public class GenericDao {
    private static final Logger log = LoggerFactory.getLogger(GenericDao.class);

    public GenericDao() {
        log.debug("GenericDao instance created (@Singleton).");
    }

    /**
     * Inserts a batch of records into the specified table.
     * Assumes all records in the list belong to the same table and have a consistent
     * set of columns (PK, attributes, FKs) derived from the first record.
     *
     * @param connection The active database connection (caller manages transaction).
     * @param tableName  The name of the table to insert into (derived from XML tag).
     * @param records    A list of ParsedRecord objects for the specified table.
     * @throws DataPersistenceException If any database error occurs during insertion or
     *                                  if records are inconsistent or missing vital info (like resolved keys).
     */
    public void batchInsert(Connection connection, String tableName, List<ParsedRecord> records) throws DataPersistenceException {
        if (records == null || records.isEmpty()) {
            log.debug("No records provided for batch insert into table '{}'. Skipping.", tableName);
            return;
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new DataPersistenceException("Table name cannot be null or empty for batch insert.", null);
        }
        // Basic validation on table name to prevent trivial SQL injection characters - Needs more robustness for untrusted input
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new DataPersistenceException("Invalid table name format detected: " + tableName, null);
        }

        // --- Determine Columns Dynamically from the first record ---
        // Assumes all records in the batch have the same logical structure
        ParsedRecord sampleRecord = records.get(0);
        List<String> columnNames = new ArrayList<>();
        List<String> attributeKeys = new ArrayList<>(sampleRecord.getAttributes().keySet());
        List<String> fkKeys = new ArrayList<>(sampleRecord.getResolvedForeignKeys().keySet());

        // Define insertion order: PK -> Attributes (sorted for consistency) -> FKs (sorted for consistency)
        attributeKeys.sort(String::compareTo); // Sort for predictable SQL/param order
        fkKeys.sort(String::compareTo);        // Sort for predictable SQL/param order

        String pkColumnName = sampleRecord.getPkAttributeName();
        if (pkColumnName != null) {
            columnNames.add(pkColumnName);
        } else {
            // Decide how to handle records without a PK defined in the XML/parsing stage
            log.warn("Sample record for table '{}' does not have a primary key attribute defined. " +
                    "Ensure this is intended or DB allows inserts without explicit PK.", tableName);
            // If PK is absolutely required, throw exception:
            // throw new DataPersistenceException("Cannot insert records without PK for table " + tableName, null);
        }
        columnNames.addAll(attributeKeys);
        columnNames.addAll(fkKeys);

        if (columnNames.isEmpty()) {
            // Should only happen if PK is missing AND there are no attributes/FKs
            log.warn("No columns identified for insertion into table '{}' based on the first record.", tableName);
            return; // Or throw error
        }

        // --- Build Dynamic Prepared Statement SQL ---
        // Use DB-specific quoting for table/column names if necessary (e.g., `"` for Postgres)
        String quotedTableName = quoteIdentifier(tableName, connection); // Basic quoting example
        List<String> quotedColumnNames = columnNames.stream()
                .map(col -> quoteIdentifier(col, connection))
                .collect(Collectors.toList());

        StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ");
        sqlBuilder.append(quotedTableName).append(" (");
        sqlBuilder.append(String.join(", ", quotedColumnNames));
        sqlBuilder.append(") VALUES (");
        sqlBuilder.append(String.join(", ", java.util.Collections.nCopies(columnNames.size(), "?")));
        sqlBuilder.append(")");

        String insertSql = sqlBuilder.toString();
        log.debug("Generated SQL for table '{}': {}", tableName, insertSql);

        // --- Execute Batch Insert ---
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            int batchCount = 0;
            final int BATCH_SIZE = 1000; // Adjust batch size as needed

            for (ParsedRecord record : records) {
                int paramIndex = 1;

                // 1. Set PK Parameter
                if (pkColumnName != null) {
                    if (record.getGeneratedPkValue() == null) {
                        throw new DataPersistenceException("Record for table '" + tableName + "' has null generated PK value. Placeholder: " + record.getPkPlaceholder(), null);
                    }
                    ps.setLong(paramIndex++, record.getGeneratedPkValue());
                }

                // 2. Set Attribute Parameters (CRITICAL: Needs Proper Type Handling)
                for (String attrKey : attributeKeys) { // Iterate in sorted order
                    String valueStr = record.getAttributes().get(attrKey);
                    setTypedParameter(ps, paramIndex++, valueStr, connection, tableName, attrKey); // Call type handling helper
                }

                // 3. Set FK Parameters
                for (String fkKey : fkKeys) { // Iterate in sorted order
                    Long fkValue = record.getResolvedForeignKeys().get(fkKey);
                    if (fkValue == null) {
                        // Check if the FK column allows NULLs in DB. If not, this is an error.
                        // If it allows NULLs, decide if this case is valid based on requirements.
                        log.warn("Resolved FK value for key '{}' in table '{}' is null. Placeholder: {}. Setting NULL in PreparedStatement.",
                                fkKey, tableName, record.getPkPlaceholder());
                        // Assuming FK column is BIGINT or similar numeric type allowing NULLs
                        ps.setNull(paramIndex++, Types.BIGINT); // Use appropriate java.sql.Types constant
                        // throw new DataPersistenceException("Record for table '" + tableName + "' has null resolved FK value for key '" + fkKey + "'. Placeholder: " + record.getPkPlaceholder(), null);
                    } else {
                        ps.setLong(paramIndex++, fkValue);
                    }
                }

                ps.addBatch();
                batchCount++;

                // Optional: Execute batch periodically if list is very large
                if (batchCount % BATCH_SIZE == 0) {
                    log.debug("Executing intermediate batch for table '{}' ({} records)...", tableName, batchCount);
                    executeBatchAndUpdateCounts(ps, BATCH_SIZE); // Helper to execute and check counts
                    log.debug("Intermediate batch executed.");
                }
            }

            // Execute any remaining batch
            if (batchCount % BATCH_SIZE != 0) {
                log.debug("Executing final batch for table '{}' ({} records)...", tableName, records.size() % BATCH_SIZE);
                executeBatchAndUpdateCounts(ps, records.size() % BATCH_SIZE); // Pass expected count for final batch
                log.debug("Final batch executed.");
            }

        } catch (BatchUpdateException bue) {
            log.error("Error during batch insert for table '{}'. Update counts: {}", tableName, bue.getUpdateCounts(), bue);
            // You might want to log the specific SQLState or vendor error code from bue.getNextException()
            throw new DataPersistenceException("Batch update failed for table " + tableName + ": " + bue.getMessage(), bue);
        } catch (SQLException e) {
            log.error("SQL error during batch insert preparation or execution for table '{}'. SQLState: {}, ErrorCode: {}", tableName, e.getSQLState(), e.getErrorCode(), e);
            throw new DataPersistenceException("SQL error inserting into table " + tableName + ".", e);
        } catch (Exception e) { // Catch unexpected errors (e.g., type conversion)
            log.error("Unexpected error preparing or executing batch insert for table '{}'.", tableName, e);
            throw new DataPersistenceException("Unexpected error during insert for " + tableName + ".", e);
        }
    }

    /**
     * Executes the current batch and provides basic logging/checking of results.
     * @param ps The PreparedStatement with added batches.
     * @param expectedCount The number of statements expected in this batch execution.
     * @throws SQLException If executeBatch fails.
     * @throws DataPersistenceException If the result count indicates potential failure.
     */
    private void executeBatchAndUpdateCounts(PreparedStatement ps, int expectedCount) throws SQLException, DataPersistenceException {
        int[] results = ps.executeBatch();
        long successCount = 0;
        boolean failureDetected = false;
        for (int result : results) {
            if (result >= 0 || result == Statement.SUCCESS_NO_INFO) {
                successCount++;
            } else if (result == Statement.EXECUTE_FAILED) {
                failureDetected = true;
                // Log specific failure if possible, though BatchUpdateException is usually caught first
            }
        }
        log.trace("Batch execution result count: {}, Success/NoInfo count: {}", results.length, successCount);
        if (results.length != expectedCount || failureDetected) {
            // Log clearly but might not always be a fatal error depending on DB/driver
            log.warn("Potential issue during batch execution: Expected results count {}, actual count {}. Failure detected: {}. Success count: {}",
                    expectedCount, results.length, failureDetected, successCount);
            // Throw exception if strict checking is needed
            // throw new DataPersistenceException("Batch execution failed or did not update expected number of rows.", null);
        }
    }


    /**
     * Sets a parameter on a PreparedStatement with basic type handling.
     * !!! THIS IS A VERY BASIC EXAMPLE - REPLACE WITH ROBUST TYPE HANDLING !!!
     *
     * @param ps           The PreparedStatement.
     * @param index        The parameter index (1-based).
     * @param valueStr     The string value from the ParsedRecord attribute.
     * @param connection   The database connection (for metadata).
     * @param tableName    The target table name.
     * @param columnName   The target column name.
     * @throws SQLException If setting the parameter fails.
     * @throws DataPersistenceException If type conversion is needed but fails.
     */
    private void setTypedParameter(PreparedStatement ps, int index, String valueStr, Connection connection, String tableName, String columnName)
            throws SQLException, DataPersistenceException {

        // --- !! Robust Type Handling Logic Needed Here !! ---
        // 1. Get actual column type from DatabaseMetaData
        //    - Cache metadata to avoid repeated lookups per batch.
        // 2. Based on the SQL type, attempt to parse/convert valueStr:
        //    - Integer.parseInt(), Long.parseLong(), Double.parseDouble()
        //    - Date.valueOf(), Timestamp.valueOf() (handle date formats carefully!)
        //    - Boolean.parseBoolean() (handle variations like "true"/"false", "T"/"F", "1"/"0")
        // 3. Use the corresponding ps.setXxx() method (setInt, setLong, setDate, setTimestamp, setBoolean, etc.)
        // 4. Handle potential parsing errors (NumberFormatException, IllegalArgumentException) gracefully.
        // 5. Handle NULL values appropriately (ps.setNull(index, sql_type)).

        // --- Basic Example (Treats everything as String) ---
        if (valueStr == null) {
            // Need to know the actual SQL type to call setNull correctly
            log.warn("Attribute value for {}.{} is null. Setting NULL using Types.VARCHAR (basic fallback).", tableName, columnName);
            ps.setNull(index, Types.VARCHAR); // This might fail if column isn't character-based
        } else {
            ps.setString(index, valueStr);
        }
    }


    /**
     * Basic identifier quoting. Databases have different rules (e.g., ", [, `).
     * Uses JDBC DatabaseMetaData if possible.
     * @param identifier Table or column name.
     * @param connection Active connection.
     * @return Quoted identifier if necessary, otherwise the original identifier.
     */
    private String quoteIdentifier(String identifier, Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String quoteString = metaData.getIdentifierQuoteString();
            // If quoteString is not null or empty space, and identifier isn't already quoted
            if (quoteString != null && !quoteString.trim().isEmpty() &&
                    !identifier.startsWith(quoteString.trim()) && !identifier.endsWith(quoteString.trim())) {
                return quoteString.trim() + identifier + quoteString.trim();
            }
        } catch (SQLException e) {
            log.warn("Could not get identifier quote string from DatabaseMetaData. Returning identifier unquoted.", e);
        }
        // Return original if quoting isn't needed or failed
        return identifier;
    }

}