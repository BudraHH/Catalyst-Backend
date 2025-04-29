package com.budra.uvh.utils;

import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.ReferenceResolutionException; // New Exception
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*; // Import Set, List, Map, etc.
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class XmlUtils {
    private static final Logger log = LoggerFactory.getLogger(XmlUtils.class);

    // Pattern for standard LSK Placeholders (PKs)
    // Groups: 1=AttributeName, 2=FullPlaceholder(Table:Col:LogicalId) 3=TableName, 4=ColumnName, 5=LogicalId
    private static final Pattern LSK_ATTRIBUTE_PATTERN = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"(([a-zA-Z_]+):([a-zA-Z_]+):([^\"\\s:]+))\""
            // Attr Name      =   " (Full LSK Placeholder          ) "
            //                      Table   : Column   : LogicalId (anything not quote, space, or colon)
    );

    // Pattern for Reference Placeholders (FKs)
    // Groups: 1=AttributeName, 2=FullRefString(REF:{...}), 3=PK_Placeholder(inside braces)
    private static final Pattern REF_ATTRIBUTE_PATTERN = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"(REF:\\{([a-zA-Z_]+:[a-zA-Z_]+:[^\"\\s:]+)\\})\""
            // Attr Name      =   " (REF:{PK_Placeholder          }) "
            //                         Table : Column   : LogicalId (inside braces)
    );


    /**
     * Finds all unique Primary Key (LSK) placeholder strings within attribute values.
     * Format: "TableName:ColumnName:LogicalId"
     *
     * @param xmlContent The XML content as a string.
     * @return A Set of unique PK placeholder strings found (e.g., "Dept:ID:3", "Emp:ID:1001").
     * @throws PlaceholderFormatException if the structure is invalid.
     */
    public static Set<String> findUniquePkPlaceholders(String xmlContent) throws PlaceholderFormatException {
        Set<String> uniquePks = new HashSet<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return uniquePks;
        }

        Matcher matcher = LSK_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for PK placeholders...");
        while (matcher.find()) {
            try {
                String fullPkPlaceholder = matcher.group(2); // The full Table:Column:LogicalId
                String tableName = matcher.group(3);
                String columnName = matcher.group(4);
                String logicalId = matcher.group(5); // We don't use logicalId directly here, but capture helps validation

                if (tableName == null || tableName.trim().isEmpty() ||
                        columnName == null || columnName.trim().isEmpty() ||
                        logicalId == null || logicalId.trim().isEmpty()) {
                    throw new PlaceholderFormatException("Invalid PK placeholder structure found: " + matcher.group(0));
                }

                uniquePks.add(fullPkPlaceholder);
                log.trace("Found PK placeholder instance: {}", fullPkPlaceholder);

            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                log.error("Error parsing PK placeholder match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing PK placeholder structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique PK placeholders.", uniquePks.size());
        return uniquePks;
    }

    /**
     * Finds all Foreign Key (Reference) placeholder strings and the PK they target.
     * Format: "REF:{TableName:ColumnName:LogicalId}"
     *
     * @param xmlContent The XML content as a string.
     * @return A Map where the key is the full FK reference string (e.g., "REF:{Dept:ID:3}")
     *         and the value is the target PK placeholder string (e.g., "Dept:ID:3").
     * @throws PlaceholderFormatException if the structure is invalid.
     */
    public static Map<String, String> findFkReferences(String xmlContent) throws PlaceholderFormatException {
        Map<String, String> fkReferences = new HashMap<>(); // FullRefString -> TargetPKString
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return fkReferences;
        }

        Matcher matcher = REF_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for FK references...");
        while (matcher.find()) {
            try {
                String fullRefString = matcher.group(2); // The full REF:{...}
                String targetPkPlaceholder = matcher.group(3); // The PK placeholder inside {}

                // Basic validation on the extracted target PK format
                String[] parts = targetPkPlaceholder.split(":");
                if (parts.length < 3 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
                    throw new PlaceholderFormatException("Invalid target PK placeholder format inside REF: " + targetPkPlaceholder);
                }

                // Store the mapping: the full REF string points to the target PK string
                // If the same REF appears multiple times, this map will only store it once, which is usually fine
                // as they should all resolve to the same value eventually.
                if (!fkReferences.containsKey(fullRefString)) {
                    fkReferences.put(fullRefString, targetPkPlaceholder);
                    log.trace("Found FK reference: '{}' targeting PK '{}'", fullRefString, targetPkPlaceholder);
                }

            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                log.error("Error parsing FK reference match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing FK reference structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique FK references.", fkReferences.size());
        return fkReferences;
    }


    /**
     * Replaces all occurrences of placeholder strings (both PK and FK)
     * with their corresponding resolved LSK strings in the XML content.
     *
     * WARNING: Uses simple String.replace, which is NOT robust for XML.
     *
     * @param xmlContent       The original XML content string.
     * @param finalReplacements A Map where the key is the original placeholder string
     *                          (e.g., "DepartmentInfo:DEPT_ID:3" or "REF:{DepartmentInfo:DEPT_ID:3}")
     *                          and the value is the final resolved LSK string
     *                          (e.g., "DepartmentInfo:DEPT_ID:101").
     * @return The XML content string with replacements made.
     */
    public static String replaceAllPlaceholders(String xmlContent, Map<String, String> finalReplacements) {
        String currentContent = xmlContent;
        if (xmlContent == null || finalReplacements == null || finalReplacements.isEmpty()) {
            return xmlContent; // Nothing to replace
        }

        log.debug("Starting final placeholder replacement phase...");
        int replacementsMade = 0;

        // Iterate through the final map of replacements
        for (Map.Entry<String, String> entry : finalReplacements.entrySet()) {
            String originalPlaceholder = entry.getKey(); // Can be PK string or REF string
            String resolvedLsk = entry.getValue();       // The final resolved LSK

            // We need to replace these placeholders when they appear inside attribute quotes.
            String placeholderInQuotes = "\"" + originalPlaceholder + "\"";
            String resolvedInQuotes = "\"" + resolvedLsk + "\"";

            String beforeReplacement = currentContent;
            // Use replaceAll to catch all occurrences of this specific placeholder
            currentContent = currentContent.replace(placeholderInQuotes, resolvedInQuotes);

            if (!beforeReplacement.equals(currentContent)) {
                replacementsMade++; // Count distinct replacement keys processed
                log.trace("Replaced '{}' with '{}'", placeholderInQuotes, resolvedInQuotes);
            } else {
                // This might happen if the exact string wasn't found, despite being in the map.
                // Could indicate issues with the initial regex matching vs the actual content structure.
                log.warn("String replacement did not find exact match for attribute value: {}", placeholderInQuotes);
            }
        }

        log.debug("Finished replacement phase. Processed {} replacement mappings.", replacementsMade);
        // Note: The actual number of string replacements might be higher if a placeholder appeared multiple times.
        return currentContent;
    }

    // Private constructor
    private XmlUtils() {}
}