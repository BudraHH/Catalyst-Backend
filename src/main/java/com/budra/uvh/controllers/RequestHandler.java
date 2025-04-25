package com.budra.uvh.controllers;

// Service and Exception Imports
import com.budra.uvh.service.LskResolution;
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.PlaceholderFormatException;

// JAX-RS Imports
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

// Jersey Multipart Imports
import org.glassfish.jersey.media.multipart.FormDataParam;

// DI / Scope Imports
import jakarta.enterprise.context.RequestScoped; // Or: import org.glassfish.jersey.process.internal.RequestScoped;
import jakarta.inject.Inject;

// Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java IO Imports
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Path("/logical-seed-key") // Base path for this resource
@RequestScoped // Explicitly define lifecycle - one instance per HTTP request
public class RequestHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    private final LskResolution lskResolution; // Dependency field

    // --- Use @Inject for Dependency Injection ---
    @Inject // Instructs HK2 to inject dependencies when creating an instance
    public RequestHandler(LskResolution lskResolution) {
        log.debug("RequestHandler instance created via @Inject constructor (RequestScoped).");
        if (lskResolution == null) {
            throw new IllegalStateException("DI framework failed to inject LskResolution");
        }
        this.lskResolution = lskResolution;
    }

    // --- Static Nested Class for Standard JSON Responses ---
    // (Or move to its own file: e.g., com.budra.uvh.model.ApiResponse.java)
    // Making it public allows Jackson to access fields easily.
    public static class ApiResponse {
        public String message; // Optional success/info message
        public String data;    // To hold resolved XML or other payload data
        public String error;   // Optional error message

        // Constructor for success responses
        public ApiResponse(String message, String data) {
            this.message = message;
            this.data = data;
            this.error = null; // Ensure error is null on success
        }

        // Constructor for error responses
        public ApiResponse(String error) {
            this.message = null; // Ensure message/data are null on error
            this.data = null;
            this.error = error;
        }

        // Static factory methods for convenience
        public static ApiResponse success(String message, String data) {
            return new ApiResponse(message, data);
        }

        public static ApiResponse error(String error) {
            return new ApiResponse(error);
        }

        // Default no-arg constructor - Jackson might need this in some cases
        public ApiResponse() {}
    }
    // --- End ApiResponse Definition ---


    // --- Endpoint for Multipart Upload & JSON Response ---
    @POST
    @Path("/resolve-upload") // Endpoint path
    @Consumes(MediaType.MULTIPART_FORM_DATA) // Expects file upload + form fields
    @Produces(MediaType.APPLICATION_JSON) // Returns JSON structure (ApiResponse)
    public Response resolveLskFromUpload(
            @FormDataParam("repository") String repositoryUrl,
            @FormDataParam("branch") String branchName,
            @FormDataParam("relativePath") String relativePath,
            @FormDataParam("xmlFile") InputStream fileInputStream)
    {
        log.info("Received POST request on /resolve-upload");
        log.info("Context - Repo: [{}], Branch: [{}], Path: [{}]", repositoryUrl, branchName, relativePath);

        String fileContext = (relativePath != null && !relativePath.isEmpty()) ? relativePath : "uploaded file";

        // --- Input Validation ---
        if (fileInputStream == null) {
            log.warn("Missing XML file upload ('xmlFile' form part).");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("XML file ('xmlFile') is required.")) // Use factory method
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        String xmlContent;
        try {
            // Read XML content
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))) {
                xmlContent = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            } catch (IOException e) {
                log.error("Error reading uploaded XML file ({}): {}", fileContext, e.getMessage(), e);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Failed to read uploaded file content."))
                        .type(MediaType.APPLICATION_JSON).build();
            }

            if (xmlContent.trim().isEmpty()) {
                log.warn("Received empty XML file content for {}", fileContext);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("Uploaded XML file content cannot be empty."))
                        .type(MediaType.APPLICATION_JSON).build();
            }

            // --- Delegate to Service ---
            log.debug("Processing XML content from {}", fileContext);
            String resolvedXml = this.lskResolution.processAndResolveXml(xmlContent);
            log.info("LSK resolution successful for {}", fileContext);

            // --- Return Success Response (ApiResponse JSON) ---
            return Response.ok(ApiResponse.success("Resolution successful for " + fileContext, resolvedXml)) // Use factory
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (PlaceholderFormatException e) {
            log.warn("Placeholder format error during resolution for {}: {}", fileContext, e.getMessage());
            String errorMsg = "Invalid placeholder format" + (e.getMessage() != null ? ": " + e.getMessage() : ".");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error(errorMsg)) // Use factory
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (LskGenerationException e) {
            log.error("LSK Generation or DB error during resolution for {}: {}", fileContext, e.getMessage(), e);
            String errorMsg = "LSK generation failed" + (e.getMessage() != null ? ": " + e.getMessage() : ".");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error(errorMsg)) // Use factory
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Unexpected internal server error during LSK resolution for {}: {}", fileContext, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("An unexpected internal server error occurred.")) // Use factory
                    .type(MediaType.APPLICATION_JSON).build();
        }
    }

    // --- Optional: XML-in/XML-out endpoint ---
    /*
    @POST
    @Path("/resolve-xml")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_XML)
    public Response resolveLskXmlInOut(String inputXml) { ... }
    */
}