package com.budra.uvh.controllers;

// Import only necessary DTOs
import com.budra.uvh.dto.MessageResponse;

// JAX-RS imports
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

// Logging imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Concurrency import
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages backend sessions and sign-out functionality.
 * Authentication (sign-in) is now handled by GitHubAuthResource.
 */
@Path("/auth") // Base path for authentication-related actions
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    // --- Session Management ---
    // This map stores the active backend sessions.
    // Key: Backend-generated session token (e.g., UUID)
    // Value: User identifier obtained from GitHub (e.g., email or "github_id:12345")
    // NOTE: Static map is simple but not persistent. Consider a proper session store (DB, Redis) for production.
    private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * Provides access to the active session map (used by GitHubAuthResource and validation).
     * @return The map of active backend tokens to user identifiers.
     */
    public static Map<String, String> getActiveSessions() {
        return activeSessions;
    }

    /**
     * Validates a backend session token provided by the plugin.
     * @param token The backend token (without "Bearer ").
     * @return The user identifier (e.g., email, github_id:...) associated with the token, or null if invalid/expired.
     */
    public static String validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null; // No token provided
        }
        // Lookup the token in the active sessions map
        String userIdentifier = activeSessions.get(token.trim());
        if (userIdentifier != null) {
            log.trace("Token validation successful for token prefix: {}", token.substring(0, Math.min(8, token.length())));
        } else {
            log.trace("Token validation failed for token prefix: {}", token.substring(0, Math.min(8, token.length())));
        }
        return userIdentifier; // Returns the identifier or null
    }


    // --- Sign-Out Endpoint ---
    @POST // Handles POST requests for sign-out
    @Path("/signout")
    public Response signOut(@HeaderParam("Authorization") String authorizationHeader) {
        log.info("Received sign-out request.");

        String token = null;
        final String bearerPrefix = "Bearer "; // Define prefix locally

        // Extract token from "Bearer <token>" header
        if (authorizationHeader != null && authorizationHeader.startsWith(bearerPrefix)) {
            token = authorizationHeader.substring(bearerPrefix.length()).trim();
        }

        // Check if token was extracted
        if (token == null || token.isEmpty()) {
            log.warn("Sign-out attempt with missing or invalid Bearer token in Authorization header.");
            // Return 400 Bad Request as the required header is missing or malformed
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse("Authorization header with Bearer token is required."))
                    .build();
        }

        // Attempt to remove the session associated with the token
        String removedIdentifier = activeSessions.remove(token);

        if (removedIdentifier != null) {
            // Successfully removed the session
            log.info("Successfully signed out session for user identifier '{}' associated with token prefix '{}...'", removedIdentifier, token.substring(0, Math.min(8, token.length())));
            log.debug("Active sessions map size after removal: {}", activeSessions.size());
        } else {
            // Token was not found in the active sessions map (already signed out, invalid, or expired)
            log.warn("Sign-out request received for an invalid or already expired token prefix: '{}...'", token.substring(0, Math.min(8, token.length())));
        }

        // Always return a success response for sign-out attempts, regardless of whether
        // the token was valid or not. This prevents leaking information about session validity.
        return Response.ok()
                .entity(new MessageResponse("Sign-out processed."))
                .build();
    }

    // No signIn method needed here anymore.
    // No credential storage needed here anymore.
}