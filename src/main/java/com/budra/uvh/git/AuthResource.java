package com.budra.uvh.git; // Or your relevant package (make sure POJO is accessible)

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.glassfish.jersey.jackson.JacksonFeature; // Import Jackson feature
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Path("/auth") // Base path for authentication endpoints
public class AuthResource {

    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    // --- Configuration Keys ---
    private static final String GITHUB_CLIENT_ID_ENV = "GITHUB_CLIENT_ID";
    private static final String GITHUB_CLIENT_SECRET_ENV = "GITHUB_CLIENT_SECRET"; // Add Secret Key
    private static final String GITHUB_REDIRECT_URI_ENV = "GITHUB_REDIRECT_URI";
    private static final String FRONTEND_APP_URL_ENV = "FRONTEND_APP_URL"; // URL to redirect back to

    // --- Session Keys ---
    private static final String GITHUB_SESSION_STATE_KEY = "github_oauth_state";
    public static final String GITHUB_ACCESS_TOKEN_KEY = "github_access_token"; // Public if accessed elsewhere

    // --- GitHub OAuth Details ---
    private static final String GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_SCOPE = "repo"; // Scope needed to read/write repository content


    // --- Endpoint to start the flow ---
    @GET
    @Path("/github/connect")
    @Produces(MediaType.APPLICATION_JSON) // For potential errors
    public Response redirectToGithub(@Context HttpServletRequest request) {
        log.info("Received request to connect GitHub account.");

        String clientId = System.getenv(GITHUB_CLIENT_ID_ENV);
        String redirectUri = System.getenv(GITHUB_REDIRECT_URI_ENV);

        if (isNullOrEmpty(clientId) || isNullOrEmpty(redirectUri)) {
            log.error("Missing GitHub OAuth configuration ({} or {}). Please configure environment variables.",
                    GITHUB_CLIENT_ID_ENV, GITHUB_REDIRECT_URI_ENV);
            return createErrorResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Server configuration error. Cannot initiate GitHub connection.");
        }

        String state = UUID.randomUUID().toString();
        HttpSession session = request.getSession(true);
        session.setAttribute(GITHUB_SESSION_STATE_KEY, state);
        log.debug("Stored state {} in session ID {}", state, session.getId());

        String authorizationUrl;
        try {
            authorizationUrl = GITHUB_AUTH_URL + "?" +
                    "client_id=" + urlEncode(clientId) + "&" +
                    "redirect_uri=" + urlEncode(redirectUri) + "&" +
                    "scope=" + urlEncode(GITHUB_SCOPE) + "&" +
                    "state=" + urlEncode(state);

            log.info("Redirecting user to GitHub for authorization: {}", GITHUB_AUTH_URL);
            return Response.temporaryRedirect(new URI(authorizationUrl)).build();

        } catch (UnsupportedEncodingException | URISyntaxException e) {
            log.error("Failed to build or encode GitHub authorization URL", e);
            return createErrorResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Server error during URL construction.");
        }
    }


