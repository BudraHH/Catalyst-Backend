package com.budra.uvh.service;

import com.budra.uvh.dbConfig.ConnectionManager;
// Ensure correct package for repository
import com.budra.uvh.model.LskCounterRepository;
import com.budra.uvh.exception.PlaceholderFormatException;
// Assuming LskGenerationException might be thrown from repo or needed for future catches
import com.budra.uvh.exception.LskGenerationException;
import com.budra.uvh.utils.PlaceHolderInfo;
import com.budra.uvh.utils.XmlUtils;

// --- Import annotations ---
import jakarta.enterprise.context.RequestScoped; // CDI standard scope, or use Jersey's equivalent if needed
import jakarta.inject.Inject;                   // Standard injection annotation

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

// --- ADD Scope Annotation ---
@RequestScoped // A new instance will be created for each request
public class LskResolution {
    private static final Logger log = LoggerFactory.getLogger(LskResolution.class);

    // Dependency field - make final if using constructor injection
    private final LskCounterRepository counterRepository;

    // --- ADD @Inject Annotation to the Constructor ---
    // HK2 will use this constructor to create instances,
    // automatically providing the (likely Singleton) LskCounterRepository.
    @Inject
    public LskResolution(LskCounterRepository counterRepository) {
        log.debug("LskResolution instance created by DI container (@RequestScoped via @Inject constructor).");
        if (counterRepository == null) {
            // Safeguard check - DI should prevent this if configured correctly
            throw new IllegalStateException("DI framework failed to inject LskCounterRepository");
        }
        this.counterRepository = counterRepository;
    }

    // Default no-arg constructor removed as the @Inject constructor is now primary

    // Add LskGenerationException to throws clause as it's thrown within the method
    public String processAndResolveXml(String inputXml) throws PlaceholderFormatException, LskGenerationException {
        log.info("Starting LSK resolution process for provided XML.");

        // Check on the injected dependency can be removed as the constructor guarantees it
        // if (this.counterRepository == null) { ... }

        Map<String, PlaceHolderInfo> uniquePlaceholders = XmlUtils.findUniquePlaceholders(inputXml);

        if (uniquePlaceholders.isEmpty()) {
            log.info("No LSK placeholders found in the input XML. Returning original content.");
            return inputXml;
        }

        log.info("Found {} unique LSK placeholders to resolve.", uniquePlaceholders.size());

        Map<String, String> resolvedMappings = new HashMap<>();
        Connection connection = null;
        boolean transactionSuccess = false;

        try {
            connection = ConnectionManager.getConnection(); // Assuming static access to connection manager
            if (connection == null) {
                // Handle inability to get a connection more explicitly
                log.error("Failed to acquire database connection from ConnectionManager.");
                throw new LskGenerationException("Could not connect to the database service.");
            }
            connection.setAutoCommit(false);
            log.debug("Database transaction started for LSK generation.");

            for (Map.Entry<String, PlaceHolderInfo> entry : uniquePlaceholders.entrySet()) {
                String placeHolderKey = entry.getKey();
                PlaceHolderInfo info = entry.getValue();

                // Use the injected counterRepository field
                long generatedValue = this.counterRepository.getAndReserveNextValueBlock(connection, info.getTableName(), info.getColumnName(), 1);
                String resolvedLsk = info.buildResolvedLsk(generatedValue);

                resolvedMappings.put(placeHolderKey, resolvedLsk);
                log.debug("Mapped placeholder '{}' to resolved LSK '{}'", placeHolderKey, resolvedLsk);
            }

            connection.commit();
            transactionSuccess = true;
            log.debug("Database transaction committed successfully.");

        } catch (SQLException e) {
            log.error("SQL error during LSK generation transaction: {}", e.getMessage(), e);
            // Re-throw as LskGenerationException for consistent error handling type
            throw new LskGenerationException("Database error during LSK generation: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) { // Catch potential validation errors from repo
            log.warn("Invalid argument during LSK generation: {}", e.getMessage());
            // Re-throw as LskGenerationException
            throw new LskGenerationException("Invalid data provided for LSK generation: " + e.getMessage(), e);
        }
        // LskGenerationException is already explicitly thrown, no need for separate catch unless specific handling needed
        finally {
            // --- Finally block remains crucial for cleanup ---
            if (connection != null) {
                try {
                    if (!transactionSuccess) {
                        try {
                            log.warn("Rolling back transaction due to error during LSK resolution.");
                            connection.rollback();
                            log.info("Transaction rollback completed.");
                        } catch (SQLException exRb) {
                            log.error("!!! CRITICAL: Failed to rollback transaction !!!", exRb);
                        }
                    }
                } finally { // Nested finally to ensure close attempt always happens
                    try {
                        connection.setAutoCommit(true); // Reset auto-commit before closing/returning to pool
                    } catch (SQLException exAc) {
                        log.warn("Failed to reset autoCommit on connection.", exAc);
                    }
                    try {
                        connection.close();
                        log.debug("Database connection closed/returned to pool.");
                    } catch (SQLException exCl) {
                        log.error("Failed to close database connection.", exCl);
                    }
                }
            }
        } // End outer finally

        log.debug("Replacing placeholders in XML content...");
        String resolvedXml = XmlUtils.replacePlaceholders(inputXml, resolvedMappings);

        log.info("LSK resolution service finished successfully.");
        return resolvedXml;
    }
}