package com.budra.uvh.git; // Or your relevant package

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Ignore any properties in the JSON that we don't explicitly map
@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("token_type")
    private String tokenType;

    // Optional: Add fields for error responses if needed
    @JsonProperty("error")
    private String error;

    @JsonProperty("error_description")
    private String errorDescription;

    // --- Getters and Setters (or make fields public) ---

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public void setErrorDescription(String errorDescription) {
        this.errorDescription = errorDescription;
    }

    @Override
    public String toString() {
        return "GithubTokenResponse{" +
                "accessToken='" + (accessToken != null ? "[PRESENT]" : "null") + '\'' +
                ", scope='" + scope + '\'' +
                ", tokenType='" + tokenType + '\'' +
                ", error='" + error + '\'' +
                ", errorDescription='" + errorDescription + '\'' +
                '}';
    }
}