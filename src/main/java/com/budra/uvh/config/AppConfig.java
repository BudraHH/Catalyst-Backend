package com.budra.uvh.config;

import com.budra.uvh.controllers.RequestHandler;
import com.budra.uvh.filters.CorsFilter;
import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;

@ApplicationPath("/api")
public class AppConfig extends ResourceConfig {
    public AppConfig() {
        packages("com.budra.uvh.controllers");

        System.out.println("Initializing AppConfig - Configuring Manual DI Binding...");
        register(CorsFilter.class);

        register(new AbstractBinder() {
            @Override
            protected void configure() {
                System.out.println("Binding ManualDIProviderFactory.RequestHandlerProvider to RequestHandler using HK2 RequestScope...");

               bindFactory(ManualDIProviderFactory.RequestHandlerProvider.class)
                        .to(RequestHandler.class)
                        .in(RequestScoped.class);
            }
        });

        System.out.println("JAX-RS (Jersey) Application Initialized - MANUAL DI WIRED VIA FACTORY.");
    }
}