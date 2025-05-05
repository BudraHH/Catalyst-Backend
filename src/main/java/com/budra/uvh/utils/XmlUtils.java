package com.budra.uvh.utils;

import com.budra.uvh.exception.PlaceholderFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// <<< Import LinkedHashMap >>>
import java.util.LinkedHashMap;
import java.util.*; // Keep other imports
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlUtils {
    private static final Logger log = LoggerFactory.getLogger(XmlUtils.class);

    // Group 1: Full Tag (<.../> or <...>)
    // Group 2: Tag Name
    // Group 3: Full 3-part PK placeholder (Table:Column:LogicalId)
    // Group 4: Table Name ([a-zA-Z_0-9]+ allows letters, numbers, underscore)
    // Group 5: Column Name ([a-zA-Z_0-9]+ allows letters, numbers, underscore)
    // Group 6: Logical ID ([^"\s:]+ allows almost anything except quote, space, colon)
    private static final Pattern ELEMENT_WITH_LSK_PATTERN = Pattern.compile(
            "(<"                                        // Start tag <
                    + "([\\w_:-]+)"                     // Group 2: Tag Name (letters, numbers, _, :, -)
                    + "\\s+"                            // Space
                    + "[^>]*?"                          // Preceding attributes (non-greedy)
                    + "[\\w_:-]+\\s*=\\s*\""            // An attribute name = "
                                                         // --- Capture 3 parts: Tbl, Col are alphanumeric+_, Lid is broader ---
                    + "(([a-zA-Z_0-9]+):([a-zA-Z_0-9]+):([^\"\\s:]+))" // Group 3(PK=T:C:Lid), 4(Tbl), 5(Col), 6(Lid)
                    + "\""                              // Closing quote
                    + "[^>]*?"                          // Subsequent attributes (non-greedy)
                    + "/?>"                             // Closing /> or >
                    + ")"                               // End Group 1 (Full Tag)
            , Pattern.CASE_INSENSITIVE
    );

    // Group 1: Attribute Name
    // Group 2: Full REF string "REF:{T:C:Lid}" or "REF:{T:C:Value}"
    // Group 3: Target 3-part placeholder (T:C:Lid or T:C:Value)
    // Group 4: Target Table Name
    // Group 5: Target Column Name
    // Group 6: Target Logical ID or Value
    private static final Pattern REF_ATTRIBUTE_PATTERN = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\""        // Attr name = "
                    // --- Capture 3 parts inside REF: Tbl, Col are alphanumeric+_, Lid/Val is broader ---
                    + "(REF:\\{(([a-zA-Z_0-9]+):([a-zA-Z_0-9]+):([^\"\\s:{}]+))\\})" // Grp 2(Full REF), 3(Target T:C:Lid/Val), 4(Tbl), 5(Col), 6(Lid/Val)
                    + "\""                           // Closing quote
    );

    /**
     * Finds unique PK placeholders and maps them to the list of full XML opening/self-closing
     * tag strings where they were found, PRESERVING FIND ORDER.
     *
     * @param xmlContent The input XML string.
     * @return A LinkedHashMap where Key = PK Placeholder String, Value = List of element strings.
     *         Iteration order reflects the order placeholders were found.
     * @throws PlaceholderFormatException If an invalid placeholder format is detected.
     */
    public static Map<String, List<String>> findPkPlaceholdersWithElements(String xmlContent) throws PlaceholderFormatException {
        // <<< Use LinkedHashMap to preserve insertion (find) order >>>
        Map<String, List<String>> pkElementMap = new LinkedHashMap<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return pkElementMap;
        }

        Matcher matcher = ELEMENT_WITH_LSK_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for elements containing 3-part PK placeholders...");

        while (matcher.find()) {
            try {
                String fullElementTag = matcher.group(1);
                String fullPkPlaceholder = matcher.group(3); // The full Table:Col:Module:LogId
                String tableName = matcher.group(4);
                String columnName = matcher.group(5);
//                String moduleName = matcher.group(6);
                String logicalId = matcher.group(6);

                if (tableName == null || tableName.isEmpty() ||
                        columnName == null || columnName.isEmpty() ||
//                        moduleName == null || moduleName.isEmpty() ||
                        logicalId == null || logicalId.isEmpty()) {
                    throw new PlaceholderFormatException("Invalid PK placeholder structure: " + fullPkPlaceholder);
                }

                // Add using computeIfAbsent, LinkedHashMap preserves order of first insertion
                pkElementMap.computeIfAbsent(fullPkPlaceholder, k -> new ArrayList<>()).add(fullElementTag);
                log.trace("Found PK placeholder '{}' within element tag: {}", fullPkPlaceholder, fullElementTag.length() > 100 ? fullElementTag.substring(0, 100) + "..." : fullElementTag);

            } catch (Exception e) {
                log.error("Error parsing PK placeholder/element match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error processing PK placeholder structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Finished PK element scan. Found {} unique placeholders mapped to elements (order preserved).", pkElementMap.size());
        return pkElementMap; // Returns LinkedHashMap
    }

    /**
     * Finds unique FK references and maps them to the target PK placeholder, PRESERVING FIND ORDER.
     *
     * @param xmlContent The input XML.
     * @return A LinkedHashMap where Key = Full FK Reference String, Value = Target PK Placeholder string.
     * @throws PlaceholderFormatException If format is invalid.
     */
    public static Map<String, String> findFkReferences(String xmlContent) throws PlaceholderFormatException {
        // <<< Use LinkedHashMap to preserve insertion (find) order >>>
        Map<String, String> fkReferences = new LinkedHashMap<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return fkReferences;
        }
        Matcher matcher = REF_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for 4-part FK references...");
        while (matcher.find()) {
            try {
                String fullRefString = matcher.group(2); // REF:{Target}
                String targetPkPlaceholder = matcher.group(3); // Target
                String[] parts = targetPkPlaceholder.split(":");
                if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                    throw new PlaceholderFormatException("Invalid target PK placeholder format inside REF: " + targetPkPlaceholder);
                }
                // Use putIfAbsent to only store the first time a unique REF string is encountered
                fkReferences.putIfAbsent(fullRefString, targetPkPlaceholder);
                log.trace("Found FK reference: '{}' targeting PK '{}'", fullRefString, targetPkPlaceholder);

            } catch (Exception e) {
                log.error("Error parsing FK reference match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing FK reference structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique FK references (order preserved).", fkReferences.size());
        return fkReferences; // Returns LinkedHashMap
    }

    // replaceAllPlaceholders remains the same
    public static String replaceAllPlaceholders(String xmlContent, Map<String, String> finalReplacements) {
        // ... (no changes needed here) ...
        String currentContent = xmlContent;
        if (xmlContent == null || finalReplacements == null || finalReplacements.isEmpty()) { return xmlContent; }
        log.debug("Starting final placeholder replacement phase...");
        int replacementsMade = 0;
        // Iteration order of finalReplacements *might* matter if replacements overlap,
        // but typically doesn't for simple LSKs. Using LinkedHashMap ensures consistency.
        for (Map.Entry<String, String> entry : finalReplacements.entrySet()) {
            String originalPlaceholder = entry.getKey();
            String resolvedLsk = entry.getValue();
            String placeholderInQuotes = "\"" + originalPlaceholder + "\"";
            String resolvedInQuotes = "\"" + resolvedLsk + "\"";
            String beforeReplacement = currentContent;
            currentContent = currentContent.replace(placeholderInQuotes, resolvedInQuotes);
            if (!beforeReplacement.equals(currentContent)) { replacementsMade++; log.trace("Replaced '{}' with '{}'", placeholderInQuotes, resolvedInQuotes); }
            else { log.warn("String replacement did not find exact match for attribute value: {}", placeholderInQuotes); }
        }
        log.debug("Finished replacement phase. Processed {} replacement mappings.", replacementsMade);
        return currentContent;
    }

    private XmlUtils() {}
}