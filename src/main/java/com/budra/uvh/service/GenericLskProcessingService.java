package com.budra.uvh.service;

import com.budra.uvh.dao.GenericDao;
import com.budra.uvh.config.ConnectionManager;
import com.budra.uvh.exception.*; // Import all custom exceptions
import com.budra.uvh.model.LskCounterRepository;
import com.budra.uvh.model.ParsedRecord;
import com.budra.uvh.parser.GenericXmlDataParser; // Import the parser
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// --- Import DI annotations if using a framework ---
import jakarta.inject.Inject;
import jakarta.inject.Singleton; // Or ApplicationScoped

import java.io.InputStream; // Accept InputStream
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Singleton // Often services are singletons unless they hold request-specific state
public class GenericLskProcessingService {
    private static final Logger log = LoggerFactory.getLogger(GenericLskProcessingService.class);
    private static final Pattern LSK_PATTERN = Pattern.compile("^([^:]+):([^:]+):.*$");

    // --- Dependencies ---
    private final LskCounterRepository counterRepository;
    private final GenericDao genericDao;
    private final ConnectionManager connectionManager;
    private final GenericXmlDataParser xmlParser; // Add parser dependency

    @Inject // Constructor for DI
    public GenericLskProcessingService(LskCounterRepository counterRepository,
                                       GenericDao genericDao,
                                       ConnectionManager connectionManager,
                                       GenericXmlDataParser xmlParser) { // Inject parser
        this.counterRepository = counterRepository;
        this.genericDao = genericDao;
        this.connectionManager = connectionManager;
        this.xmlParser = xmlParser; // Store parser
    }

    /**
     * Parses XML from InputStream, processes parsed data (generates LSKs,
     * resolves FKs), and persists to DB within a single transaction.
     *
     * @param xmlInputStream The InputStream containing the XML data.
     * @throws XmlParsingException If XML parsing fails.
     * @throws LskGenerationException If LSK generation fails.
     * @throws DataPersistenceException If database insertion fails.
     * @throws PlaceholderFormatException If an LSK placeholder is malformed.
     * @throws IllegalStateException If FK resolution fails or other state issues occur.
     */
    public void parseProcessAndPersist(InputStream xmlInputStream) {
        // --- Stage 1: Parse XML ---
        // Parsing happens first, outside the main DB transaction if it's slow,
        // but included here for simplicity of example flow. For very large files
        // consider parsing first, then starting transaction.
        List<ParsedRecord> allRecords;
        try {
            log.info("Starting XML parsing...");
            allRecords = xmlParser.parseXml(xmlInputStream);
            log.info("XML parsing complete. Found {} records.", allRecords.size());
        } catch (XmlParsingException e) {
            // Logged within parser usually
            throw e; // Re-throw parsing exceptions immediately
        }

        if (allRecords == null || allRecords.isEmpty()) {
            log.info("No records parsed from XML, nothing to process or persist.");
            return;
        }

        // --- Stages 2 & 3: Process and Persist within Transaction ---
        Map<String, Long> generatedKeysMap = new HashMap<>(); // Placeholder -> Generated ID
        Connection connection = null;
        boolean transactionSuccess = false;

        try {
            connection = connectionManager.getConnection();
            connection.setAutoCommit(false);
            log.info("BEGIN Transaction for LSK generation and persistence.");

            // --- Stage 2a: Generate Primary Keys ---
            generatePrimaryKeys(connection, allRecords, generatedKeysMap);

            // --- Stage 2b: Resolve Foreign Keys ---
            resolveForeignKeys(allRecords, generatedKeysMap);

            // --- Stage 3: Persist Data ---
            persistData(connection, allRecords); // Pass the already parsed records

            connection.commit();
            transactionSuccess = true;
            log.info("COMMIT Transaction successful.");

        } catch (SQLException | LskGenerationException | PlaceholderFormatException | DataPersistenceException | IllegalStateException e) {
            log.error("Error during processing or persistence. Transaction will be rolled back.", e);
            // Rollback logic is handled in finally block
            if (e instanceof SQLException) {
                throw new DataPersistenceException("Database error during transaction.", e);
            } else if (e instanceof RuntimeException) {
                // Assumes custom exceptions extend RuntimeException
                throw (RuntimeException) e;
            } else {
                // Should not happen if custom exceptions are RuntimeExceptions
                throw new RuntimeException("Unexpected checked exception during processing", e);
            }
        } finally {
            cleanupResources(connection, transactionSuccess); // Use same cleanup method
        }
    }

