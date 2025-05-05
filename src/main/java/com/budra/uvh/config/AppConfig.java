package com.budra.uvh.config;

import com.budra.uvh.controllers.LskResource;
import com.budra.uvh.filters.CorsFilter;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;
import com.budra.uvh.config.ManualDIProviderFactory;

/**
 * JAX-RS Application Configuration class.
 * Defines the base API path, registers resource classes (via package scanning),
 * registers filters, and sets up manual dependency injection bindings.
 */
@ApplicationPath("/api")
public class AppConfig extends ResourceConfig {

    public AppConfig() {
        // --- Package Scanning ---
        packages("com.budra.uvh.controllers", "com.budra.uvh.filters");
        System.out.println("Initializing AppConfig - Configuring Resource Scanning and Manual DI Binding...");
        // register(CorsFilter.class); // Keep if package scan doesn't reliably pick it up, or remove if it does.

        // --- Manual Dependency Injection Setup using HK2 AbstractBinder ---
        register(new AbstractBinder() {
            @Override
            protected void configure() {
                System.out.println("Binding ManualDIProviderFactory.LskResourceProvider to LskResource using HK2 RequestScope..."); // UPDATED Log
                // Bind the Factory (LskResourceProvider) to the Target Class (LskResource)
                // This means whenever Jersey needs an LskResource, it will call the get() method
                // of LskResourceProvider to obtain an instance.
                bindFactory(ManualDIProviderFactory.LskResourceProvider.class)
                        .to(LskResource.class)
                        .in(RequestScoped.class);
            }
        });

        System.out.println("JAX-RS (Jersey) Application Initialized. Base Path: /api. Resource scanning and manual DI configured.");
    }
}