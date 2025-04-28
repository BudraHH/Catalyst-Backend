package com.budra.uvh.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ParsedRecord {
    private static final Logger log = LoggerFactory.getLogger(ParsedRecord.class);

    private final String xmlTagName;
    private String pkAttributeName;
    private String pkPlaceholder;

    private final Map<String, String> attributes = new HashMap<>();

    private final Map<String, String> foreignKeyLinks = new HashMap<>();

    private Long generatedPkValue;

    private final Map<String, Long> resolvedForeignKeys = new HashMap<>();

    public ParsedRecord(String xmlTagName) {
        this.xmlTagName = Objects.requireNonNull(xmlTagName, "XML Tag Name cannot be null");
    }

    public String getTableName() { return xmlTagName; }

    public String getPkAttributeName() { return pkAttributeName; }

    public String getPkPlaceholder() { return pkPlaceholder; }

    public Map<String, String> getAttributes() { return attributes; }

    public Map<String, String> getForeignKeyLinks() { return foreignKeyLinks; }

    public Long getGeneratedPkValue() { return generatedPkValue; }

    public Map<String, Long> getResolvedForeignKeys() { return resolvedForeignKeys; } // Consider returning an unmodifiable view

    // --- Setters/Adders used by Parser and Service ---

    public void setPrimaryKeyInfo(String attributeName, String placeholder) {
        if (this.pkAttributeName != null && !this.pkAttributeName.equals(attributeName)) {
            // Log or throw if multiple different PK attributes are identified
            log.warn("Multiple PK attributes detected for record <{}>. Overwriting existing PK '{}'='{}' with new PK '{}'='{}'.",
                    this.xmlTagName, this.pkAttributeName, this.pkPlaceholder, attributeName, placeholder);
            // Consider throwing new XmlParsingException(...) here for stricter validation
        } else if (this.pkAttributeName != null && this.pkAttributeName.equals(attributeName) && !Objects.equals(this.pkPlaceholder, placeholder)) {
            // Same attribute name but different placeholder value - potentially problematic
            log.warn("PK attribute '{}' for record <{}> has conflicting placeholder values detected ('{}' vs '{}'). Using the latest value '{}'.",
                    attributeName, this.xmlTagName, this.pkPlaceholder, placeholder, placeholder);
        }
        this.pkAttributeName = Objects.requireNonNull(attributeName, "PK Attribute Name cannot be null");
        this.pkPlaceholder = Objects.requireNonNull(placeholder, "PK Placeholder cannot be null");
    }

    /**
     * Adds a regular attribute (non-PK, non-FK-ref) found during parsing.
     * @param name Attribute name (column name).
     * @param value Attribute value (as string).
     */
    public void addAttribute(String name, String value) {
        Objects.requireNonNull(name, "Attribute name cannot be null");
        // Value can potentially be null if attribute exists but is empty, e.g., attr=""
        this.attributes.put(name, value);
    }

    /**
     * Adds a foreign key link identified during parsing (either explicit REF:{} or implicit nesting).
     * @param fkAttributeName The name of the attribute acting as the foreign key column.
     * @param parentPkPlaceholder The LSK placeholder string of the parent record being referenced.
     */
    public void addForeignKeyLink(String fkAttributeName, String parentPkPlaceholder) {
        Objects.requireNonNull(fkAttributeName, "FK Attribute Name cannot be null");
        Objects.requireNonNull(parentPkPlaceholder, "Parent PK Placeholder cannot be null for FK link");
        // Check for duplicates? Overwrite or throw? Overwriting is simpler.
        if (this.foreignKeyLinks.containsKey(fkAttributeName)) {
            log.trace("Overwriting existing FK link for attribute '{}' in record <{}>", fkAttributeName, this.xmlTagName);
        }
        this.foreignKeyLinks.put(fkAttributeName, parentPkPlaceholder);
    }

    /**
     * Stores the generated primary key value after LSK processing.
     * @param generatedPkValue The generated primary key value.
     */
    public void setGeneratedPkValue(Long generatedPkValue) {
        // Allow setting null? Might happen if LSK gen is skipped. Check for null before DB insert.
        this.generatedPkValue = generatedPkValue;
    }

    /**
     * Stores a resolved foreign key value after LSK processing and lookup.
     * @param fkAttributeName The name of the foreign key attribute/column.
     * @param parentGeneratedId The actual generated primary key ID of the referenced parent record.
     */
    public void addResolvedForeignKey(String fkAttributeName, Long parentGeneratedId) {
        Objects.requireNonNull(fkAttributeName, "FK Attribute Name cannot be null for resolved FK");
        Objects.requireNonNull(parentGeneratedId, "Parent Generated ID cannot be null for resolved FK");
        // Overwrite if called multiple times? Should ideally only be resolved once.
        if (this.resolvedForeignKeys.containsKey(fkAttributeName)) {
            log.warn("Overwriting existing resolved FK value for attribute '{}' in record <{}>", fkAttributeName, this.xmlTagName);
        }
        this.resolvedForeignKeys.put(fkAttributeName, parentGeneratedId);
    }

    @Override
    public String toString() {
        // Provide a concise summary for logging
        return "ParsedRecord{" +
                "table='" + xmlTagName + '\'' +
                (pkPlaceholder != null ? ", pk='" + pkPlaceholder + '\'' : "") +
                (generatedPkValue != null ? ", genPK=" + generatedPkValue : "") +
                ", attrs=" + attributes.size() +
                ", fkLinks=" + foreignKeyLinks.size() +
                ", resolvedFKs=" + resolvedForeignKeys.size() +
                '}';
    }


}