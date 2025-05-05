package com.budra.uvh.dto;

/**
 * DTO for receiving the authorization code from the plugin
 * during the GitHub OAuth code exchange.
 */
public class GitHubCodeRequest {
    public String code;

    public GitHubCodeRequest() {}

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}