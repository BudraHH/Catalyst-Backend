package com.budra.uvh.service;

import com.budra.uvh.config.ConnectionManager;
import com.budra.uvh.model.LskRepository;
import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.ReferenceResolutionException;
import com.budra.uvh.utils.XmlUtils; // Needs the modified version

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

    private static final String INSERT_LOG_SQL =
            "INSERT INTO LskResolutionLog (dev_email, table_name, column_name, module_name, start_value, end_value, source_xml_elements) VALUES (?, ?, ?, ?, ?, ?, ?)";

    public LskResolution(LskRepository lskRepository) {
        log.debug("LskResolution instance created with LskRepository.");
        if (lskRepository == null) {
            throw new IllegalArgumentException("LskRepository cannot be null");
        }
        this.lskRepository = lskRepository;
    }

    // --- Inner class RangeKey remains the same ---
    private static final class RangeKey {
        // ... (same as before) ...
        private final String tableName; private final String columnName; private final String moduleName;
        public RangeKey(String t, String c, String m) { this.tableName=t; this.columnName=c; this.moduleName=m; }
        @Override public boolean equals(Object o) { if (this==o) return true; if (o==null||getClass()!=o.getClass()) return false; RangeKey rk=(RangeKey)o; return Objects.equals(tableName, rk.tableName) && Objects.equals(columnName, rk.columnName) && Objects.equals(moduleName, rk.moduleName); }
        @Override public int hashCode() { return Objects.hash(tableName, columnName, moduleName); }
        @Override public String toString() { return "RangeKey{"+tableName+":"+columnName+":"+moduleName+"}";}
    }

    // --- Inner class RangeInfo: No longer needs to store sourceElements directly ---
    // It only tracks the range itself for the log entry.
    private static class RangeInfo {
        long firstValue;
        long lastValue;
        String tableName;
        String columnName;
        String moduleName;
        // <<< REMOVED sourceElements List >>>

        RangeInfo(long initialValue, String table, String column, String module) {
            this.firstValue = initialValue; this.lastValue = initialValue; this.tableName = table; this.columnName = column; this.moduleName = module;
        }
        public void setLastValue(long lastValue) { this.lastValue = lastValue; }
        // <<< REMOVED addSourceElement Method >>>
        // Getters
        public long getFirstValue() { return firstValue; } public long getLastValue() { return lastValue; } public String getTableName() { return tableName; } public String getColumnName() { return columnName; } public String getModuleName() { return moduleName; }
        // <<< REMOVED getSourceElements Getter >>>
    }


    public String processAndResolveXml(String inputXml, String devEmail)
            throws PlaceholderFormatException, LskGenerationException, ReferenceResolutionException, SQLException {

        log.info("Starting LSK/Reference resolution process for user: {}", devEmail);
        if (this.lskRepository == null || devEmail == null || devEmail.isEmpty() || inputXml == null || inputXml.isEmpty()) {
            throw new IllegalArgumentException("Repository, DevEmail, and InputXML must not be null or empty.");
        }

        // --- Find Placeholders AND Source Elements ---
        Map<String, List<String>> pkPlaceholdersWithElements = XmlUtils.findPkPlaceholdersWithElements(inputXml);
        Map<String, String> fkReferences = XmlUtils.findFkReferences(inputXml);
        Set<String> uniquePkPlaceholders = pkPlaceholdersWithElements.keySet();

        if (uniquePkPlaceholders.isEmpty() && fkReferences.isEmpty()) {
            log.info("No LSK placeholders or FK references found for user {}. Returning original XML.", devEmail);
            return inputXml;
        }
        log.info("Found {} unique PK placeholders and {} unique FK references for user {}",
                uniquePkPlaceholders.size(), fkReferences.size(), devEmail);

        // --- Resolve Primary Keys ---
        Map<String, String> pkResolutionMap = new HashMap<>(); // PK Placeholder -> Resolved LSK String
        Map<RangeKey, RangeInfo> rangesProcessed = new HashMap<>(); // Tracks first/last value per RangeKey
        // <<< NEW MAP: Aggregate source elements per RangeKey >>>
        Map<RangeKey, List<String>> elementsPerRangeKey = new HashMap<>(); // RangeKey -> List of <Element...> strings

        Connection connection = null;
        boolean transactionSuccess = false;

        if (!uniquePkPlaceholders.isEmpty()) {
            try {
                connection = ConnectionManager.getConnection();
                connection.setAutoCommit(false);
                log.debug("DB transaction started for LSK generation.");
                Map<RangeKey, Long> nextValueTracker = new HashMap<>();

                // --- Loop 1: Resolve LSKs and Aggregate Source Elements ---
                log.debug("--- Starting LSK Resolution and Element Aggregation Phase ---");
                for (Map.Entry<String, List<String>> entry : pkPlaceholdersWithElements.entrySet()) {
                    String pkPlaceholder = entry.getKey();
                    List<String> sourceElementsForThisPlaceholder = entry.getValue();

                    String tableName, columnName, moduleName;
                    try {
                        String[] parts = pkPlaceholder.split(":", 4);
                        if (parts.length < 4 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                            throw new PlaceholderFormatException("Invalid PK format parsed: " + pkPlaceholder);
                        }
                        tableName = parts[0]; columnName = parts[1]; moduleName = parts[2];
                    } catch (Exception e) { throw new PlaceholderFormatException("Error parsing PK placeholder: " + pkPlaceholder, e); }

                    RangeKey currentKey = new RangeKey(tableName, columnName, moduleName);
                    long valueToAssign;

                    // Determine next value
                    if (nextValueTracker.containsKey(currentKey)) {
                        valueToAssign = nextValueTracker.get(currentKey);
                    } else {
                        valueToAssign = this.lskRepository.getNextStartingValue(connection, tableName, columnName, moduleName);
                    }
                    nextValueTracker.put(currentKey, valueToAssign + 1); // Update tracker for next iteration

                    // Track the MIN/MAX values for the range log
                    RangeInfo rangeInfo = rangesProcessed.computeIfAbsent(currentKey,
                            k -> new RangeInfo(valueToAssign, tableName, columnName, moduleName));
                    rangeInfo.setLastValue(valueToAssign); // Keep updating last value

                    // <<< AGGREGATE source elements for this RangeKey >>>
                    if (sourceElementsForThisPlaceholder != null && !sourceElementsForThisPlaceholder.isEmpty()) {
                        elementsPerRangeKey.computeIfAbsent(currentKey, k -> new ArrayList<>())
                                .addAll(sourceElementsForThisPlaceholder); // Add all elements found for this placeholder
                    }

                    // Store the resolution for XML replacement
                    String resolvedLsk = tableName + ":" + columnName + ":" + valueToAssign;
                    pkResolutionMap.put(pkPlaceholder, resolvedLsk);
                    log.debug("Resolved PK '{}' -> LSK '{}' (Value: {}). Associated elements gathered for key {}.",
                            pkPlaceholder, resolvedLsk, valueToAssign, currentKey);

                } // End loop through unique PK placeholders
                log.debug("--- Finished LSK Resolution and Element Aggregation Phase ---");


                // --- Loop 2: Log Consolidated Ranges using aggregated elements ---
                log.info("Logging consolidated ranges and aggregated source elements to LskResolutionLog for user {}...", devEmail);
                for (Map.Entry<RangeKey, RangeInfo> rangeEntry : rangesProcessed.entrySet()) {
                    RangeKey key = rangeEntry.getKey();
                    RangeInfo range = rangeEntry.getValue();
                    // Get the complete list of aggregated elements for this key
                    List<String> aggregatedElements = elementsPerRangeKey.getOrDefault(key, Collections.emptyList());

                    logConsolidatedResolution(connection, devEmail,
                            range.getTableName(), range.getColumnName(), range.getModuleName(),
                            range.getFirstValue(), range.getLastValue(),
                            aggregatedElements); // Pass the fully aggregated list
                }
                log.info("Finished logging consolidated ranges.");

                // --- Commit Transaction ---
                connection.commit();
                transactionSuccess = true;
                log.debug("DB transaction committed successfully (incl. consolidated logs).");

            } catch (SQLException | LskGenerationException | PlaceholderFormatException | IllegalArgumentException e) {
                log.error("Error during LSK resolution transaction for user {}: {}", devEmail, e.getMessage(), e);
                if (e instanceof SQLException || e instanceof IllegalArgumentException) { throw new LskGenerationException("Error during LSK processing: "+e.getMessage(),e); }
                else { throw e; }
            } finally {
                if (connection != null) { try { if (!transactionSuccess) { log.warn("Rolling back DB transaction."); connection.rollback();} } catch (SQLException ex){log.error("Rollback failed",ex);}finally{ try { connection.setAutoCommit(true); connection.close();log.debug("DB conn closed."); } catch (SQLException e) {log.error("Conn close failed",e);} } }
            }
        } else {
            log.info("No PK placeholders found to resolve for user {}.", devEmail);
        }

        // --- Resolve Foreign Keys (remains the same) ---
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


        // --- Perform XML Replacements (remains the same) ---
        log.info("Performing final XML replacements in attribute values for user {}...", devEmail);
        String resolvedXml = XmlUtils.replaceAllPlaceholders(inputXml, finalReplacements);

        log.info("LSK/Reference resolution service finished successfully for user: {}", devEmail);
        return resolvedXml;
    }


    /**
     * Inserts a CONSOLIDATED log record for a processed range, including concatenated source XML elements.
     * (Method implementation remains the same as previous correct version)
     */
    private void logConsolidatedResolution(Connection conn, String email, String table, String column, String module,
                                           long startVal, long endVal, List<String> sourceElements)
            throws SQLException {

        String concatenatedElements = "";
        if (sourceElements != null && !sourceElements.isEmpty()) {
            concatenatedElements = sourceElements.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }

        int maxLogLength = 4000;
        String loggedElements = concatenatedElements;
        if (loggedElements.length() > maxLogLength) {
            log.warn("Source XML elements string length ({}) exceeds log limit ({}) for User={}, Key={}:{}:{}",
                    loggedElements.length(), maxLogLength, email, table, column, module);
            loggedElements = loggedElements.substring(0, maxLogLength) + "\n... (truncated)";
        }

        log.debug("Logging consolidated LSK range: User={}, Key={}:{}:{}, Range=[{}-{}], Elements:\n{}",
                email, table, column, module, startVal, endVal, loggedElements);

        try (PreparedStatement logStatement = conn.prepareStatement(INSERT_LOG_SQL)) {
            logStatement.setString(1, email);
            logStatement.setString(2, table);
            logStatement.setString(3, column);
            logStatement.setString(4, module);
            logStatement.setLong(5, startVal);
            logStatement.setLong(6, endVal);
            logStatement.setString(7, concatenatedElements); // Store full string

            int rowsAffected = logStatement.executeUpdate();
            if (rowsAffected != 1) {
                log.error("Failed to insert consolidated log record! Rows affected: {} for User={}, Key={}:{}:{}",
                        rowsAffected, email, table, column, module);
                throw new SQLException("Failed log insert, rows affected: " + rowsAffected);
            }
            log.trace("Successfully inserted consolidated log record for {}:{}:{}.", table, column, module);
        } catch (SQLException e) {
            log.error("SQL Exception inserting consolidated log for User={}, Key={}:{}:{}: {}",
                    email, table, column, module, e.getMessage(), e);
            throw e;
        }
    }
}