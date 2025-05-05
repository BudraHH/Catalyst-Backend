package com.budra.uvh.dto;

// Using Jackson annotations - add dependency if needed
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for parsing the response from GitHub's /login/oauth/access_token endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubTokenResponse {

    @JsonProperty("access_token")
    public String accessToken;

    @JsonProperty("scope")
    public String scope;

    @JsonProperty("token_type")
    public String tokenType;

    // --- Potential Error Fields ---
    @JsonProperty("error")
    public String error;

    @JsonProperty("error_description")
    public String errorDescription;

    @JsonProperty("error_uri")
    public String errorUri;

    public GitHubTokenResponse() {}
}