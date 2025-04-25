package com.budra.uvh.config;

// ... other imports ...
import java.util.function.Supplier; // <<< CHANGE IMPORT

import com.budra.uvh.controllers.RequestHandler;
import com.budra.uvh.model.LskCounterRepository;
import com.budra.uvh.service.LskResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ... imports for RequestHandler, LskResolution, LskCounterRepository ...

public class ManualDIProviderFactory {
    private static final Logger log = LoggerFactory.getLogger(ManualDIProviderFactory.class);
    private static final LskCounterRepository SINGLETON_REPOSITORY = createRepositoryInstance();

    private static LskCounterRepository createRepositoryInstance() {
        log.info("ManualDIProviderFactory: Creating SINGLETON instance of LskCounterRepository.");
        return new LskCounterRepository();
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
    public static class RequestHandlerProvider implements Supplier<RequestHandler> { // <<< CHANGE HERE
        @Override
        public RequestHandler get() {
            log.debug("ManualDIProviderFactory: Providing new RequestHandler instance.");
            LskResolution resolutionService = new LskResolutionProvider().get();
            return new RequestHandler(resolutionService);
        }
    }

    private ManualDIProviderFactory() {}
}