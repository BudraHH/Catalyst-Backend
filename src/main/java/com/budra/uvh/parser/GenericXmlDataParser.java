package com.budra.uvh.parser;

import com.budra.uvh.exception.XmlParsingException;
import com.budra.uvh.model.ParsedRecord; // Use the generic record
// --- REMOVE DI Annotations ---
// import jakarta.inject.Singleton;
// import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses an XML InputStream into a list of generic ParsedRecord objects.
 * Handles nested elements, attributes, LSK placeholders (PK), and REF placeholders (FK).
 */
// NO Scope or Service annotation - Instantiation managed by AbstractBinder
public class GenericXmlDataParser {

    private static final Logger log = LoggerFactory.getLogger(GenericXmlDataParser.class);
    // Constants for parsing logic
    private static final String REF_PREFIX = "REF:{";
    private static final String REF_SUFFIX = "}";
    // Basic pattern for LSK placeholders like Table:Column:Key (key part can be anything)
    private static final Pattern LSK_PLACEHOLDER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+:[a-zA-Z0-9_]+:.*$");
    // Configuration flag for implicit FK convention
    private static final boolean USE_PARENT_PK_ATTR_NAME_FOR_IMPLICIT_FK = true;

    // --- Public No-Arg Constructor ---
    // Required by AbstractBinder for direct binding when defined as Singleton.
    public GenericXmlDataParser() {
        log.debug("GenericXmlDataParser instance created (manual binding).");
    }

    /**
     * Parses the given XML InputStream into a flat list of ParsedRecord objects.
     *
     * @param xmlInputStream The InputStream containing the XML data.
     * @return A List of ParsedRecord objects representing the XML structure.
     * @throws XmlParsingException If parsing fails due to syntax errors, I/O issues, or configuration problems.
     */
    public List<ParsedRecord> parseXml(InputStream xmlInputStream) throws XmlParsingException {
        if (xmlInputStream == null) {
            throw new XmlParsingException("XML InputStream cannot be null.");
        }

        List<ParsedRecord> allRecords = new ArrayList<>();
        log.debug("Starting XML parsing...");

        try {
            // --- DOM Parser Setup ---
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // TODO: Configure factory for security against XXE, etc. in production
            // factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // Consider disabling external entities

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(xmlInputStream); // Parses the entire stream into memory
            document.getDocumentElement().normalize(); // Recommended practice

            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new XmlParsingException("XML document is empty or lacks a root element.");
            }

            log.debug("XML Document parsed successfully. Root element: <{}>", rootElement.getTagName());

            // --- Start Recursive Node Processing ---
            NodeList rootChildren = rootElement.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                // Start processing from children of the root, passing null as initial parent context
                processNode(rootChildren.item(i), null, allRecords);
            }

            log.info("Generic XML parsing complete. Generated {} ParsedRecord objects.", allRecords.size());

