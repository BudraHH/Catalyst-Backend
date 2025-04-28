package com.budra.uvh.controllers;

import com.budra.uvh.exception.*;
import com.budra.uvh.service.GenericLskProcessingService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.glassfish.jersey.media.multipart.FormDataParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.SQLException;

@Path("/lsk")
public class RequestHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    private final GenericLskProcessingService lskProcessingService;


    @Inject
    public RequestHandler(GenericLskProcessingService lskProcessingService) {
        log.debug("RequestHandler instance created by DI Container (Manual Binding) with injected service.");
        if (lskProcessingService == null) {
            log.error("CRITICAL: GenericLskProcessingService injection failed during manual binding setup!");
            throw new IllegalStateException("DI framework failed to inject GenericLskProcessingService (Manual Binding)");
        }
        this.lskProcessingService = lskProcessingService;
    }

    public RequestHandler() {
        log.warn("RequestHandler default constructor called! This might indicate a DI configuration problem.");
        this.lskProcessingService = null;
    }

    // --- Endpoint for Multipart Upload & JSON Response ---
    @POST
    @Path("/process-xml-upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response processXmlUpload(
            @FormDataParam("repository") String repositoryUrl,
            @FormDataParam("branch") String branchName,
            @FormDataParam("relativePath") String relativePath,
            @FormDataParam("xmlFile") InputStream fileInputStream)
    {
        String operationId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.info("[{}] Received POST request on /process-xml-upload", operationId);

        // --- Check if service was injected ---
        if (this.lskProcessingService == null) {
            log.error("[{}] Internal Server Error: lskProcessingService was not injected correctly.", operationId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("Internal server configuration error."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // --- Input Validation (Simplified) ---
        if (repositoryUrl == null || branchName == null || relativePath == null || fileInputStream == null){
            log.warn("[{}] Missing one or more required form parameters (repository, branch, relativePath, xmlFile).", operationId);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Missing required form parameters (repository, branch, relativePath, xmlFile)."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            log.debug("[{}] Delegating processing to GenericLskProcessingService...", operationId);
            this.lskProcessingService.parseProcessAndPersist(fileInputStream);
            log.info("[{}] XML processing and persistence completed successfully.", operationId);

            // --- Return Success Response ---
            return Response.ok(new ApiResponse("XML processed and data persisted successfully.", null))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

            // --- Exception Handling ---
        } catch (XmlParsingException | PlaceholderFormatException e) { // Combine Bad Request errors
            log.warn("[{}] Invalid input data: {}", operationId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Invalid input format" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (LskGenerationException | DataPersistenceException | SQLException e) { // Combine potential DB/Processing errors -> 500
            log.error("[{}] Server error during processing or persistence: {}", operationId, e.getMessage(), e);
            // Optionally differentiate message based on exception type if needed
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("Processing or data persistence failed" + (e.getMessage() != null ? ": " + e.getMessage() : ".")))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (IllegalStateException e) { // Often indicates logic errors
            log.error("[{}] Internal state error: {}", operationId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("An internal processing logic error occurred."))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) { // Catch-all for truly unexpected errors
            log.error("[{}] Unexpected internal server error: {}", operationId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("An unexpected error occurred."))
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    // --- Static Nested Class for Standard JSON Responses ---
    public static class ApiResponse {
        public String message;
        public String data;
        public String error;

        public ApiResponse(String message, String data) { this.message = message; this.data = data; this.error = null; }
        public ApiResponse(String error) { this.message = null; this.data = null; this.error = error; }
        public ApiResponse() {}
    }
}