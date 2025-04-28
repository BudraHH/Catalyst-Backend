package com.budra.uvh.service;

import com.budra.uvh.config.ConnectionManager; // Corrected potential typo if package was dbConfig
import com.budra.uvh.dao.GenericDao;
import com.budra.uvh.exception.*; // Import all custom exceptions
import com.budra.uvh.model.LskCounterRepository;
import com.budra.uvh.model.ParsedRecord;
import com.budra.uvh.parser.GenericXmlDataParser; // Import the parser
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// --- REMOVE DI Annotations ---
// import org.jvnet.hk2.annotations.Service; // Remove HK2 specific annotation
import jakarta.inject.Inject;             // Keep @Inject on constructor only
// import jakarta.inject.Singleton;          // Remove Scope annotation

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// NO Scope or Service annotation - Instantiation and scope managed by AbstractBinder
public class GenericLskProcessingService {
    private static final Logger log = LoggerFactory.getLogger(GenericLskProcessingService.class);
    // Pattern to extract table and column name from LSK placeholder
    private static final Pattern LSK_PATTERN = Pattern.compile("^([^:]+):([^:]+):.*$");

    // --- Dependencies (final if using constructor injection) ---
    private final LskCounterRepository counterRepository;
    private final GenericDao genericDao;
    private final ConnectionManager connectionManager;
    private final GenericXmlDataParser xmlParser;

    // --- Constructor for Manual Binding ---
    // Keep @Inject: This tells HK2 which constructor to use when it's asked
    // to create an instance based on the AbstractBinder definition. HK2 will
    // resolve the parameters based on other bindings in the binder.
    @Inject
    public GenericLskProcessingService(LskCounterRepository counterRepository,
                                       GenericDao genericDao,
                                       ConnectionManager connectionManager,
                                       GenericXmlDataParser xmlParser) {
        log.debug("GenericLskProcessingService instance created by DI Container (Manual Binding) with injected dependencies.");
        // Null checks are good practice, especially when relying on correct configuration
        if (counterRepository == null) throw new IllegalStateException("LskCounterRepository not injected");
        if (genericDao == null) throw new IllegalStateException("GenericDao not injected");
        if (connectionManager == null) throw new IllegalStateException("ConnectionManager not injected");
        if (xmlParser == null) throw new IllegalStateException("GenericXmlDataParser not injected");

        this.counterRepository = counterRepository;
        this.genericDao = genericDao;
        this.connectionManager = connectionManager;
        this.xmlParser = xmlParser;
    }

    // Remove default no-arg constructor if the @Inject constructor is the only intended one

