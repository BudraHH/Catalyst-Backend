package com.budra.uvh.utils;

// Import custom exception
import com.budra.uvh.exception.PlaceholderFormatException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for XML related operations, specifically finding and replacing
 * LSK placeholders.
 *
 * WARNING: Uses basic Regex and String replacement which can be unreliable for complex XML.
 * A production system should use a proper XML parser (StAX, DOM4J, JAXB etc.).
 */
public class XmlUtils {
    private static final Logger log = LoggerFactory.getLogger(XmlUtils.class);

    // Updated Regex Pattern reflecting Table/Column naming constraints
    // Captures: 1=AttributeName, 2=TableName, 3=ColumnName, 4=PlaceholderSuffix
    private static final Pattern LSK_ATTRIBUTE_PATTERN = Pattern.compile(
            // Allowing underscore also in attribute name for flexibility (e.g., emp_id)
            "([a-zA-Z_]+)\\s*=\\s*\"([a-zA-Z_]+):([a-zA-Z_]+):(__PLACEHOLDER_[a-zA-Z0-9_-]+__)\""
            // Attr Name      =   " Table    : Column   : __PLACEHOLDER_Descriptor__ "
    );

    /**
     * Finds unique placeholder logical seed key strings within attribute values in the XML content.
     *
     * WARNING: Uses Regex, which is not a robust way to parse XML.
     *
     * @param xmlContent The XML content as a string.
     * @return A Map where the key is the full placeholder LSK string (e.g., "Dept:ID:__PLACEHOLDER_A__")
     *         and the value is a PlaceholderInfo object containing parsed components.
     * @throws PlaceholderFormatException if parsing fails based on the regex match or internal validation.
     */
    // --- FIX: Added throws clause back ---
    public static Map<String, PlaceHolderInfo> findUniquePlaceholders(String xmlContent) throws PlaceholderFormatException {
        Map<String, PlaceHolderInfo> placeholders = new HashMap<>();
        if (xmlContent == null || xmlContent.trim().isEmpty()) {
            log.warn("XML content provided for placeholder scanning is null or empty.");
            return placeholders; // Return empty map
        }

        Matcher matcher = LSK_ATTRIBUTE_PATTERN.matcher(xmlContent);
        log.debug("Scanning XML content for placeholders...");

        int count = 0;
        while (matcher.find()){
            try {
                // Groups match the capturing groups in the regex pattern
                String attributeName = matcher.group(1);
                String tableName = matcher.group(2);
                String columnName = matcher.group(3);
                String placeholderSuffix = matcher.group(4);

                PlaceHolderInfo info = new PlaceHolderInfo(tableName, columnName, placeholderSuffix);

                // Use the full LSK string as the key for the map
                String fullPlaceholderKey = info.getFullPlaceholderLsk();
                if(!placeholders.containsKey(fullPlaceholderKey)){
                    placeholders.put(fullPlaceholderKey, info);
                    count++;
                    log.trace("Found unique placeholder: {}", fullPlaceholderKey);
                } else {
                    log.trace("Found duplicate placeholder instance (already collected): {}", fullPlaceholderKey);
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                // Catch errors during parsing/validation (e.g., from PlaceholderInfo constructor)
                log.error("Error parsing potential placeholder match: {}", matcher.group(0), e);
                // --- FIX: Re-throw the exception ---
                throw new PlaceholderFormatException("Error parsing placeholder structure near: '" + matcher.group(0) + "'. Reason: " + e.getMessage(), e);
            }
        }

        log.debug("Finished scanning. Found {} unique placeholders.", count);
        return placeholders;
    }

    /**
     * Replaces all occurrences of placeholder LSK strings (within attribute quotes)
     * with their corresponding resolved LSK strings in the XML content.
     *
     * WARNING: Uses simple String.replace, which is NOT robust for XML.
     *
     * @param xmlContent       The original XML content string.
     * @param resolvedMappings A Map where the key is the full placeholder LSK string
     *                         and the value is the full resolved LSK string.
     * @return The XML content string with replacements made.
     */
    public static String replacePlaceholders(String xmlContent, Map<String, String> resolvedMappings){
        String currentContent = xmlContent;
        if (xmlContent == null || resolvedMappings == null || resolvedMappings.isEmpty()) {
            return xmlContent; // Nothing to replace
        }

        log.debug("Starting placeholder replacement...");
        int replacements = 0;

        for(Map.Entry<String,String> entry : resolvedMappings.entrySet()){
            String placeholderLsk = entry.getKey(); // e.g., "DepartmentInfo:DEPT_ID:__PLACEHOLDER_HR_BenefitsDept__"
            String resolvedLsk = entry.getValue();    // e.g., "DepartmentInfo:DEPT_ID:7"

            // Need to match the placeholder within quotes in the attribute value
            String placeholderInQuotes = "\"" + placeholderLsk + "\"";
            String resolvedInQuotes = "\"" + resolvedLsk + "\"";

            // Perform simple string replacement
            String beforeReplacement = currentContent;
            currentContent = currentContent.replace(placeholderInQuotes, resolvedInQuotes);

            // Log if a replacement actually occurred based on the map entry
            if(!beforeReplacement.equals(currentContent)){
                replacements++;
                log.trace("Replaced '{}' with '{}'", placeholderInQuotes, resolvedInQuotes);
            } else {
                // This warning indicates the placeholder key from the map wasn't found
                // exactly as expected (within quotes) in the current XML content string.
                // This could happen if the XML was malformed or the string replace is failing.
                log.warn("Simple string replace did not find exact match for placeholder attribute value: {}", placeholderInQuotes);
            }
        }
        log.debug("Finished replacement. Attempted {} replacements based on map size {}.", replacements, resolvedMappings.size());
        return currentContent;
    }

    // Private constructor for utility class
    private XmlUtils(){};
}