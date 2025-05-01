package com.budra.uvh.dto;

/**
 * DTO for receiving the authorization code from the plugin
 * during the GitHub OAuth code exchange.
 */
public class GitHubCodeRequest {
    public String code; // Field name must match the JSON key expected from the plugin

    // Default constructor needed for JSON deserialization libraries like Jackson/Gson
    public GitHubCodeRequest() {}

    // Getters and Setters (Optional, depending on library usage, but good practice)
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}