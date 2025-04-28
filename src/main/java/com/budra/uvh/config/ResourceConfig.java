package com.budra.uvh.config;

import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// --- NOTE: Extends Jersey's ResourceConfig ---
// This is specific to Jersey implementation of JAX-RS
@ApplicationPath("/api") // Defines the base path for all JAX-RS resources in this app
public class ResourceConfig extends org.glassfish.jersey.server.ResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceConfig.class);

    public ResourceConfig() {
        log.info("Initializing JAX-RS Application (Jersey ResourceConfig)...");

        // --- Package Scanning ---
        // Tells Jersey where to look for components annotated with JAX-RS
        // (@Path, @Provider) and Jakarta DI (@Singleton, @RequestScoped, @Inject, etc.)
        // List ALL relevant base packages. Jersey will scan recursively.
        final String basePackage = "com.budra.uvh"; // Use the common root package
        packages(basePackage);

        log.info("Scanning base package for components: {}", basePackage);
        // You could list individual sub-packages like before, but scanning the
        // base package is usually sufficient and less error-prone if you add new sub-packages.
        // packages(
        //        "com.budra.uvh.controllers",
        //        "com.budra.uvh.git",
        //        "com.budra.uvh.service",
        //        "com.budra.uvh.model",       // Needs LskCounterRepository(@Singleton) and potentially ApiResponse if used directly
        //        "com.budra.uvh.filters",     // Needs CorsFilter(@Provider)
        //        "com.budra.uvh.dao",         // Needs GenericDao(@Singleton if applicable)
        //        "com.budra.uvh.config",      // Needs ConnectionManager(@Singleton)
        //        "com.budra.uvh.parser",      // Parsers usually don't need DI scope unless they have dependencies
        //        "com.budra.uvh.exception"    // Needs any ExceptionMappers(@Provider)
        // );

        // --- FEATURE REGISTRATION ---
        // Register essential Jersey features
        log.info("Registering JAX-RS features: JacksonFeature (JSON), MultiPartFeature (File Uploads)");
        register(JacksonFeature.class);   // Enables automatic JSON marshalling/unmarshalling via Jackson
        register(MultiPartFeature.class); // Enables handling of multipart/form-data requests (file uploads)

        // --- Explicit Registration (Usually unnecessary if using @Provider + package scanning) ---
        // Example: If CorsFilter wasn't found via scanning, you could uncomment this.
         register(com.budra.uvh.filters.CorsFilter.class);
         log.debug("Explicitly registered CorsFilter.");

        log.info("Registering AutoScanFeature for HK2 component discovery...");
        register(AutoScanFeature.class);
        // Jersey's built-in Dependency Injection (HK2) should automatically find
        // and manage classes annotated with @Singleton, @RequestScoped, etc., within
        // the scanned packages. Constructor injection (@Inject) is the preferred way.

        log.info("JAX-RS Application Initialized. Base path: /api");
    }
}