    // --- Endpoint to handle the callback from GitHub ---
    @GET
    @Path("/github/callback")
    @Produces(MediaType.APPLICATION_JSON) // For potential errors before final redirect
    public Response handleGithubCallback(@QueryParam("code") String code,
                                         @QueryParam("state") String receivedState,
                                         @QueryParam("error") String githubError,
                                         @QueryParam("error_description") String errorDescription,
                                         @Context HttpServletRequest request) {

        log.info("Received callback from GitHub.");
        HttpSession session = request.getSession(false); // Don't create session if it doesn't exist

        // --- Determine Frontend Redirect URL ---
        // Read from env var, fallback to a default if needed
        String frontendAppUrl = System.getenv(FRONTEND_APP_URL_ENV);
        if (isNullOrEmpty(frontendAppUrl)) {
            log.warn("{} environment variable not set, using default redirect.", FRONTEND_APP_URL_ENV);
            frontendAppUrl = "/"; // Adjust this default as needed (e.g., "/settings")
        }
        URI redirectUriOnError = createRedirectUri(frontendAppUrl, "github_error=true");
        URI redirectUriOnSuccess = createRedirectUri(frontendAppUrl, "github_connected=true");


        // 1. Check for errors from GitHub
        if (!isNullOrEmpty(githubError)) {
            log.warn("GitHub returned an error in callback: {} - {}", githubError, errorDescription);
            return Response.temporaryRedirect(redirectUriOnError).build();
        }

        // 2. Validate Session and State (CSRF Check)
        if (session == null) {
            log.error("Invalid session during GitHub callback.");
            return Response.temporaryRedirect(redirectUriOnError).build();
        }
        String sessionState = (String) session.getAttribute(GITHUB_SESSION_STATE_KEY);
        session.removeAttribute(GITHUB_SESSION_STATE_KEY); // Remove state after use

        if (isNullOrEmpty(sessionState) || isNullOrEmpty(receivedState) || !sessionState.equals(receivedState)) {
            log.error("State mismatch during GitHub callback. Potential CSRF attack. Session: {}, Received: {}",
                    sessionState, receivedState);
            return Response.temporaryRedirect(redirectUriOnError).build();
        }
        log.debug("State validation successful.");

        // 3. Check for code parameter
        if (isNullOrEmpty(code)) {
            log.error("Missing 'code' parameter in GitHub callback.");
            return Response.temporaryRedirect(redirectUriOnError).build();
        }

        // 4. Get Configuration for Token Exchange
        String clientId = System.getenv(GITHUB_CLIENT_ID_ENV);
        String clientSecret = System.getenv(GITHUB_CLIENT_SECRET_ENV);
        String callbackUriRegistered = System.getenv(GITHUB_REDIRECT_URI_ENV); // For the request body

        if (isNullOrEmpty(clientId) || isNullOrEmpty(clientSecret) || isNullOrEmpty(callbackUriRegistered)) {
            log.error("Missing GitHub OAuth configuration for token exchange (ID, SECRET, or REDIRECT_URI).");
            // Don't redirect here, return server error as this shouldn't happen if connect worked
            return createErrorResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Server configuration error during token exchange.");
        }

        // 5. Exchange Code for Access Token
        Client client = null;
        Response tokenResponse = null;
        try {
            // Use try-with-resources if Client implements AutoCloseable in your JAX-RS version, otherwise finally block
            client = ClientBuilder.newClient().register(JacksonFeature.class); // Register Jackson for JSON processing

            WebTarget target = client.target(GITHUB_TOKEN_URL);

            Form formData = new Form()
                    .param("client_id", clientId)
                    .param("client_secret", clientSecret)
                    .param("code", code)
                    .param("redirect_uri", callbackUriRegistered); // Send back redirect URI

            log.info("Requesting access token from GitHub...");
            Invocation.Builder invocationBuilder = target.request(MediaType.APPLICATION_JSON_TYPE);
            tokenResponse = invocationBuilder.post(Entity.form(formData));

            log.debug("GitHub token response status: {}", tokenResponse.getStatus());

            // 6. Process Token Response
            if (tokenResponse.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                GithubTokenResponse githubData = tokenResponse.readEntity(GithubTokenResponse.class);
                log.debug("Received token response data: {}", githubData);

                if (!isNullOrEmpty(githubData.getAccessToken())) {
                    // 7. Store Token Securely (Using Session for this example)
                    log.info("Successfully obtained GitHub access token for session {}", session.getId());
                    session.setAttribute(GITHUB_ACCESS_TOKEN_KEY, githubData.getAccessToken());

                    // 8. Redirect to Frontend on Success
                    return Response.temporaryRedirect(redirectUriOnSuccess).build();

                } else {
                    log.error("GitHub token response successful, but access token was missing or empty. Response: {}", githubData);
                    // Check for error fields in the successful response
                    if (!isNullOrEmpty(githubData.getError())) {
                        log.error("GitHub token response contained error: {} - {}", githubData.getError(), githubData.getErrorDescription());
                    }
                    return Response.temporaryRedirect(redirectUriOnError).build();
                }
            } else {
                // Handle non-2xx response from GitHub token endpoint
                String errorBody = tokenResponse.hasEntity() ? tokenResponse.readEntity(String.class) : "[No Response Body]";
                log.error("Failed to exchange code for token. GitHub response status: {}. Body: {}",
                        tokenResponse.getStatus(), errorBody);
                return Response.temporaryRedirect(redirectUriOnError).build();
            }

        } catch (Exception e) {
            log.error("Exception during GitHub token exchange request.", e);
            return Response.temporaryRedirect(redirectUriOnError).build();
        } finally {
            // Clean up JAX-RS client resources
            if (tokenResponse != null) {
                try {
                    tokenResponse.close();
                } catch (Exception e) {
                    log.warn("Error closing token response", e);
                }
            }
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("Error closing JAX-RS client", e);
                }
            }
        }
    }

    // --- Helper Methods ---

    private boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    private String urlEncode(String value) throws UnsupportedEncodingException {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    // Helper to build final redirect URIs safely
    private URI createRedirectUri(String base, String query) {
        try {
            // Use UriBuilder for robust URI construction
            UriBuilder builder = UriBuilder.fromPath(base);
            if (query != null && !query.isEmpty()) {
                builder.replaceQuery(query); // Use replaceQuery to handle existing query params in base if any
            }
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to create redirect URI from base '{}' and query '{}'", base, query, e);
            // Fallback to a very basic root redirect
            return URI.create("/");
        }
    }

    // Helper to create JSON error responses
    private Response createErrorResponse(Response.Status status, String message) {
        // Simple JSON string construction. Consider using a library or proper JSON object mapping for more complex errors.
        String jsonError = String.format("{\"error\": \"%s\"}", message.replace("\"", "\\\"")); // Basic escaping
        return Response.status(status)
                .entity(jsonError)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}