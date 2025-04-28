package com.budra.uvh.parser;

import com.budra.uvh.exception.XmlParsingException;
import com.budra.uvh.model.ParsedRecord; // Use the generic record
import jakarta.inject.Singleton; // Parser is typically stateless, suitable for Singleton
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
// Import both SAXException and SAXParseException
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

// --- (Class documentation remains the same) ---
@Service
@Singleton
public class GenericXmlDataParser {

    private static final Logger log = LoggerFactory.getLogger(GenericXmlDataParser.class);
    private static final String REF_PREFIX = "REF:{";
    private static final String REF_SUFFIX = "}";
    private static final Pattern LSK_PLACEHOLDER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+:[a-zA-Z0-9_]+:.*$");
    private static final boolean USE_PARENT_PK_ATTR_NAME_FOR_IMPLICIT_FK = true;

    public GenericXmlDataParser() {
        log.debug("GenericXmlDataParser instance created (@Singleton).");
    }

    // --- (parseXml method documentation remains the same) ---
    public List<ParsedRecord> parseXml(InputStream xmlInputStream) throws XmlParsingException {
        if (xmlInputStream == null) {
            throw new XmlParsingException("XML InputStream cannot be null.");
        }

        List<ParsedRecord> allRecords = new ArrayList<>();
        log.debug("Starting XML parsing...");

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Optional: Configure factory for security
            // ... (security features as before) ...

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(xmlInputStream);
            document.getDocumentElement().normalize();

            Element rootElement = document.getDocumentElement();
            if (rootElement == null) {
                throw new XmlParsingException("XML document is empty or has no root element.");
            }

            log.debug("XML Document parsed successfully. Root element: <{}>", rootElement.getTagName());
            NodeList rootChildren = rootElement.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                processNode(rootChildren.item(i), null, allRecords);
            }

            log.info("Generic XML parsing complete. Found {} records.", allRecords.size());

        } catch (ParserConfigurationException e) {
            log.error("XML Parser configuration error.", e);
            throw new XmlParsingException("Internal parser configuration error.", e);
            // --- CORRECTED CATCH BLOCK ---
        } catch (SAXParseException e) { // Catch specific parse exception first
            // Now e.getLineNumber() and e.getColumnNumber() are available
            log.error("XML Syntax error during parsing. Line {}, Column {}: {}", e.getLineNumber(), e.getColumnNumber(), e.getMessage());
            // Include line/column in the exception message for better diagnosis
            throw new XmlParsingException("Invalid XML syntax at Line " + e.getLineNumber() + ", Column " + e.getColumnNumber() + ": " + e.getMessage(), e);
        } catch (SAXException e) { // Catch other, more general SAX errors
            log.error("General SAX error during parsing: {}", e.getMessage(), e);
            throw new XmlParsingException("XML processing error: " + e.getMessage(), e);
            // --- END CORRECTION ---
        } catch (IOException e) {
            log.error("I/O error reading XML input stream.", e);
            throw new XmlParsingException("Failed to read XML input stream.", e);
        } catch (XmlParsingException e) {
            log.error("Error during XML node processing logic.", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during XML parsing.", e);
            throw new XmlParsingException("An unexpected error occurred during parsing.", e);
        }
        return allRecords;
    }

    // --- processNode method remains the same as before ---
    private void processNode(Node node, ParsedRecord parentContextRecord, List<ParsedRecord> allRecords) {
        if (node.getNodeType() != Node.ELEMENT_NODE) { return; }
        Element element = (Element) node;
        String tagName = element.getTagName();
        if (tagName == null || tagName.trim().isEmpty()) { log.warn("Skipping element with empty tag name."); return; }
        ParsedRecord currentRecord = new ParsedRecord(tagName);
        log.trace("Processing element: <{}>", tagName);
        NamedNodeMap attributeMap = element.getAttributes();
        boolean explicitFkFoundForImplicitCheck = false;
        for (int i = 0; i < attributeMap.getLength(); i++) {
            Node attr = attributeMap.item(i);
            String attrName = attr.getNodeName();
            String attrValue = attr.getNodeValue().trim();
            if (attrValue.isEmpty()) { log.trace("  Skipping empty attribute: {}", attrName); continue; }
            if (LSK_PLACEHOLDER_PATTERN.matcher(attrValue).matches()) {
                if (currentRecord.getPkPlaceholder() != null) { throw new XmlParsingException("Primary key for child table is missing!"); }
                currentRecord.setPrimaryKeyInfo(attrName, attrValue);
                log.trace("  Found PK: {}='{}'", attrName, attrValue);
            } else if (attrValue.startsWith(REF_PREFIX) && attrValue.endsWith(REF_SUFFIX)) {
                String parentPlaceholder = attrValue.substring(REF_PREFIX.length(), attrValue.length() - REF_SUFFIX.length()).trim();
                if (parentPlaceholder.isEmpty()) { throw new XmlParsingException("Reference key for child table is missing!"); }
                currentRecord.addForeignKeyLink(attrName, parentPlaceholder);
                log.trace("  Found Explicit FK Ref: {} -> '{}'", attrName, parentPlaceholder);
                if (parentContextRecord != null && attrName.equals(determineImplicitFkAttrName(parentContextRecord))) { explicitFkFoundForImplicitCheck = true; }
            } else {
                currentRecord.addAttribute(attrName, attrValue);
                log.trace("  Found Attribute: {}='{}'", attrName, attrValue);
            }
        }
        if (parentContextRecord != null && parentContextRecord.getPkPlaceholder() != null) {
            String implicitFkAttrName = determineImplicitFkAttrName(parentContextRecord);
            if (implicitFkAttrName != null && !explicitFkFoundForImplicitCheck && !currentRecord.getForeignKeyLinks().containsKey(implicitFkAttrName)) {
                currentRecord.addForeignKeyLink(implicitFkAttrName, parentContextRecord.getPkPlaceholder());
                log.trace("  Added Implicit FK Ref via Nesting: {} -> '{}'", implicitFkAttrName, parentContextRecord.getPkPlaceholder());
                currentRecord.getAttributes().remove(implicitFkAttrName);
            }
        }
        if (currentRecord.getPkPlaceholder() == null) { log.debug("  Record <{}> has no LSK Primary Key defined in attributes.", tagName); }
        allRecords.add(currentRecord);
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) { processNode(childNodes.item(i), currentRecord, allRecords); }
    }

    // --- determineImplicitFkAttrName method remains the same ---
    private String determineImplicitFkAttrName(ParsedRecord parentRecord) {
        if (parentRecord == null || parentRecord.getPkAttributeName() == null) { return null; }
        if (USE_PARENT_PK_ATTR_NAME_FOR_IMPLICIT_FK) { return parentRecord.getPkAttributeName(); }
        else { log.warn("Implicit FK determination convention not fully implemented..."); return null; }
    }
}