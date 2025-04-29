package com.budra.uvh.filters;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider; // Keep Provider annotation
import java.io.IOException;
import org.slf4j.Logger; // Use SLF4j
import org.slf4j.LoggerFactory;

@Provider // KEEP: Makes it discoverable by package scanning OR explicit registration
public class CorsFilter implements ContainerResponseFilter {

    private static final Logger log = LoggerFactory.getLogger(CorsFilter.class);

    // Public No-Arg Constructor (Needed by JAX-RS)
    public CorsFilter() {
        log.debug("CorsFilter instance created.");
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {

        // Optional logging for debugging
        // log.trace("CorsFilter executing for path: {}", requestContext.getUriInfo().getPath());

        // Add CORS Headers
        responseContext.getHeaders().add("Access-Control-Allow-Origin", "http://localhost:5174"); // Or read from config
        responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");
        responseContext.getHeaders().add("Access-Control-Allow-Headers", "origin, content-type, accept, authorization, x-requested-with");
        responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");

        // Handle Preflight OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            log.debug("CorsFilter handling OPTIONS preflight request for path: {}", requestContext.getUriInfo().getPath());
            responseContext.setStatus(Response.Status.OK.getStatusCode());
            // Optional: Abort request chain for OPTIONS if needed, but often just setting headers/status is enough
            // requestContext.abortWith(Response.ok().build());
        }
    }
}