            // --- Exception Handling ---
        } catch (ParserConfigurationException e) {
            log.error("XML Parser configuration error.", e);
            throw new XmlParsingException("Internal parser setup error.", e);
        } catch (SAXParseException e) {
            // Provide specific line/column info for syntax errors
            String errorDetails = String.format("Invalid XML syntax at Line %d, Column %d: %s",
                    e.getLineNumber(), e.getColumnNumber(), e.getMessage());
            log.error("XML Syntax error during parsing: {}", errorDetails);
            throw new XmlParsingException(errorDetails, e);
        } catch (SAXException e) {
            log.error("General SAX error during XML parsing: {}", e.getMessage(), e);
            throw new XmlParsingException("XML processing error: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("I/O error reading XML input stream.", e);
            throw new XmlParsingException("Failed to read XML input.", e);
        } catch (XmlParsingException e) { // Catch exceptions thrown from processNode
            log.error("Error during XML node processing logic: {}", e.getMessage());
            throw e; // Re-throw
        } catch (Exception e) { // Catch-all for truly unexpected issues
            log.error("Unexpected error during XML parsing process.", e);
            throw new XmlParsingException("An unexpected error occurred during parsing.", e);
        }
        return allRecords;
    }

    /**
     * Recursively processes a DOM Node, creating ParsedRecord objects for Elements
     * and establishing parent-child relationships for implicit FK detection.
     *
     * @param node                The current DOM Node to process.
     * @param parentContextRecord The ParsedRecord of the parent Element, or null for top-level nodes.
     * @param allRecords          The master list where all created ParsedRecords are added.
     */
    private void processNode(Node node, ParsedRecord parentContextRecord, List<ParsedRecord> allRecords) {
        // Only process Element nodes
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return;
        }

        Element element = (Element) node;
        String tagName = element.getTagName();

        // Skip elements with no tag name (shouldn't usually happen with valid XML)
        if (tagName == null || tagName.trim().isEmpty()) {
            log.warn("Skipping element with empty/null tag name.");
            return;
        }

        // Create a new record for this element
        ParsedRecord currentRecord = new ParsedRecord(tagName);
        log.trace("Processing element: <{}>", tagName);

        // Process Attributes
        NamedNodeMap attributeMap = element.getAttributes();
        boolean explicitFkFoundForImplicitCheck = false; // Track if an explicit REF matches potential implicit FK name

        for (int i = 0; i < attributeMap.getLength(); i++) {
            Node attr = attributeMap.item(i);
            String attrName = attr.getNodeName();
            String attrValue = attr.getNodeValue().trim(); // Trim whitespace from attribute value

            // Skip attributes with empty values after trimming
            if (attrValue.isEmpty()) {
                log.trace("  Skipping empty attribute: {}", attrName);
                continue;
            }

            // Check for LSK Placeholder (Primary Key)
            if (LSK_PLACEHOLDER_PATTERN.matcher(attrValue).matches()) {
                // Basic check - assumes only one PK attribute per element
                if (currentRecord.getPkPlaceholder() != null) {
                    // Log a warning or throw an exception if multiple PKs are defined
                    log.warn("Multiple LSK placeholders found for element <{}>. Using the last one found ('{}').", tagName, attrName);
                    // Or: throw new XmlParsingException("Multiple primary key attributes defined for element <" + tagName + ">");
                }
                currentRecord.setPrimaryKeyInfo(attrName, attrValue);
                log.trace("  Found PK: {}='{}'", attrName, attrValue);
            }
            // Check for Explicit Foreign Key Reference (REF:{...})
            else if (attrValue.startsWith(REF_PREFIX) && attrValue.endsWith(REF_SUFFIX)) {
                String parentPlaceholder = attrValue.substring(REF_PREFIX.length(), attrValue.length() - REF_SUFFIX.length()).trim();
                if (parentPlaceholder.isEmpty()) {
                    // Or: throw new XmlParsingException("Empty reference placeholder found in attribute '" + attrName + "' for element <" + tagName + ">");
                    log.warn("Empty REF placeholder found in attribute '{}' for element <{}>. Skipping FK link.", attrName, tagName);
                    continue; // Skip this potential FK
                }
                currentRecord.addForeignKeyLink(attrName, parentPlaceholder);
                log.trace("  Found Explicit FK Ref: {} -> '{}'", attrName, parentPlaceholder);

                // Check if this explicit FK matches the name convention for implicit FKs from the parent
                if (parentContextRecord != null && attrName.equals(determineImplicitFkAttrName(parentContextRecord))) {
                    explicitFkFoundForImplicitCheck = true;
                }
            }
            // Regular Attribute
            else {
                currentRecord.addAttribute(attrName, attrValue);
                log.trace("  Found Attribute: {}='{}'", attrName, attrValue);
            }
        }

        // --- Handle Implicit Foreign Keys (based on nesting) ---
        if (parentContextRecord != null && parentContextRecord.getPkPlaceholder() != null) {
            String implicitFkAttrName = determineImplicitFkAttrName(parentContextRecord);
            // Add implicit FK only if:
            // 1. We determined a conventional name for it (implicitFkAttrName != null)
            // 2. We didn't already find an explicit REF:{} with the same name
            // 3. This element doesn't already have an FK link defined with that name (redundant check, but safe)
            if (implicitFkAttrName != null && !explicitFkFoundForImplicitCheck && !currentRecord.getForeignKeyLinks().containsKey(implicitFkAttrName)) {
                currentRecord.addForeignKeyLink(implicitFkAttrName, parentContextRecord.getPkPlaceholder());
                log.trace("  Added Implicit FK Ref via Nesting: {} -> '{}'", implicitFkAttrName, parentContextRecord.getPkPlaceholder());
                // Optional: Remove the attribute if it was also added as a regular attribute erroneously
                // currentRecord.getAttributes().remove(implicitFkAttrName);
            }
        }

        // Log if no PK was found for this record
        if (currentRecord.getPkPlaceholder() == null) {
            log.debug("  Record <{}> has no LSK Primary Key defined in attributes.", tagName);
        }

        // Add the processed record to the master list
        allRecords.add(currentRecord);

        // --- Recursively Process Child Nodes ---
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            // Pass the *current* record as the parent context for its children
            processNode(childNodes.item(i), currentRecord, allRecords);
        }
    }

    /**
     * Determines the expected attribute name for an implicit foreign key based on the parent record.
     * Current simple convention: Use the parent's PK attribute name.
     *
     * @param parentRecord The ParsedRecord of the parent element.
     * @return The expected implicit FK attribute name, or null if conventions aren't met.
     */
    private String determineImplicitFkAttrName(ParsedRecord parentRecord) {
        if (parentRecord == null || parentRecord.getPkAttributeName() == null) {
            return null; // Cannot determine implicit FK without parent PK info
        }

        if (USE_PARENT_PK_ATTR_NAME_FOR_IMPLICIT_FK) {
            // Convention: Child's FK column name = Parent's PK column name
            return parentRecord.getPkAttributeName();
        } else {
            // Implement other conventions if needed (e.g., parentTagName + "_id")
            log.warn("Implicit FK determination convention not fully implemented or disabled...");
            return null;
        }
    }
}