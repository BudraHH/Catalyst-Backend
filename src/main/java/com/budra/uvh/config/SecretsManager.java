package com.budra.uvh.config;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class SecretsManager {

    private static final Logger log = LoggerFactory.getLogger(SecretsManager.class);

    private static final String GITHUB_CLIENT_ID_ENV_VAR = "UVH_GITHUB_CLIENT_ID";
    private static final String GITHUB_CLIENT_SECRET_ENV_VAR = "UVH_GITHUB_CLIENT_SECRET ";

    private static String githubClientId = null;
    private static String githubClientSecret = null;

    static {
        log.info("Loading secrets from environment variables...");

        // Load Client ID
        githubClientId = System.getenv(GITHUB_CLIENT_ID_ENV_VAR);
        if (githubClientId == null || githubClientId.trim().isEmpty()) {
            log.error("!!! CRITICAL: GitHub Client ID not found in environment variable '{}'. GitHub authentication will fail.", GITHUB_CLIENT_ID_ENV_VAR);
        } else {
            log.info("Loaded GITHUB_CLIENT_ID successfully from environment variable '{}'.", GITHUB_CLIENT_ID_ENV_VAR);
        }

        // Load Client Secret
        githubClientSecret = System.getenv(GITHUB_CLIENT_SECRET_ENV_VAR);
        if (githubClientSecret == null || githubClientSecret.trim().isEmpty()) {
            log.error("!!! CRITICAL: GitHub Client Secret not found in environment variable '{}'. GitHub authentication will fail.", GITHUB_CLIENT_SECRET_ENV_VAR);
        } else {
            log.info("Loaded GITHUB_CLIENT_SECRET successfully from environment variable '{}'.", GITHUB_CLIENT_SECRET_ENV_VAR);
        }
    }

    /**
     * Gets the loaded GitHub Client ID.
     * @return The Client ID string, or null if it failed to load.
     */
    @Nullable // Optional annotation
    public static String getGithubClientId() {
        if (githubClientId == null) {
            log.warn("Attempted to get GitHub Client ID, but it was not loaded successfully during startup.");
        }
        return githubClientId;
    }

    /**
     * Gets the loaded GitHub Client Secret.
     * @return The Client Secret string, or null if it failed to load.
     */
    @Nullable // Optional annotation
    public static String getGithubClientSecret() {
        if (githubClientSecret == null) {
            log.warn("Attempted to get GitHub Client Secret, but it was not loaded successfully during startup.");
        }
        return githubClientSecret;
    }

    private SecretsManager() {}
}