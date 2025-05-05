package com.budra.uvh.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for representing a single email object from the GitHub /user/emails API response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubEmailResponse {

    @JsonProperty("email")
    public String email;

    @JsonProperty("primary")
    public boolean primary;

    @JsonProperty("verified")
    public boolean verified;

    @JsonProperty("visibility")
    public String visibility;

    // Default constructor
    public GitHubEmailResponse() {}

    // Getters if needed
    public String getEmail() { return email; }
    public boolean isPrimary() { return primary; }
    public boolean isVerified() { return verified; }
    public String getVisibility() { return visibility; }
}