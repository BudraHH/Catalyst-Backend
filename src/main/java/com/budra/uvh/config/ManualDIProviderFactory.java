package com.budra.uvh.config;

import java.util.function.Supplier;

import com.budra.uvh.controllers.LskResource;
import com.budra.uvh.model.LskRepository;
import com.budra.uvh.service.LskResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManualDIProviderFactory {
    private static final Logger log = LoggerFactory.getLogger(ManualDIProviderFactory.class);
    private static final LskRepository SINGLETON_REPOSITORY = createRepositoryInstance();

    private static LskRepository createRepositoryInstance() {
        log.info("ManualDIProviderFactory: Creating SINGLETON instance of LskRepository.");
        return new LskRepository();
    }

    // --- Factory for LskResolution (implements Supplier) ---
    public static class LskResolutionProvider implements Supplier<LskResolution> { // <<< CHANGE HERE
        @Override
        public LskResolution get() {
            log.debug("ManualDIProviderFactory: Providing new LskResolution instance.");
            return new LskResolution(SINGLETON_REPOSITORY);
        }
    }

    // --- Factory for RequestHandler (implements Supplier) ---
    public static class LskResourceProvider implements Supplier<LskResource> { // <<< CHANGE HERE
        @Override
        public LskResource get() {
            log.debug("ManualDIProviderFactory: Providing new RequestHandler instance.");
            LskResolution resolutionService = new LskResolutionProvider().get();
            return new LskResource(resolutionService);
        }
    }

    private ManualDIProviderFactory() {}
}