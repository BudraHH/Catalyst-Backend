package com.budra.uvh.config;

// Import the RENAMED resource class
import com.budra.uvh.controllers.LskResource;
// Import other required classes
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
@ApplicationPath("/api") // Base path for all API endpoints (e.g., /api/auth/github/exchange-code)
public class AppConfig extends ResourceConfig {

    public AppConfig() {
        // --- Package Scanning ---
        // Automatically scans these packages for JAX-RS resources (@Path) and providers (@Provider)
        // This should find LskResource, GitHubAuthResource, AuthResource (if kept), CorsFilter etc.
        packages("com.budra.uvh.controllers", "com.budra.uvh.filters");
        // Note: If you have exception mappers or other providers in different packages, add them here.

        System.out.println("Initializing AppConfig - Configuring Resource Scanning and Manual DI Binding...");

        // --- Explicit Filter Registration (Optional if package scanning works) ---
        // Can explicitly register filters if needed, though package scanning usually suffices for @Provider
        // register(CorsFilter.class); // Keep if package scan doesn't reliably pick it up, or remove if it does.

        // --- Manual Dependency Injection Setup using HK2 AbstractBinder ---
        // This tells Jersey how to create instances of LskResource when needed for a request.
        register(new AbstractBinder() {
            @Override
            protected void configure() {
                System.out.println("Binding ManualDIProviderFactory.LskResourceProvider to LskResource using HK2 RequestScope..."); // UPDATED Log

                // Bind the Factory (LskResourceProvider) to the Target Class (LskResource)
                // This means whenever Jersey needs an LskResource, it will call the get() method
                // of LskResourceProvider to obtain an instance.
                bindFactory(ManualDIProviderFactory.LskResourceProvider.class) // <<< UPDATED Provider Class
                        .to(LskResource.class)                                // <<< UPDATED Target Class
                        .in(RequestScoped.class); // Scope: New instance per HTTP request (suitable for resource classes)
                // Consider .in(Singleton.class) if the resource has no per-request state
            }
        });

        System.out.println("JAX-RS (Jersey) Application Initialized. Base Path: /api. Resource scanning and manual DI configured.");
    }
}