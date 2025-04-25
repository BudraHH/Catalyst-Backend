package com.budra.uvh.config;

// Import your CorsFilter if you intend to register it explicitly
// import com.budra.uvh.filters.CorsFilter;

import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationPath("/api") // Defines the base path for all JAX-RS resources
public class ResourceConfig extends org.glassfish.jersey.server.ResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceConfig.class);

    public ResourceConfig() {
        log.info("Initializing JAX-RS ResourceConfig using annotation-based discovery...");

        // --- Package Scanning ---
        // List ALL packages containing components with JAX-RS (@Path),
        // Jakarta DI (@Singleton, @RequestScoped, @Inject),
        // or JAX-RS Provider (@Provider, ExceptionMapper) annotations.
        packages(
                "com.budra.uvh.controllers",   // Contains RequestHandler (@Path, @RequestScoped)
                "com.budra.uvh.git",           // Contains AuthResource (@Path)
                "com.budra.uvh.service",       // Contains LskResolution (@RequestScoped)
                "com.budra.uvh.model",         // Contains LskCounterRepository (@Singleton)
                "com.budra.uvh.filters",       // Contains CorsFilter (@Provider)
                "com.budra.uvh.exception"      // Contains any ExceptionMappers (@Provider)
        );
        // Log the packages being scanned for easier debugging
        log.info("Scanning packages: com.budra.uvh.controllers, com.budra.uvh.git, com.budra.uvh.service, com.budra.uvh.model, com.budra.uvh.filters, com.budra.uvh.exception");

        // --- FEATURE REGISTRATION ---
        // Register essential Jersey features that configure underlying providers or capabilities.
        log.info("Registering required Jersey features: MultiPartFeature, JacksonFeature");
        register(MultiPartFeature.class); // Essential for @FormDataParam and multipart requests
        register(JacksonFeature.class);   // Essential for automatic JSON marshalling/unmarshalling

        // --- Explicit Registration (Optional but sometimes clearer) ---
        // If CorsFilter has @Provider and is in a scanned package, this is optional.
        // Explicitly registering can sometimes help if scanning fails or for clarity.
        // register(CorsFilter.class);
        // log.info("Explicitly registered CorsFilter."); // Uncomment log if you uncomment register

        // AuthResource should be picked up by scanning com.budra.uvh.git because it has @Path
        // register(AuthResource.class);

        // The AbstractBinder for manual DI has been correctly removed.

        log.info("JAX-RS (Jersey) Application Initialized.");
    }
}