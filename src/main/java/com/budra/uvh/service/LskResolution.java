package com.budra.uvh.service;

import com.budra.uvh.config.ConnectionManager;
import com.budra.uvh.model.LskRepository;
import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.ReferenceResolutionException;
import com.budra.uvh.utils.XmlUtils; // Needs the modified version finding elements

// Import Jackson for JSON conversion
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class LskResolution {
    private static final Logger log = LoggerFactory.getLogger(LskResolution.class);
    private final LskRepository lskRepository;
    private final ObjectMapper objectMapper; // For JSON conversion

    // SQL statement remains the same
    private static final String INSERT_LOG_SQL =
            "INSERT INTO LskResolutionLog (" +
                    "  dev_email, table_name, column_name, module_name, start_value, end_value, " +
                    "  placeholder_mapping, source_xml_elements, resolved_xml_elements" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    public LskResolution(LskRepository lskRepository) {
        log.debug("LskResolution instance created with LskRepository.");
        if (lskRepository == null) { throw new IllegalArgumentException("LskRepository cannot be null"); }
        this.lskRepository = lskRepository;
        this.objectMapper = new ObjectMapper();
    }

    // --- Inner class RangeKey remains the same ---
    private static final class RangeKey { /* ... */
        private final String tableName; private final String columnName; private final String moduleName;
        public RangeKey(String t, String c, String m) { this.tableName=t; this.columnName=c; this.moduleName=m; }
        @Override public boolean equals(Object o) { if (this==o) return true; if (o==null||getClass()!=o.getClass()) return false; RangeKey rk=(RangeKey)o; return Objects.equals(tableName, rk.tableName) && Objects.equals(columnName, rk.columnName) && Objects.equals(moduleName, rk.moduleName); }
        @Override public int hashCode() { return Objects.hash(tableName, columnName, moduleName); }
        @Override public String toString() { return "RangeKey{"+tableName+":"+columnName+":"+moduleName+"}";}
    }

    // --- Inner class RangeInfo remains the same ---
    private static class RangeInfo { /* ... */
        long firstValue; long lastValue; String tableName; String columnName; String moduleName;
        RangeInfo(long initialValue, String table, String column, String module) { this.firstValue = initialValue; this.lastValue = initialValue; this.tableName = table; this.columnName = column; this.moduleName = module; }
        public void setLastValue(long lastValue) { this.lastValue = lastValue; }
        public long getFirstValue() { return firstValue; } public long getLastValue() { return lastValue; } public String getTableName() { return tableName; } public String getColumnName() { return columnName; } public String getModuleName() { return moduleName; }
    }


    public String processAndResolveXml(String moduleName, String inputXml, String devEmail)
            throws PlaceholderFormatException, LskGenerationException, ReferenceResolutionException, SQLException {

        log.info("Starting LSK/Reference resolution process for user: {}", devEmail);
        if (this.lskRepository == null || devEmail == null || devEmail.isEmpty() || inputXml == null || inputXml.isEmpty()) { throw new IllegalArgumentException("Repository, DevEmail, and InputXML must not be null or empty."); }

        // --- Step 1: Find Placeholders and Source Elements ---
        Map<String, List<String>> pkPlaceholdersWithElements = XmlUtils.findPkPlaceholdersWithElements(inputXml);
        Map<String, String> fkReferences = XmlUtils.findFkReferences(inputXml);
        Set<String> uniquePkPlaceholders = pkPlaceholdersWithElements.keySet();

        if (uniquePkPlaceholders.isEmpty() && fkReferences.isEmpty()) { log.info("No LSK placeholders or FK references found. Returning original XML."); return inputXml; }
        log.info("Found {} unique PK placeholders and {} unique FK references.", uniquePkPlaceholders.size(), fkReferences.size());

        // --- Step 2: Resolve Primary Keys and Aggregate Data ---
        Map<String, String> pkResolutionMap = new HashMap<>(); // PK Placeholder -> Resolved LSK String
        Map<RangeKey, RangeInfo> rangesProcessed = new HashMap<>();
        Map<RangeKey, List<String>> elementsPerRangeKey = new HashMap<>();
        Map<RangeKey, Map<String, String>> mappingsPerRangeKey = new HashMap<>(); // Specific mappings per key

        Connection generationConn = null;
        boolean generationSuccess = false;

        if (!uniquePkPlaceholders.isEmpty()) {
            try { // Transaction for value generation
                generationConn = ConnectionManager.getConnection();
                generationConn.setAutoCommit(false);
                log.debug("DB transaction started for LSK value generation.");
                Map<RangeKey, Long> nextValueTracker = new HashMap<>();

                for (Map.Entry<String, List<String>> entry : pkPlaceholdersWithElements.entrySet()) { // Loop 1
                    String originalPkPlaceholder = entry.getKey();
                    List<String> sourceElements = entry.getValue();
                    String tableName, columnName;
                    try { String[] parts = originalPkPlaceholder.split(":", 3);
                        if (parts.length < 3 || parts[0].isEmpty()||parts[1].isEmpty()||parts[2].isEmpty()){
                            throw new PlaceholderFormatException(originalPkPlaceholder);
                        }
                        tableName=parts[0];
                        columnName=parts[1];
                    } catch (Exception e){
                        throw new PlaceholderFormatException(originalPkPlaceholder, e);
                    }
                    RangeKey currentKey = new RangeKey(tableName, columnName, moduleName);
                    long valueToAssign;
                    if (nextValueTracker.containsKey(currentKey)) { valueToAssign = nextValueTracker.get(currentKey); } else { valueToAssign = this.lskRepository.getNextStartingValue(generationConn, tableName, columnName, moduleName); }
                    nextValueTracker.put(currentKey, valueToAssign + 1);
                    RangeInfo rangeInfo = rangesProcessed.computeIfAbsent(currentKey, k -> new RangeInfo(valueToAssign, tableName, columnName, moduleName));
                    rangeInfo.setLastValue(valueToAssign);
                    if (sourceElements != null && !sourceElements.isEmpty()) { elementsPerRangeKey.computeIfAbsent(currentKey, k -> new ArrayList<>()).addAll(sourceElements); }
                    String resolvedLskString = tableName + ":" + columnName + ":" + moduleName + ":" + valueToAssign;
                    pkResolutionMap.put(originalPkPlaceholder, resolvedLskString);
                    mappingsPerRangeKey.computeIfAbsent(currentKey, k -> new HashMap<>()).put(originalPkPlaceholder, resolvedLskString);
                }
                generationConn.commit();
                generationSuccess = true;
                log.debug("DB transaction for LSK value generation committed.");
            } catch (Exception e) { // Catch all exceptions during generation
                log.error("Error during LSK value generation transaction for user {}: {}", devEmail, e.getMessage(), e);
                // Rethrow appropriate exception
                if (e instanceof SQLException || e instanceof IllegalArgumentException) { throw new LskGenerationException("DB/Config error during LSK value generation: "+e.getMessage(),e); }
                else if (e instanceof PlaceholderFormatException) { throw (PlaceholderFormatException) e;}
                else { throw new LskGenerationException("Unexpected error during LSK generation: "+e.getMessage(), e); }
            } finally {
                if (generationConn != null) { try { if (!generationSuccess) { generationConn.rollback();} } catch (SQLException ex){log.error("Generation Rollback failed",ex);}finally{ try { generationConn.setAutoCommit(true); generationConn.close();} catch (SQLException e) {log.error("Generation Conn close failed",e);} } }
            }
        } else {
            log.info("No PK placeholders found to resolve for user {}.", devEmail);
        }

        // --- Step 3: Resolve Foreign Keys (Build the final replacement map) ---
        // Start with PK resolutions
        Map<String, String> finalReplacements = new HashMap<>(pkResolutionMap);
        if (!fkReferences.isEmpty()) {
            log.debug("Resolving {} FK references...", fkReferences.size());
            for (Map.Entry<String, String> fkEntry : fkReferences.entrySet()) {
                String fullRefString = fkEntry.getKey(); // REF:{T:C:Lid}
                String targetPkPlaceholder = fkEntry.getValue(); // T:C:Lid
                String resolvedTargetLsk = pkResolutionMap.get(targetPkPlaceholder); // Look up T:C:Value
                if (resolvedTargetLsk == null) { throw new ReferenceResolutionException("Cannot resolve FK '" + fullRefString + "'. Target PK '" + targetPkPlaceholder + "' not resolved."); }
                finalReplacements.put(fullRefString, resolvedTargetLsk); // Add REF:{...} -> T:C:M:Value
            }
        }

        // --- Step 4: Perform Final XML Replacements (for the string returned to plugin) ---
        log.info("Performing final XML replacements for response...");
        String resolvedXmlOutput = XmlUtils.replaceAllPlaceholders(inputXml, finalReplacements); // Use the map with FKs resolved

        // --- Step 5: Log to Database (using the final replacement map for elements) ---
        if (!rangesProcessed.isEmpty()) {
            Connection loggingConn = null;
            boolean loggingSuccess = false;
            try {
                loggingConn = ConnectionManager.getConnection();
                loggingConn.setAutoCommit(false);
                log.debug("DB transaction started for *logging* resolved LSKs.");

                for (Map.Entry<RangeKey, RangeInfo> rangeEntry : rangesProcessed.entrySet()) {
                    RangeKey key = rangeEntry.getKey();
                    RangeInfo range = rangeEntry.getValue();
                    Map<String, String> specificPkMapping = mappingsPerRangeKey.getOrDefault(key, Collections.emptyMap()); // PK map for JSON column
                    List<String> aggregatedSourceElements = elementsPerRangeKey.getOrDefault(key, Collections.emptyList()); // Original elements

                    // <<< Pass the FINAL replacement map to the logging method >>>
                    logResolutionEvent(loggingConn, devEmail,
                            range.getTableName(), range.getColumnName(), range.getModuleName(),
                            range.getFirstValue(), range.getLastValue(),
                            specificPkMapping, // Map with only PKs resolved (for placeholder_mapping column)
                            aggregatedSourceElements, // Original elements (for source_xml_elements column)
                            finalReplacements // <<< Map with PKs AND FKs resolved (for generating resolved_xml_elements column)
                    );
                }

                loggingConn.commit();
                loggingSuccess = true;
                log.info("Consolidated LSK resolution logs committed successfully.");

            } catch (SQLException | JsonProcessingException e) {
                log.error("Error during LSK logging transaction for user {}: {}", devEmail, e.getMessage(), e);
            } finally {
                if (loggingConn != null) { try { if (!loggingSuccess) { loggingConn.rollback();} } catch (SQLException ex){log.error("Logging Rollback failed",ex);}finally{ try { loggingConn.setAutoCommit(true); loggingConn.close();} catch (SQLException e) {log.error("Logging Conn close failed",e);} } }
            }
        }

        // --- Step 6: Return Final Result ---
        log.info("LSK/Reference resolution service finished successfully.");
        return resolvedXmlOutput;
    }


    /**
     * <<< MODIFIED SIGNATURE & LOGIC >>>
     * Inserts a log record.
     * - placeholderMapping (Map<OriginalPK, ResolvedLSK>) is serialized to JSON for the 'placeholder_mapping' column.
     * - sourceElements (List<OriginalElement>) are concatenated for the 'source_xml_elements' column.
     * - replacementsForElements (Map<OriginalPlaceholderOrRef, ResolvedLSK>) is used to generate the 'resolved_xml_elements' column.
     */
    private void logResolutionEvent(Connection conn, String email, String table, String column, String module,
                                    long startVal, long endVal,
                                    Map<String, String> placeholderMapping, // Specific PK map for JSON column
                                    List<String> sourceElements,           // Original elements
                                    Map<String, String> replacementsForElements) // FULL map (PKs+FKs) for resolving elements
            throws SQLException, JsonProcessingException {

        // 1. Serialize the specific PK placeholder map to JSON
        String mappingJsonString = "{}";
        if (placeholderMapping != null && !placeholderMapping.isEmpty()) {
            mappingJsonString = objectMapper.writeValueAsString(placeholderMapping);
        }

        // 2. Concatenate original source elements
        String concatenatedSourceElements = "";
        if (sourceElements != null && !sourceElements.isEmpty()) {
            concatenatedSourceElements = sourceElements.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }

        // 3. <<< Generate resolved elements string using the FULL replacement map >>>
        List<String> resolvedElementsList = new ArrayList<>();
        if (sourceElements != null && !sourceElements.isEmpty() && replacementsForElements != null && !replacementsForElements.isEmpty()) {
            for (String originalElement : sourceElements) {
                String currentResolvedElement = originalElement;
                // Apply ALL replacements (PKs and FKs) relevant to this element
                for (Map.Entry<String, String> replacementEntry : replacementsForElements.entrySet()) {
                    String placeholderOrRef = replacementEntry.getKey();
                    String resolvedValue = replacementEntry.getValue();
                    String placeholderInQuotes = "\"" + placeholderOrRef + "\""; // e.g., "T:C:M:Lid" or "REF:{T:C:M:Lid}"
                    String resolvedInQuotes = "\"" + resolvedValue + "\"";      // e.g., "T:C:M:Value"
                    currentResolvedElement = currentResolvedElement.replace(placeholderInQuotes, resolvedInQuotes);
                }
                resolvedElementsList.add(currentResolvedElement);
            }
        }
        String concatenatedResolvedElements = resolvedElementsList.stream().collect(Collectors.joining("\n"));
        // --- End generating resolved elements ---

        // Truncate for logging display if needed
        int maxLogLength = 4000; // Or adjust as needed
        String loggedSource = concatenatedSourceElements.length() > maxLogLength ? concatenatedSourceElements.substring(0, maxLogLength) + "..." : concatenatedSourceElements;
        String loggedResolved = concatenatedResolvedElements.length() > maxLogLength ? concatenatedResolvedElements.substring(0, maxLogLength) + "..." : concatenatedResolvedElements;
        String loggedMapping = mappingJsonString.length() > maxLogLength ? mappingJsonString.substring(0, maxLogLength) + "..." : mappingJsonString;

        log.debug("Logging LSK Event: User={}, Key={}:{}:{}, Range=[{}-{}], Mapping: {}, Source Elements:\n{}, Resolved Elements:\n{}",
                email, table, column, module, startVal, endVal, loggedMapping, loggedSource, loggedResolved);

        // 4. Insert into Database
        try (PreparedStatement logStatement = conn.prepareStatement(INSERT_LOG_SQL)) {
            int i = 1;
            logStatement.setString(i++, email);
            logStatement.setString(i++, table);
            logStatement.setString(i++, column);
            logStatement.setString(i++, module);
            logStatement.setLong(i++, startVal);
            logStatement.setLong(i++, endVal);
            logStatement.setString(i++, mappingJsonString);             // Col 7: placeholder_mapping (PKs only)
            logStatement.setString(i++, concatenatedSourceElements);   // Col 8: source_xml_elements
            logStatement.setString(i++, concatenatedResolvedElements); // Col 9: resolved_xml_elements (PKs & FKs resolved)

            int rowsAffected = logStatement.executeUpdate();
            if (rowsAffected != 1) {
                log.error("Failed log insert! Rows affected: {} for Key={}:{}:{}", rowsAffected, table, column, module);
                throw new SQLException("Failed log insert, rows affected: " + rowsAffected);
            }
            log.trace("Successfully inserted log record for {}:{}:{}.", table, column, module);
        } catch (SQLException e) {
            log.error("SQL Exception inserting log for Key={}:{}:{}: {}", table, column, module, e.getMessage(), e);
            throw e;
        }
    }
}