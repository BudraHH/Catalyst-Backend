package com.budra.uvh.controllers;

// Service Dependency
import com.budra.uvh.service.LskResolution;

// JAX-RS Annotations and Classes
import jakarta.ws.rs.*; // Includes @Path, @POST, @Consumes, @Produces
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.media.multipart.FormDataParam; // For multipart form data

// Custom Exceptions
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.exception.PlaceholderFormatException;
import com.budra.uvh.exception.ReferenceResolutionException; // Import the new exception

// Logging
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// NOTE: No lifecycle annotations like @RequestScoped needed here due to manual DI factory

@Path("/logical-seed-key")
public class RequestHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    private final LskResolution lskResolution;

    private String currentEmail;

    public String getCurrentEmail() {
        return currentEmail;
    }



    // --- Constructor for Manual DI ---
    // This constructor is called by the ManualDIProviderFactory
    public RequestHandler(LskResolution lskResolution) {
        log.debug("RequestHandler instance MANUALLY created via constructor.");
        if (lskResolution == null) {
            // Fail fast if the dependency wasn't provided during manual wiring
            log.error("CRITICAL: LskResolution dependency was null during RequestHandler creation!");
            throw new IllegalArgumentException("LskResolution cannot be null for RequestHandler");
        }
        this.lskResolution = lskResolution;
    }

    @POST
    @Path("/resolve")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response processXmlUpload(
            @HeaderParam("Authorization") String authorizationHeader,
            @FormDataParam("repository") String repositoryUrl,
            @FormDataParam("branch") String branchName,
            @FormDataParam("relativePath") String relativePath,
            @FormDataParam("xmlFile") String inputXml)
    {
        log.info("Received POST request on /api/logical-seed-key/resolve");

        String userAccessToken = null;
        String bearerPrefix = "Bearer ";

        if (authorizationHeader != null && authorizationHeader.startsWith(bearerPrefix)) {
            userAccessToken = authorizationHeader.substring(bearerPrefix.length());
            log.debug("Extracted Access Token from header.");
        } else {
            log.warn("Authorization header is missing or is not in the expected Bearer format.");
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiResponse("Authorization header is missing or invalid."))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        log.info("token is " + userAccessToken);
        String email = AuthResource.getActiveSessions().get(userAccessToken);
        log.info("");
        log.info("");
        log.info("Developer Email currently request from is : " + email);
        log.info("");
        currentEmail = email;
        log.info("Developer email stored for this thread");
        // --- Input Validation ---
        if (this.lskResolution == null) {
            log.error("Critical error: lskResolution field is null within request handler method!");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("Internal server configuration error."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // Validate mandatory form parameters (like xmlFile)
        if (inputXml == null || inputXml.trim().isEmpty()) {
            log.warn("Received empty or null XML input for resolution.");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Request body requires XML content in 'xmlFile' form parameter."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        // Optional: Validate other parameters if they become mandatory
        // if(repositoryUrl == null || repositoryUrl.trim().isEmpty()) { ... }
        // if(branchName == null || branchName.trim().isEmpty()) { ... }
        // if(relativePath == null || relativePath.trim().isEmpty()) { ... }



        try {
            String resolvedXml = this.lskResolution.processAndResolveXml(inputXml,currentEmail);

            log.info("LSK and Reference resolution successful.");
            return Response.status(Response.Status.OK)
                    .entity(new ApiResponse("Successfully resolved LSKs and references!", resolvedXml))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (PlaceholderFormatException e) {
            log.warn("Placeholder format error during resolution: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Invalid placeholder format detected: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (ReferenceResolutionException e) {
            log.warn("Reference resolution error: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiResponse("Reference resolution failed: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (LskGenerationException e) {
            log.error("LSK Generation or Database error during resolution: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("LSK generation backend error: " + e.getMessage()))
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (Exception e) {
            log.error("Unexpected internal server error during LSK resolution: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ApiResponse("An unexpected internal server error occurred."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }


    public static class ApiResponse {
        public String message;
        public String data;
        public String error;


        public ApiResponse(String message, String data) {
            this.message = message;
            this.data = data;
            this.error = null;
        }


        public ApiResponse(String error) {
            this.message = null;
            this.data = null;
            this.error = error;
        }

        public ApiResponse() {}

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}