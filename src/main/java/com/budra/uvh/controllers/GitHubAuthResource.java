package com.budra.uvh.controllers;

import com.budra.uvh.config.SecretsManager;
import com.budra.uvh.dto.AuthResponse; // Your existing DTO for plugin response
import com.budra.uvh.dto.GitHubCodeRequest;
import com.budra.uvh.dto.GitHubTokenResponse;
import com.budra.uvh.dto.GitHubUserResponse;
import com.budra.uvh.dto.GitHubEmailResponse; // <<< Import for email DTO
import com.budra.uvh.dto.MessageResponse;
import com.fasterxml.jackson.core.type.TypeReference; // <<< Import for List<T>
import com.fasterxml.jackson.databind.ObjectMapper; // Using Jackson

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List; // <<< Import for email list
import java.util.Map;
import java.util.UUID;

@Path("/auth/github") // Defines the base path for this resource
@Produces(MediaType.APPLICATION_JSON) // Default response type is JSON
public class GitHubAuthResource {

    private static final Logger log = LoggerFactory.getLogger(GitHubAuthResource.class);
    private static final String GITHUB_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_ENDPOINT = "https://api.github.com/user";
    private static final String GITHUB_EMAILS_ENDPOINT = "https://api.github.com/user/emails"; // <<< New Endpoint URL

    private final HttpClient httpClient; // Reusable HTTP client
    private final ObjectMapper objectMapper; // Reusable JSON mapper

