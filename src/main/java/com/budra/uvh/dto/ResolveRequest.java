package com.budra.uvh.dto;

/**
 * DTO for receiving the JSON request body for the LSK resolve endpoint.
 */
public class ResolveRequest {
    public String xmlContent; // Field name must match the JSON key sent by the plugin

    // Default constructor
    public ResolveRequest() {}

    // Getters and Setters
    public String getXmlContent() {
        return xmlContent;
    }

    public void setXmlContent(String xmlContent) {
        this.xmlContent = xmlContent;
    }
}