    /**
     * Parses XML, processes records (LSKs, FKs), and persists within a transaction.
     * (Method documentation remains the same)
     *@param xmlInputStream The InputStream containing the XML data.
     *@throws XmlParsingException If XML parsing fails.
     *@throws LskGenerationException If LSK generation fails.
     *@throws DataPersistenceException If database insertion fails.
     *@throws PlaceholderFormatException If an LSK placeholder is malformed.
     *@throws IllegalStateException If FK resolution fails or other state issues occur.
     */
    public void parseProcessAndPersist(InputStream xmlInputStream)
            throws XmlParsingException, LskGenerationException, DataPersistenceException, PlaceholderFormatException, IllegalStateException, SQLException {
        // --- Stage 1: Parse XML ---
        List<ParsedRecord> allRecords;
        try {
            log.info("Starting XML parsing...");
            // Use the injected parser instance
            allRecords = this.xmlParser.parseXml(xmlInputStream);
            log.info("XML parsing complete. Found {} records.", allRecords.size());
        } catch (XmlParsingException e) {
            // Parser logs specifics, re-throw for controller
            throw e;
        }

        if (allRecords == null || allRecords.isEmpty()) {
            log.info("No records parsed from XML, processing finished.");
            return; // Nothing more to do
        }

        // --- Stages 2 & 3: Process and Persist within Transaction ---
        Map<String, Long> generatedKeysMap = new HashMap<>(); // Placeholder -> Generated ID
        Connection connection = null;
        boolean transactionSuccess = false;

        try {
            // Use the injected connection manager instance
            connection = this.connectionManager.getConnection();
            if (connection == null) {
                throw new DataPersistenceException("Failed to obtain database connection.", null);
            }
            connection.setAutoCommit(false); // Start transaction
            log.info("BEGIN Transaction for LSK generation and persistence.");

            // --- Stage 2a: Generate Primary Keys ---
            generatePrimaryKeys(connection, allRecords, generatedKeysMap);

            // --- Stage 2b: Resolve Foreign Keys ---
            resolveForeignKeys(allRecords, generatedKeysMap);

            // --- Stage 3: Persist Data ---
            persistData(connection, allRecords);

            // --- Commit ---
            connection.commit();
            transactionSuccess = true;
            log.info("COMMIT Transaction successful.");

        } catch (SQLException | LskGenerationException | PlaceholderFormatException | DataPersistenceException | IllegalStateException e) {
            // Log the error for server-side diagnosis
            log.error("Error during processing or persistence. Transaction will be rolled back.", e);
            // Rollback happens in finally. Re-throw the original exception or a wrapper.
            // Wrapping SQLException in DataPersistenceException is common.
            if (e instanceof SQLException) {
                throw new DataPersistenceException("Database error during transaction: " + e.getMessage(), e);
            } else {
                throw e;
            }
        } catch (Exception e) {
            // Catch unexpected runtime errors
            log.error("Unexpected error during processing. Transaction will be rolled back.", e);
            throw new RuntimeException("Unexpected error during processing: " + e.getMessage(), e);
        }
        finally {
            // Ensure resources are cleaned up (rollback if needed, close connection)
            cleanupResources(connection, transactionSuccess);
        }
    }

    // --- Helper Methods ---

    /** Generates primary keys for all records needing one. */
    private void generatePrimaryKeys(Connection connection, List<ParsedRecord> allRecords, Map<String, Long> generatedKeysMap)
            throws LskGenerationException, PlaceholderFormatException, SQLException {
        log.debug("Generating primary keys for {} records...", allRecords.size());
        int generatedCount = 0;
        for (ParsedRecord record : allRecords) {
            String placeholder = record.getPkPlaceholder();
            if (placeholder != null && !placeholder.isEmpty()) {
                // Pass the injected repository instance to the helper
                generateAndStoreKey(connection, placeholder, generatedKeysMap, record::setGeneratedPkValue, this.counterRepository);
                generatedCount++;
            }
        }
        log.debug("Primary key generation complete. Generated {} keys.", generatedCount);
    }

    /** Generates a single key using the repository and stores it. */
    private void generateAndStoreKey(Connection connection, String placeholder, Map<String, Long> map,
                                     java.util.function.Consumer<Long> idSetter, LskCounterRepository repo) // Pass repo explicitly
            throws LskGenerationException, PlaceholderFormatException, SQLException {

        if (placeholder == null || placeholder.trim().isEmpty()) {
            throw new PlaceholderFormatException("Cannot generate key for null or empty placeholder.");
        }
        // Check if key already generated for this placeholder in this run
        if (map.containsKey(placeholder)) {
            log.warn("Duplicate PK placeholder found: '{}'. Reusing already generated key.", placeholder);
            idSetter.accept(map.get(placeholder));
            return;
        }
        // Parse placeholder
        Matcher matcher = LSK_PATTERN.matcher(placeholder);
        if (!matcher.matches()) {
            throw new PlaceholderFormatException("Invalid LSK placeholder format: " + placeholder);
        }
        String tableName = matcher.group(1);
        String columnName = matcher.group(2);
        int count = 1; // Always generate one key at a time here

        try {
            // Use the passed repository instance
            long generatedId = repo.getAndReserveNextValueBlock(connection, tableName, columnName, count);
            log.trace("Generated PK ID {} for placeholder '{}'", generatedId, placeholder);
            map.put(placeholder, generatedId); // Store mapping
            idSetter.accept(generatedId); // Set on the ParsedRecord
        } catch (IllegalArgumentException iae) {
            // Wrap argument errors from repo layer
            throw new LskGenerationException("Invalid arguments for LSK generation (placeholder: '" + placeholder + "'): " + iae.getMessage(), iae);
        }
        // SQLException is declared and thrown directly by repo method
    }

