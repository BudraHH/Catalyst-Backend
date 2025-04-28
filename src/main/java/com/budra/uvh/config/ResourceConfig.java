package com.budra.uvh.config;

import com.budra.uvh.controllers.RequestHandler;
import com.budra.uvh.dao.GenericDao;
import com.budra.uvh.exception.*;
import com.budra.uvh.filters.CorsFilter;
import com.budra.uvh.git.AuthResource;
import com.budra.uvh.model.LskCounterRepository;
import com.budra.uvh.parser.GenericXmlDataParser;
import com.budra.uvh.service.GenericLskProcessingService;

// Import scopes and binder
import jakarta.inject.Singleton;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.process.internal.RequestScoped;

import jakarta.ws.rs.ApplicationPath;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationPath("/api")
public class ResourceConfig extends org.glassfish.jersey.server.ResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResourceConfig.class);

    public ResourceConfig() {
        log.info("Initializing JAX-RS ResourceConfig for MANUAL BINDING...");

        final String basePackage = "com.budra.uvh";
        packages(basePackage);
        log.info("Scanning base package for JAX-RS components: {}", basePackage);

        // --- FEATURE REGISTRATION ---
        log.info("Registering required Jersey features: MultiPartFeature, JacksonFeature");
        register(MultiPartFeature.class);
        register(JacksonFeature.class);

        log.info("Registering JAX-RS components explicitly...");
        register(CorsFilter.class);
        register(RequestHandler.class);
        register(AuthResource.class);

        // --- MANUAL DI BINDING using AbstractBinder ---
        log.info("Configuring MANUAL DI bindings using AbstractBinder...");
        register(new AbstractBinder() {
            @Override
            protected void configure() {
                log.debug("Binding components within AbstractBinder...");

                bind(LskCounterRepository.class).to(LskCounterRepository.class).in(Singleton.class);
                log.debug(" -> LskCounterRepository bound as Singleton");

                bind(GenericDao.class).to(GenericDao.class).in(Singleton.class);
                log.debug(" -> GenericDao bound as Singleton");

                bind(GenericXmlDataParser.class).to(GenericXmlDataParser.class).in(Singleton.class);
                log.debug(" -> GenericXmlDataParser bound as Singleton");

                bind(ConnectionManager.class).to(ConnectionManager.class).in(Singleton.class);
                log.debug(" -> ConnectionManager bound as Singleton");

                bind(GenericLskProcessingService.class).to(GenericLskProcessingService.class).in(RequestScoped.class);
                log.debug(" -> GenericLskProcessingService bound as RequestScoped");

                bind(RequestHandler.class).to(RequestHandler.class).in(RequestScoped.class);
                log.debug(" -> RequestHandler bound as RequestScoped");

//                bind(AuthResource.class).to(AuthResource.class).in(RequestScoped.class);
//                log.debug(" -> AuthResource bound as RequestScoped");

                log.debug("AbstractBinder configuration complete.");
            }
        });

        log.info("JAX-RS Application Initialized using manual binding.");
    }
}