    // Constructor: Initialize reusable objects
    public GitHubAuthResource() {
        log.debug("GitHubAuthResource instance created.");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)) // Set connection timeout
                .followRedirects(HttpClient.Redirect.NEVER) // Don't follow redirects automatically
                .build();
        this.objectMapper = new ObjectMapper(); // Create a new ObjectMapper instance
    }

    @POST // Handles POST requests
    @Path("/exchange-code") // Handles requests to /api/auth/github/exchange-code
    @Consumes(MediaType.APPLICATION_JSON) // Expects JSON input
    public Response exchangeCode(GitHubCodeRequest request) {
        log.info("Received request to exchange GitHub code.");

        // --- Input Validation ---
        if (request == null || request.getCode() == null || request.getCode().trim().isEmpty()) {
            log.warn("Exchange code request missing code.");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse("Missing 'code' in request body."))
                    .build();
        }
        String receivedCode = request.getCode();

        // --- Get Configuration ---
        String clientId = SecretsManager.getGithubClientId();
        String clientSecret = SecretsManager.getGithubClientSecret();

        if (clientId == null || clientSecret == null) {
            log.error("GitHub client ID or secret not configured on backend via environment variables.");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse("Server configuration error [GitHub Auth]."))
                    .build();
        }

        try {
            // --- Step 1: Exchange authorization code for GitHub access token ---
            log.debug("Attempting to fetch GitHub access token...");
            GitHubTokenResponse tokenResponse = fetchGitHubAccessToken(receivedCode, clientId, clientSecret);

            // Check for errors returned by GitHub in the token response
            if (tokenResponse.error != null) {
                log.warn("GitHub returned an error during token exchange: {} - {}", tokenResponse.error, tokenResponse.errorDescription);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new MessageResponse("GitHub authentication error: " + tokenResponse.errorDescription))
                        .build();
            }

            // Validate the received access token
            if (tokenResponse.accessToken == null || tokenResponse.accessToken.trim().isEmpty()) {
                log.error("Received success status from GitHub token endpoint, but access token was missing.");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new MessageResponse("Failed to retrieve access token from GitHub."))
                        .build();
            }
            String githubAccessToken = tokenResponse.accessToken;
            log.info("Successfully received GitHub access token.");

            // --- Step 2: Determine User Identifier (Prioritize Email) ---
            String userIdentifier = null;
            Long githubUserId = null; // Store ID separately
            String githubLogin = null; // Store login for logging

            // 2a. Try fetching basic user info (might contain public email and ID)
            log.debug("Attempting to fetch basic user info from GitHub API (/user)...");
            GitHubUserResponse userInfo = fetchGitHubUserInfo(githubAccessToken);
            if (userInfo != null) {
                githubUserId = userInfo.id; // Store ID regardless
                githubLogin = userInfo.login;
                if (userInfo.email != null && !userInfo.email.isEmpty()) {
                    log.info("Found email directly from /user endpoint: {}", userInfo.email);
                    userIdentifier = userInfo.email; // Use public email if present
                } else {
                    log.info("Email field was null or empty in /user response.");
                }
            } else {
                log.warn("Failed to fetch basic user info from /user endpoint. Will proceed to check /user/emails.");
            }

            // 2b. If email wasn't found via /user, explicitly check /user/emails
            if (userIdentifier == null) {
                log.info("Checking /user/emails endpoint for primary, verified email...");
                List<GitHubEmailResponse> emails = fetchGitHubEmails(githubAccessToken);
                if (emails != null) {
                    // Find the primary verified email
                    for (GitHubEmailResponse emailInfo : emails) {
                        log.trace("Checking email entry: {} (Primary: {}, Verified: {})", emailInfo.getEmail(), emailInfo.isPrimary(), emailInfo.isVerified());
                        if (emailInfo.primary && emailInfo.verified && emailInfo.email != null && !emailInfo.email.isEmpty()) {
                            userIdentifier = emailInfo.email;
                            log.info("Found primary, verified email via /user/emails endpoint: {}", userIdentifier);
                            break; // Found the best email, exit loop
                        }
                    }
                    if (userIdentifier == null) {
                        log.warn("No primary, verified email found in list from /user/emails endpoint.");
                        // Optional: Could add a second loop here to find *any* verified email as a fallback
                    }
                } else {
                    log.warn("Failed to fetch email list from /user/emails endpoint (returned null).");
                }
            }

            // 2c. If still no email, fall back to GitHub ID (if available)
            if (userIdentifier == null) {
                if (githubUserId != null) {
                    userIdentifier = "github_id:" + githubUserId;
                    log.warn("No verified email found. Falling back to use GitHub ID: {} (login: {})", githubUserId, githubLogin);
                } else {
                    // This scenario means both /user and /user/emails failed to provide an ID or email.
                    log.error("CRITICAL: Could not retrieve ANY unique user identifier (email or ID) from GitHub API.");
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(new MessageResponse("Failed to retrieve required user identifier from GitHub."))
                            .build();
                }
            }

            // --- Step 3: Generate *Backend* Session Token ---
            String backendToken = UUID.randomUUID().toString();

            // --- Step 4: Store Session Mapping ---
            AuthResource.getActiveSessions().put(backendToken, userIdentifier);
            log.info("Generated backend session token and stored mapping for user identifier '{}'", userIdentifier);
            log.debug("Current active sessions map size: {}", AuthResource.getActiveSessions().size());

            // --- Step 5: Return Backend Token to Plugin ---
            log.info("Authentication successful. Returning backend token to plugin.");
            return Response.ok(new AuthResponse("Authentication successful.", backendToken)).build();

        } catch (IOException e) {
            log.error("Network error during communication with GitHub: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse("Network error during GitHub authentication: " + e.getMessage()))
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HTTP request to GitHub was interrupted", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse("Authentication process interrupted."))
                    .build();
        } catch (Exception e) { // Catch other potential errors (JSON parsing, etc.)
            log.error("Unexpected error during GitHub code exchange process", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse("Internal server error during authentication: " + e.getMessage()))
                    .build();
        }
    }

    // --- Private Helper Methods ---

    /**
     * Calls GitHub's token endpoint to exchange an authorization code for an access token.
     */
    private GitHubTokenResponse fetchGitHubAccessToken(String code, String clientId, String clientSecret)
            throws IOException, InterruptedException { // Propagate specific exceptions
        log.debug("Building request to GitHub token endpoint...");
        Map<Object, Object> data = new HashMap<>();
        data.put("client_id", clientId);
        data.put("client_secret", clientSecret);
        data.put("code", code);

        StringBuilder formBodyBuilder = new StringBuilder();
        for (Map.Entry<Object, Object> entry : data.entrySet()) {
            if (formBodyBuilder.length() > 0) { formBodyBuilder.append("&"); }
            formBodyBuilder.append(URLEncoder.encode(entry.getKey().toString(), StandardCharsets.UTF_8));
            formBodyBuilder.append("=");
            formBodyBuilder.append(URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
        }
        String formBody = formBodyBuilder.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        log.info("Sending request to GitHub token endpoint: {}", GITHUB_TOKEN_ENDPOINT);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("GitHub token endpoint response status: {}", response.statusCode());
        log.debug("GitHub token endpoint response body: {}", response.body());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return objectMapper.readValue(response.body(), GitHubTokenResponse.class);
        } else {
            GitHubTokenResponse errorResponse = null;
            try {
                errorResponse = objectMapper.readValue(response.body(), GitHubTokenResponse.class);
            } catch (Exception parseEx) {
                log.warn("Could not parse error response body from GitHub token endpoint (Status {}). Body: {}", response.statusCode(), response.body(), parseEx);
            }
            String errorDetails = (errorResponse != null && errorResponse.error != null)
                    ? String.format("%s (%s)", errorResponse.errorDescription, errorResponse.error)
                    : response.body();
            throw new IOException("GitHub token request failed. Status: " + response.statusCode() + ". Details: " + errorDetails);
        }
    }

    /**
     * Calls GitHub's user API endpoint (/user) to fetch basic user details.
     */
    private GitHubUserResponse fetchGitHubUserInfo(String githubAccessToken)
            throws IOException, InterruptedException { // Propagate specific exceptions
        log.debug("Building request to GitHub user endpoint (/user)...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_USER_ENDPOINT))
                .header("Authorization", "Bearer " + githubAccessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        log.info("Sending request to GitHub user endpoint: {}", GITHUB_USER_ENDPOINT);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("GitHub user endpoint response status: {}", response.statusCode());
        log.debug("GitHub user endpoint response body: {}", response.body());

        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), GitHubUserResponse.class);
        } else {
            log.error("Failed to fetch basic user info from GitHub API (/user). Status: {}, Body: {}", response.statusCode(), response.body());
            return null; // Return null on failure, let caller decide how to handle
        }
    }

    /**
     * <<< NEW HELPER METHOD >>>
     * Calls GitHub's user emails endpoint (/user/emails) to fetch all email addresses.
     * Requires the 'user:email' scope.
     */
    private List<GitHubEmailResponse> fetchGitHubEmails(String githubAccessToken)
            throws IOException, InterruptedException { // Propagate specific exceptions

        log.debug("Building request to GitHub user emails endpoint (/user/emails)...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_EMAILS_ENDPOINT)) // Use the emails endpoint URL
                .header("Authorization", "Bearer " + githubAccessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        log.info("Sending request to GitHub user emails endpoint: {}", GITHUB_EMAILS_ENDPOINT);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("GitHub user emails endpoint response status: {}", response.statusCode());
        log.debug("GitHub user emails endpoint response body: {}", response.body());

        if (response.statusCode() == 200) {
            // Success: Parse the JSON array response into a List of GitHubEmailResponse objects
            TypeReference<List<GitHubEmailResponse>> listType = new TypeReference<List<GitHubEmailResponse>>() {};
            try {
                return objectMapper.readValue(response.body(), listType);
            } catch (Exception e) {
                log.error("Failed to parse JSON array from /user/emails response. Body: {}", response.body(), e);
                return null; // Return null if parsing fails
            }
        } else {
            // Failed to get emails
            log.error("Failed to fetch emails list from GitHub API (/user/emails). Status: {}, Body: {}", response.statusCode(), response.body());
            return null; // Return null on failure
        }
    }
}