package com.budra.uvh.controllers;

// Service and Exception Imports
import com.budra.uvh.exception.*; // Import all custom exceptions
import com.budra.uvh.service.GenericLskProcessingService; // Use the generic service

// JAX-RS Imports
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

// Jersey Multipart Imports
import org.glassfish.jersey.media.multipart.FormDataParam;

// DI / Scope Imports
import jakarta.enterprise.context.RequestScoped; // Standard Jakarta EE scope
import jakarta.inject.Inject;

// Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java IO Imports
import java.io.InputStream;

@Path("/lsk") // Base path for this resource
@RequestScoped // One instance per HTTP request
public class RequestHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    // --- Dependency Injection ---
    private final GenericLskProcessingService lskProcessingService;

    @Inject // Instructs DI container (e.g., HK2) to inject the service
    public RequestHandler(GenericLskProcessingService lskProcessingService) {
        log.debug("RequestHandler instance created (@RequestScoped) with injected GenericLskProcessingService.");
        if (lskProcessingService == null) {
            // Basic check, DI framework should handle this if configured correctly
            throw new IllegalStateException("DI framework failed to inject GenericLskProcessingService");
        }
        this.lskProcessingService = lskProcessingService;
    }

    // No-arg constructor needed by some frameworks/proxies, but injection constructor is primary
    public RequestHandler() {
        this.lskProcessingService = null; // Should be replaced by DI container
        // Or throw if called directly: throw new IllegalStateException("Default constructor should not be called directly when using DI");
        log.warn("RequestHandler default constructor called - DI might not be configured correctly if service is null later.");
    }


    // --- Endpoint for Multipart Upload & JSON Response ---
    @POST
    @Path("/process-xml-upload") // More descriptive path
    @Consumes(MediaType.MULTIPART_FORM_DATA) // Expects file upload
    @Produces(MediaType.APPLICATION_JSON) // Returns JSON structure (ApiResponse)
    public Response processXmlUpload(
             @FormDataParam("repository") String repositoryUrl, // Keep if needed
             @FormDataParam("branch") String branchName,       // Keep if needed
             @FormDataParam("relativePath") String relativePath, // Keep if needed
             @FormDataParam("xmlFile") InputStream fileInputStream) // The uploaded file stream
    {
        String operationId = java.util.UUID.randomUUID().toString().substring(0, 8); // Simple request tracking ID
        log.info("[{}] Received POST request on /process-xml-upload", operationId);
        // log.info("[{}] Context - Repo: [{}], Branch: [{}], Path: [{}]", operationId, repositoryUrl, branchName, relativePath); // Log context if params are kept

        // --- Input Validation ---
        if (repositoryUrl == null){
            log.warn("[{}] Missing  file upload repository URL.", operationId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Repository URL is required.")) // Use ApiResponse for consistency
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (branchName == null){
            log.warn("[{}] Missing  file upload branch name.", operationId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Repository branch is required.")) // Use ApiResponse for consistency
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (relativePath == null){
            log.warn("[{}] Missing  file relative path.", operationId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Relative file path is required.")) // Use ApiResponse for consistency
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (fileInputStream == null) {
            log.warn("[{}] Missing XML file upload ('xmlFile' form part).", operationId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("XML file ('xmlFile') is required.")) // Use ApiResponse for consistency
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            // --- Delegate Processing to Service ---
            // The service now handles parsing, LSK generation, FK resolution, and persistence
            log.debug("[{}] Delegating processing to GenericLskProcessingService...", operationId);
            this.lskProcessingService.parseProcessAndPersist(fileInputStream);
            log.info("[{}] XML processing and persistence completed successfully.", operationId);

            // --- Return Success Response (ApiResponse JSON) ---
            // Since data is persisted, we usually just return a success message, not the processed data.
            return Response.ok(new ApiResponse("XML processed and data persisted successfully.", null)) // Success message, no data payload
                    .type(MediaType.APPLICATION_JSON)
                    .build();

            // --- Specific Exception Handling ---
        } catch (XmlParsingException e) {
            log.warn("[{}] XML Parsing error: {}", operationId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("XML parsing failed" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (PlaceholderFormatException e) {
            log.warn("[{}] Placeholder format error: {}", operationId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Invalid LSK placeholder format" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (LskGenerationException e) {
            log.error("[{}] LSK Generation or DB error during processing: {}", operationId, e.getMessage(), e);
            // Potentially BAD_REQUEST if arguments were invalid, INTERNAL_SERVER_ERROR if DB issue
            Response.Status status = (e.getCause() instanceof IllegalArgumentException) ? Response.Status.BAD_REQUEST : Response.Status.INTERNAL_SERVER_ERROR;
            return Response.status(status)
                    .entity(new ApiResponse("LSK generation failed" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (DataPersistenceException e) {
            log.error("[{}] Data persistence error: {}", operationId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("Failed to save data to the database" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) {
            // Often indicates logic errors (like FK resolution failure)
            log.error("[{}] Internal state error during processing: {}", operationId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("An internal processing error occurred" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        }
        // --- Generic Exception Handling ---
        catch (Exception e) {
            // Catch any other unexpected errors
            log.error("[{}] Unexpected internal server error during processing: {}", operationId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("An unexpected internal server error occurred."))
                    .type(MediaType.APPLICATION_JSON).build();
        } finally {
            // Clean up the input stream? Jersey might do this, but good practice if handling manually.
            // Note: InputStream from @FormDataParam is typically managed by Jersey.
            // try { if (fileInputStream != null) fileInputStream.close(); } catch (IOException ignored) {}
        }
    }

    // --- Optional: Add other endpoints as needed ---

    // --- Static Nested Class for Standard JSON Responses ---
    // (Copied from your original code - keep this or move to model package)
    public static class ApiResponse {
        public String message;
        public String data; // Can be used for other endpoints that DO return data
        public String error;

        public ApiResponse(String message, String data) {
            this.message = message; this.data = data; this.error = null;
        }
        public ApiResponse(String error) {
            this.message = null; this.data = null; this.error = error;
        }
        public ApiResponse() {} // Needed by Jackson sometimes
    }
}