    /** Resolves foreign keys by looking up parent PKs in the generatedKeysMap. */
    private void resolveForeignKeys(List<ParsedRecord> allRecords, Map<String, Long> generatedKeysMap) {
        log.debug("Resolving foreign keys...");
        int resolvedCount = 0;
        for (ParsedRecord record : allRecords) {
            if (record.getForeignKeyLinks().isEmpty()) {
                continue; // Skip records with no FKs defined
            }
            for (Map.Entry<String, String> fkEntry : record.getForeignKeyLinks().entrySet()) {
                String fkAttributeName = fkEntry.getKey();
                String parentPlaceholder = fkEntry.getValue();
                Long parentGeneratedId = generatedKeysMap.get(parentPlaceholder);

                // Critical check: Parent key must have been generated in this batch
                if (parentGeneratedId == null) {
                    // This indicates an ordering issue or missing parent in the XML/batch
                    throw new IllegalStateException(String.format(
                            "FK Resolution Failed: Cannot find generated key for parent placeholder '%s' (referenced by FK '%s' in table '%s', record PK placeholder '%s'). Ensure parent elements appear before children or have generated keys.",
                            parentPlaceholder, fkAttributeName, record.getTableName(), record.getPkPlaceholder()
                    ));
                }
                record.addResolvedForeignKey(fkAttributeName, parentGeneratedId);
                resolvedCount++;
                log.trace("Resolved FK '{}' for {}({}) -> Parent PK ID {}", fkAttributeName, record.getTableName(), record.getPkPlaceholder(), parentGeneratedId);
            }
        }
        log.debug("Foreign key resolution complete. Resolved {} references.", resolvedCount);
    }

    /** Persists all records, grouped by table name, using the GenericDao. */
    private void persistData(Connection connection, List<ParsedRecord> allRecords) throws DataPersistenceException {
        log.debug("Persisting data for {} records using Generic DAO...", allRecords.size());
        try {
            // Group records by table name before sending to DAO
            Map<String, List<ParsedRecord>> recordsByTable = allRecords.stream()
                    .collect(Collectors.groupingBy(ParsedRecord::getTableName));

            // Iterate through each table's records and call batchInsert
            for (Map.Entry<String, List<ParsedRecord>> entry : recordsByTable.entrySet()) {
                String tableName = entry.getKey();
                List<ParsedRecord> recordsForTable = entry.getValue();
                if (!recordsForTable.isEmpty()) {
                    log.debug("Persisting {} records for table '{}'", recordsForTable.size(), tableName);
                    // Use the injected DAO instance
                    this.genericDao.batchInsert(connection, tableName, recordsForTable);
                }
            }
        } catch (DataPersistenceException e) {
            // Re-throw DAO exceptions directly
            throw e;
        } catch (Exception e) {
            // Wrap unexpected errors during grouping or DAO call
            log.error("Unexpected error during data persistence phase.", e);
            throw new DataPersistenceException("Unexpected error during data persistence: " + e.getMessage(), e);
        }
        log.debug("Generic data persistence complete.");
    }

    /** Safely cleans up JDBC connection resources. */
    private void cleanupResources(Connection connection, boolean transactionSuccess) {
        if (connection != null) {
            try {
                // Rollback if transaction wasn't successful
                if (!transactionSuccess) {
                    try {
                        log.warn("Rolling back transaction due to processing error.");
                        connection.rollback();
                        log.info("Transaction rollback completed.");
                    } catch (SQLException exRb) {
                        // Log critical failure to rollback
                        log.error("!!! CRITICAL: Failed to rollback transaction !!!", exRb);
                    }
                }
            } finally {
                // Always try to reset auto-commit and close the connection
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException exAc) {
                    log.warn("Failed to reset autoCommit on connection.", exAc);
                }
                try {
                    connection.close();
                    log.debug("Database connection closed/returned to pool.");
                } catch (SQLException exCl) {
                    // Log error but don't throw from finally
                    log.error("Failed to close database connection.", exCl);
                }
            }
        }
    }
}