package com.budra.uvh.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for parsing the response from GitHub's /user API endpoint.
 * Captures essential user identifiers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubUserResponse {

    @JsonProperty("id")
    public Long id; // GitHub's unique numeric user ID (very stable identifier)

    @JsonProperty("login")
    public String login; // GitHub username (can change, but usually stable)

    @JsonProperty("email")
    public String email; // User's public email (may be null if hidden or not set)

    // Default constructor
    public GitHubUserResponse() {}

    // Add Getters/Setters if needed
}