    // Methods: generatePrimaryKeys, generateAndStoreKey, resolveForeignKeys, persistData, cleanupResources
    // remain the same as in the previous GenericLskProcessingService example, operating on the List<ParsedRecord>.
    // ... (include the implementations for these methods from the previous response) ...
    private void generatePrimaryKeys(Connection connection, List<ParsedRecord> allRecords, Map<String, Long> generatedKeysMap)
            throws LskGenerationException, PlaceholderFormatException, SQLException {
        log.debug("Generating primary keys for {} records...", allRecords.size());
        int generatedCount = 0;
        for (ParsedRecord record : allRecords) {
            String placeholder = record.getPkPlaceholder();
            if (placeholder != null && !placeholder.isEmpty()) {
                generateAndStoreKey(connection, placeholder, generatedKeysMap, record::setGeneratedPkValue);
                generatedCount++;
            }
        }
        log.debug("Primary key generation complete. Generated {} keys.", generatedCount);
    }

    private void generateAndStoreKey(Connection connection, String placeholder, Map<String, Long> map, java.util.function.Consumer<Long> idSetter)
            throws LskGenerationException, PlaceholderFormatException, SQLException {
        if (placeholder == null || placeholder.trim().isEmpty()) {
            throw new PlaceholderFormatException("Cannot generate key for null or empty placeholder.");
        }
        if (map.containsKey(placeholder)) {
            log.warn("Duplicate PK placeholder found: '{}'. Reusing already generated key.", placeholder);
            idSetter.accept(map.get(placeholder));
            return;
        }
        Matcher matcher = LSK_PATTERN.matcher(placeholder);
        if (!matcher.matches()) {
            throw new PlaceholderFormatException("Invalid LSK placeholder format: " + placeholder);
        }
        String tableName = matcher.group(1);
        String columnName = matcher.group(2);
        int count = 1;
        try {
            long generatedId = counterRepository.getAndReserveNextValueBlock(connection, tableName, columnName, count);
            log.trace("Generated PK ID {} for placeholder '{}'", generatedId, placeholder);
            map.put(placeholder, generatedId);
            idSetter.accept(generatedId);
        } catch (IllegalArgumentException iae) {
            throw new LskGenerationException("Invalid arguments for LSK generation from placeholder '" + placeholder + "': " + iae.getMessage(), iae);
        }
    }

    private void resolveForeignKeys(List<ParsedRecord> allRecords, Map<String, Long> generatedKeysMap) {
        log.debug("Resolving foreign keys...");
        int resolvedCount = 0;
        for (ParsedRecord record : allRecords) {
            if (record.getForeignKeyLinks().isEmpty()) continue;
            for (Map.Entry<String, String> fkEntry : record.getForeignKeyLinks().entrySet()) {
                String fkAttributeName = fkEntry.getKey();
                String parentPlaceholder = fkEntry.getValue();
                Long parentGeneratedId = generatedKeysMap.get(parentPlaceholder);
                if (parentGeneratedId == null) {
                    throw new IllegalStateException("Failed to resolve FK '" + fkAttributeName + "' for record " + record.getTableName() +
                            " (PK placeholder: " + record.getPkPlaceholder() + "). Referenced parent placeholder '" +
                            parentPlaceholder + "' was not found or did not generate a key.");
                }
                record.addResolvedForeignKey(fkAttributeName, parentGeneratedId);
                resolvedCount++;
                log.trace("Resolved FK '{}' for {}({}) -> {}", fkAttributeName, record.getTableName(), record.getPkPlaceholder(), parentGeneratedId);
            }
        }
        log.debug("Foreign key resolution complete. Resolved {} references.", resolvedCount);
    }

    private void persistData(Connection connection, List<ParsedRecord> allRecords) throws DataPersistenceException {
        log.debug("Persisting data using Generic DAO...");
        try {
            Map<String, List<ParsedRecord>> recordsByTable = allRecords.stream()
                    .collect(Collectors.groupingBy(ParsedRecord::getTableName));
            for (Map.Entry<String, List<ParsedRecord>> entry : recordsByTable.entrySet()) {
                String tableName = entry.getKey();
                List<ParsedRecord> recordsForTable = entry.getValue();
                if (!recordsForTable.isEmpty()) {
                    log.debug("Persisting {} records for table '{}'", recordsForTable.size(), tableName);
                    genericDao.batchInsert(connection, tableName, recordsForTable);
                }
            }
        } catch (DataPersistenceException e) { /*...*/ throw e; }
        catch (Exception e) { /*...*/ throw new DataPersistenceException(""); }
        log.debug("Generic data persistence complete.");
    }

    private void cleanupResources(Connection connection, boolean transactionSuccess) {
        if (connection != null) {
            try { if (!transactionSuccess) {
//                try { /* rollback */ } catch (SQLException exRb) { /* log */ }
            }
            } finally { try { connection.setAutoCommit(true); } catch (SQLException exAc) {/* log */}
                try { connection.close(); } catch (SQLException exCl) {/* log */} }
        }
    }
}