package com.budra.uvh.utils;

import com.budra.uvh.exception.PlaceholderFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlUtils {
    private static final Logger log = LoggerFactory.getLogger(XmlUtils.class);

    // Regex to find an opening or self-closing tag containing an LSK placeholder attribute.
    // Assumptions: Simple XML structure, placeholder attribute is in opening tag, attribute values don't contain '>'.
    // Group 1: The full opening or self-closing tag string (e.g., <DepartmentInfo dept_id="..." dept_name="..."/>)
    // Group 2: Tag name (e.g., DepartmentInfo)
    // Group 3: The full PK placeholder string (e.g., DepartmentInfo:DEPT_ID:ORG:DEPT_A)
    // Group 4: Table Name (e.g., DepartmentInfo)
    // Group 5: Column Name (e.g., DEPT_ID)
    // Group 6: Module Name (e.g., ORG)
    // Group 7: Logical ID (e.g., DEPT_A)
    private static final Pattern ELEMENT_WITH_LSK_PATTERN = Pattern.compile(
            "(<"                                // Start of opening tag <
                    + "([\\w_:-]+)"                     // Group 2: Tag Name (allow letters, numbers, _, :, -)
                    + "\\s+"                            // At least one space after tag name
                    + "[^>]*?"                          // Any characters except > (non-greedy) - preceding attributes
                    + "[\\w_:-]+\\s*=\\s*\""            // An attribute name = "
                    + "(([a-zA-Z_]+):([a-zA-Z_]+):([a-zA-Z_]+):([^\"\\s:]+))" // Group 3 PK placeholder, 4 Tbl, 5 Col, 6 Mod, 7 LogId
                    + "\""                              // Closing quote of the placeholder attribute
                    + "[^>]*?"                          // Any characters except > (non-greedy) - subsequent attributes
                    + "/?>"                             // Closing /> or >
                    + ")"                               // End of Group 1 (full tag capture)
            , Pattern.CASE_INSENSITIVE          // Ignore case for tag/attribute names if desired
    );


    // Pattern to find the REF placeholder attribute remains the same
    private static final Pattern REF_ATTRIBUTE_PATTERN = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"(REF:\\{([a-zA-Z_]+:[a-zA-Z_]+:[a-zA-Z_]+:[^\"\\s:{}]+)\\})\""
    );

    /**
     * Finds unique PK placeholders and maps them to the list of full XML opening/self-closing
     * tag strings where they were found.
     *
     * @param xmlContent The input XML string.
     * @return A Map where Key = PK Placeholder String (Table:Column:Module:LogicalId),
     *         Value = List of full XML element tag strings (<Tag ... attr="Placeholder" ... /> or <Tag ... attr="Placeholder" ... >)
     *         containing that placeholder. List might be empty if element capture failed.
     * @throws PlaceholderFormatException If an invalid placeholder format is detected.
     */
    public static Map<String, List<String>> findPkPlaceholdersWithElements(String xmlContent) throws PlaceholderFormatException {
        Map<String, List<String>> pkElementMap = new HashMap<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            return pkElementMap;
        }

        Matcher matcher = ELEMENT_WITH_LSK_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML for elements containing 4-part PK placeholders...");

        while (matcher.find()) {
            try {
                String fullElementTag = matcher.group(1);       // The captured <Element ... /> or <Element ... >
                String tagName = matcher.group(2);            // The element's tag name
                String fullPkPlaceholder = matcher.group(3);    // The full Table:Col:Module:LogId
                String tableName = matcher.group(4);
                String columnName = matcher.group(5);
                String moduleName = matcher.group(6);
                String logicalId = matcher.group(7);

                // Basic validation on extracted placeholder parts (can be enhanced)
                if (tableName == null || tableName.isEmpty() || columnName == null || columnName.isEmpty() ||
                        moduleName == null || moduleName.isEmpty() || logicalId == null || logicalId.isEmpty()) {
                    // This shouldn't happen if the regex matched correctly, but good safety check
                    log.warn("Regex matched element but placeholder parts seem invalid: {}", fullElementTag);
                    throw new PlaceholderFormatException("Invalid PK placeholder structure detected within regex match: " + fullPkPlaceholder);
                }

                // Add the found element tag to the list associated with this placeholder
                // computeIfAbsent ensures the list is created if it's the first time seeing this placeholder
                pkElementMap.computeIfAbsent(fullPkPlaceholder, k -> new ArrayList<>()).add(fullElementTag);

                log.trace("Found PK placeholder '{}' within element tag: {}", fullPkPlaceholder, fullElementTag.length() > 100 ? fullElementTag.substring(0, 100) + "..." : fullElementTag);

            } catch (Exception e) { // Catch regex group errors or other unexpected issues
                log.error("Error parsing PK placeholder/element match: {}", matcher.group(0), e);
                // Decide if processing should continue or fail completely
                throw new PlaceholderFormatException("Error processing PK placeholder structure near element match: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Finished PK element scan. Found {} unique placeholders mapped to element lists.", pkElementMap.size());
        return pkElementMap;
    }

    // findFkReferences remains unchanged as it only needs the attribute value mapping
    public static Map<String, String> findFkReferences(String xmlContent) throws PlaceholderFormatException {
        Map<String, String> fkReferences = new HashMap<>(); // FullRefString -> TargetPKString
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
                if (parts.length != 4 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) {
                    throw new PlaceholderFormatException("Invalid target PK placeholder format inside REF (expected 4 parts): " + targetPkPlaceholder);
                }
                if (!fkReferences.containsKey(fullRefString)) {
                    fkReferences.put(fullRefString, targetPkPlaceholder);
                    log.trace("Found FK reference: '{}' targeting PK '{}'", fullRefString, targetPkPlaceholder);
                }
            } catch (Exception e) {
                log.error("Error parsing FK reference match: {}", matcher.group(0), e);
                throw new PlaceholderFormatException("Error parsing FK reference structure near: '" + matcher.group(0) + "'", e);
            }
        }
        log.debug("Found {} unique FK references.", fkReferences.size());
        return fkReferences;
    }

    // replaceAllPlaceholders remains unchanged as it replaces attribute values
    public static String replaceAllPlaceholders(String xmlContent, Map<String, String> finalReplacements) {
        String currentContent = xmlContent;
        if (xmlContent == null || finalReplacements == null || finalReplacements.isEmpty()) {
            return xmlContent;
        }
        log.debug("Starting final placeholder replacement phase...");
        int replacementsMade = 0;
        for (Map.Entry<String, String> entry : finalReplacements.entrySet()) {
            String originalPlaceholder = entry.getKey();
            String resolvedLsk = entry.getValue();
            String placeholderInQuotes = "\"" + originalPlaceholder + "\"";
            String resolvedInQuotes = "\"" + resolvedLsk + "\"";
            String beforeReplacement = currentContent;
            currentContent = currentContent.replace(placeholderInQuotes, resolvedInQuotes);
            if (!beforeReplacement.equals(currentContent)) {
                replacementsMade++;
                log.trace("Replaced '{}' with '{}'", placeholderInQuotes, resolvedInQuotes);
            } else {
                log.warn("String replacement did not find exact match for attribute value: {}", placeholderInQuotes);
            }
        }
        log.debug("Finished replacement phase. Processed {} replacement mappings.", replacementsMade);
        return currentContent;
    }

    private XmlUtils() {}
}