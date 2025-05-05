package com.budra.uvh.dto;

/**
 * DTO for receiving the JSON request body for the LSK resolve endpoint.
 */
public class ResolveRequest {
    private String moduleName;
    private String xmlContent;

    public ResolveRequest() {}

    // Getters
    public String getXmlContent() {
        return xmlContent;
    }
    public String getModuleName() { return moduleName; }

}