package com.budra.uvh.utils;

import com.budra.uvh.exception.PlaceholderFormatException;
// Removed unused ReferenceResolutionException import from this file
// import com.budra.uvh.exception.ReferenceResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*; // Keep Set, Map, HashMap, HashSet
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// Removed unused Collectors import
// import java.util.stream.Collectors;

public class XmlUtils {
    private static final Logger log = LoggerFactory.getLogger(XmlUtils.class);

    private static final Pattern LSK_ATTRIBUTE_PATTERN = Pattern.compile(
            // Attr Name =   " ( Full Placeholder String                                   ) "
            "([a-zA-Z_]+)\\s*=\\s*\"(([a-zA-Z_]+):([a-zA-Z_]+):([a-zA-Z_]+):([^\"\\s:]+))\""
            //                    Table Name : Column Name: Module Name: LogicalId (anything not quote, space, or colon)
    );

     private static final Pattern REF_ATTRIBUTE_PATTERN = Pattern.compile(
            // Attr Name =   " ( REF:{ Target Placeholder String                    } ) "
            "([a-zA-Z_]+)\\s*=\\s*\"(REF:\\{([a-zA-Z_]+:[a-zA-Z_]+:[a-zA-Z_]+:[^\"\\s:{}]+)\\})\""
            //                         Table Name:Column Name:Module Name:LogicalId (inside braces)
    );

    public static Set<String> findUniquePkPlaceholders(String xmlContent) throws PlaceholderFormatException {
        Set<String> uniquePks = new HashSet<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return uniquePks;
        }

        Matcher matcher = LSK_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for 4-part PK placeholders...");
        while (matcher.find()) {
            try {
                // --- Corrected Group Numbers ---
                // Group 0 is the whole match, e.g., attr="Table:Col:Mod:Log"
                // Group 1 is the attribute name, e.g., attr
                String fullPkPlaceholder = matcher.group(2); // The full Table:Col:Module:LogId
                String tableName = matcher.group(3);
                String columnName = matcher.group(4);
                String moduleName = matcher.group(5);
                String logicalId = matcher.group(6);
                // --- Updated Validation ---
                if (tableName == null || tableName.trim().isEmpty() ||
                        columnName == null || columnName.trim().isEmpty() ||
                        moduleName == null || moduleName.trim().isEmpty() || // <<< Added check for moduleName
                        logicalId == null || logicalId.trim().isEmpty()) {
                    throw new PlaceholderFormatException("Invalid PK placeholder structure (missing parts): " + matcher.group(0));
                }

                uniquePks.add(fullPkPlaceholder);
                log.trace("Found PK placeholder instance: {}", fullPkPlaceholder);

            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                // Catch potential regex group errors or internal validation issues
                log.error("Error parsing PK placeholder match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing PK placeholder structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique PK placeholders.", uniquePks.size());
        return uniquePks;
    }

    public static Map<String, String> findFkReferences(String xmlContent) throws PlaceholderFormatException {
        Map<String, String> fkReferences = new HashMap<>(); // FullRefString -> TargetPKString
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return fkReferences;
        }

        Matcher matcher = REF_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for 4-part FK references...");
        while (matcher.find()) {
            try {
                String fullRefString = matcher.group(2);
                String targetPkPlaceholder = matcher.group(3);
                // --- Updated Validation for 4 parts ---
                String[] parts = targetPkPlaceholder.split(":");
                // Check for exactly 4 non-empty parts after splitting by colon
                if (parts.length != 4 ||
                        parts[0].trim().isEmpty() || // Table
                        parts[1].trim().isEmpty() || // Column
                        parts[2].trim().isEmpty() || // Module
                        parts[3].trim().isEmpty()) { // LogicalId
                    throw new PlaceholderFormatException("Invalid target PK placeholder format inside REF (expected 4 parts): " + targetPkPlaceholder);
                }

                // Store the mapping if not already present
                if (!fkReferences.containsKey(fullRefString)) {
                    fkReferences.put(fullRefString, targetPkPlaceholder);
                    log.trace("Found FK reference: '{}' targeting PK '{}'", fullRefString, targetPkPlaceholder);
                }

            } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
                // Catch potential regex group errors or internal validation issues
                log.error("Error parsing FK reference match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing FK reference structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique FK references.", fkReferences.size());
        return fkReferences;
    }

    public static String replaceAllPlaceholders(String xmlContent, Map<String, String> finalReplacements) {
        String currentContent = xmlContent;
        if (xmlContent == null || finalReplacements == null || finalReplacements.isEmpty()) {
            return xmlContent; // Nothing to replace
        }

        log.debug("Starting final placeholder replacement phase...");
        int replacementsMade = 0;

        // Iterate through the final map of replacements
        for (Map.Entry<String, String> entry : finalReplacements.entrySet()) {
            String originalPlaceholder = entry.getKey(); // Can be "Table:Col:Mod:LogId" or "REF:{Table:Col:Mod:LogId}"
            String resolvedLsk = entry.getValue();       // Should be "Table:Col:Value"

            // Replace the placeholder when it appears inside attribute quotes.
            String placeholderInQuotes = "\"" + originalPlaceholder + "\"";
            String resolvedInQuotes = "\"" + resolvedLsk + "\"";

            String beforeReplacement = currentContent;

            currentContent = currentContent.replace(placeholderInQuotes, resolvedInQuotes);

            if (!beforeReplacement.equals(currentContent)) {
                replacementsMade++; // Count distinct replacement keys processed
                log.trace("Replaced '{}' with '{}'", placeholderInQuotes, resolvedInQuotes);
            } else {
                // This warning might occur if the exact quoted string wasn't found.
                log.warn("String replacement did not find exact match for attribute value: {}", placeholderInQuotes);
            }
        }

        log.debug("Finished replacement phase. Processed {} replacement mappings.", replacementsMade);
        return currentContent;
    }

    // Private constructor for utility class
    private XmlUtils() {}
}