package com.budra.uvh.config; // Or com.budra.uvh.filters

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response; // Ensure this Response is jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {

        String requestOrigin = requestContext.getHeaderString("Origin");
        String requestMethod = requestContext.getMethod();

        // Log details for debugging CORS issues
        // System.out.println("CORS Filter: Request Origin: " + requestOrigin);
        // System.out.println("CORS Filter: Request Method: " + requestMethod);
        // System.out.println("CORS Filter: Path: " + requestContext.getUriInfo().getPath());

        // --- Add headers to the RESPONSE context ---
        responseContext.getHeaders().add(
                "Access-Control-Allow-Origin", "http://localhost:5173"); // Allow specific origin

        responseContext.getHeaders().add(
                "Access-Control-Allow-Credentials", "true"); // Allow credentials

        responseContext.getHeaders().add(
                "Access-Control-Allow-Headers",
                "origin, content-type, accept, authorization, x-requested-with"); // Common headers + content-type for JSON/FormData

        responseContext.getHeaders().add(
                "Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS, HEAD"); // Allowed methods

        // --- Handle OPTIONS preflight requests ---
        // The browser sends OPTIONS before non-simple requests (like POST with custom headers or non-standard Content-Type)
        if ("OPTIONS".equalsIgnoreCase(requestMethod)) {
            System.out.println("CORS Filter: Handling OPTIONS preflight request");
            // For OPTIONS, we just need to return the allowed methods/headers/origin.
            // Setting status to OK allows the browser to proceed with the actual request.
            responseContext.setStatus(Response.Status.OK.getStatusCode());
            // IMPORTANT: Stop further processing for OPTIONS requests in the filter chain
            // by not necessarily proceeding to the resource method. Setting the status
            // and returning is often enough for the browser preflight check.
            // However, the JAX-RS implementation might handle this implicitly after headers are set.
            // Explicitly returning OK status is the standard practice.
        }

        // Debugging: Log response headers being added
        // System.out.println("CORS Filter: Response Headers Added: " + responseContext.getHeaders());
    }
}