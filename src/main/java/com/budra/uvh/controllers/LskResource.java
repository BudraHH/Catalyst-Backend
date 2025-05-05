package com.budra.uvh.controllers;

import com.budra.uvh.dto.ApiResponse;
import com.budra.uvh.service.LskResolution;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.budra.uvh.dto.ResolveRequest;

import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.ReferenceResolutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Handles requests related to Logical Seed Key resolution.
 */
@Path("/logical-seed-key") // Base path for LSK operations
public class LskResource {
    private static final Logger log = LoggerFactory.getLogger(LskResource.class);

    // Dependency on the service layer (injected via factory)
    private final LskResolution lskResolution;

    /**
     * Constructor used by the ManualDIProviderFactory for injection.
     * @param lskResolution The LSK resolution service instance.
     */
    public LskResource(LskResolution lskResolution) {
        log.debug("RequestHandler instance MANUALLY created via constructor.");
        if (lskResolution == null) {
            // Fail fast during application startup/request processing if dependency missing
            log.error("CRITICAL: LskResolution dependency was null during RequestHandler creation!");
            throw new IllegalArgumentException("LskResolution cannot be null for RequestHandler");
        }
        this.lskResolution = lskResolution;
    }

    /**
     * Endpoint to resolve LSK placeholders and references in XML content.
     * Expects a JSON request body and a Bearer token for authorization.
     *
     * @param authorizationHeader The "Authorization: Bearer <token>" header.
     * @param resolveRequest The request body parsed from JSON into a ResolveRequest DTO.
     * @return JAX-RS Response containing either the resolved XML or an error message.
     */
    @POST
    @Path("/resolve") // Endpoint path: /api/logical-seed-key/resolve
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response processLskResolution(
                                          @HeaderParam("Authorization") String authorizationHeader,ResolveRequest resolveRequest)
    {
        log.info("Received POST request on /api/logical-seed-key/resolve");

        // --- Authorization ---
        String userAccessToken = null;
        final String bearerPrefix = "Bearer ";

        // Extract the token from the "Authorization: Bearer <token>" header
        if (authorizationHeader != null && authorizationHeader.startsWith(bearerPrefix)) {
            userAccessToken = authorizationHeader.substring(bearerPrefix.length()).trim();
            log.debug("Extracted backend Access Token from header.");
        } else {
            log.warn("Authorization header is missing or is not in the expected Bearer format.");
            // Return 401 Unauthorized if token is missing or malformed
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiResponse("Authorization header with Bearer token is required."))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        // Validate the extracted backend token using the static helper from AuthResource
        String userIdentifier = AuthResource.validateToken(userAccessToken);
        if (userIdentifier == null) {
            log.warn("Invalid or expired backend token received for LSK resolution. Token prefix: '{}...'", userAccessToken.substring(0, Math.min(8, userAccessToken.length())));
            // Return 401 Unauthorized if token is not found in active sessions
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiResponse("Invalid or expired session token."))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        // If validation passes, userIdentifier contains the email or github_id string
        log.info("Token validated successfully for user identifier: {}", userIdentifier);
        // Use this identifier for logging or potentially passing to the service if needed
        String emailForLogging = userIdentifier; // Assuming identifier is suitable for logging


        // --- Input Validation ---
        // Check internal dependency (should have been caught by constructor, but good practice)
        if (this.lskResolution == null) {
            log.error("Critical internal error: lskResolution field is null within request handler method!");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("Internal server configuration error."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // Validate the JSON request body

        if (resolveRequest == null) {
            log.warn("Received request with missing JSON body.");
            // Return 400 Bad Request if input is invalid
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Request body must be a JSON object ."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        if (resolveRequest.getModuleName() == null || resolveRequest.getModuleName().trim().isEmpty()) {
            log.warn("Received request with missing or empty 'xmlContent' in JSON body.");
            // Return 400 Bad Request if input is invalid
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Request body must be a JSON object with a non-empty 'moduleName' field."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (resolveRequest.getXmlContent() == null || resolveRequest.getXmlContent().trim().isEmpty()) {
            log.warn("Received request with missing or empty 'xmlContent' in JSON body.");
            // Return 400 Bad Request if input is invalid
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Request body must be a JSON object with a non-empty 'xmlContent' field."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String moduleName = resolveRequest.getModuleName();
        String inputXml = resolveRequest.getXmlContent();

        try {
            // Pass the XML content and the validated user identifier (email/github_id) to the service
            String resolvedXml = this.lskResolution.processAndResolveXml(moduleName,inputXml, emailForLogging);

            log.info("LSK and Reference resolution successful for user: {}", emailForLogging);
            // Return 200 OK with the resolved XML in the 'data' field
            return Response.status(Response.Status.OK)
                    .entity(new ApiResponse("Successfully resolved LSKs and references!", resolvedXml))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

            // --- Handle Specific Expected Errors from Service ---
        } catch (PlaceholderFormatException e) {
            log.warn("Placeholder format error during resolution for user {}: {}", emailForLogging, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST) // 400 Bad Request for format issues
                    .entity(new ApiResponse("Invalid placeholder format detected: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (ReferenceResolutionException e) {
            log.warn("Reference resolution error for user {}: {}", emailForLogging, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST) // 400 Bad Request for resolution logic errors
                    .entity(new ApiResponse("Reference resolution failed: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (LskGenerationException | SQLException e) { // Catch DB/Generation errors
            // Log as error because it indicates a backend problem (DB connection, SQL error, etc.)
            log.error("LSK Generation or Database error during resolution for user {}: {}", emailForLogging, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR) // 500 Internal Server Error
                    .entity(new ApiResponse("LSK generation or database backend error: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

            // --- Handle Unexpected Errors ---
        } catch (Exception e) {
            log.error("Unexpected internal server error during LSK resolution for user {}: {}", emailForLogging, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR) // 500 Internal Server Error
                    .entity(new ApiResponse("An unexpected internal server error occurred."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }


    /**
     * Inner class defining the structure for JSON responses from this endpoint.
     * Matches the DTO expected by the IntelliJ plugin.
     */

}