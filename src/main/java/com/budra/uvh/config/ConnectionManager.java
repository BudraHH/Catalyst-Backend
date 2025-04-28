package com.budra.uvh.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.inject.Singleton;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;


@Service
//@Singleton

public class ConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);
    private static final String CONFIG_FILE = "/uvh_config.properties"; // Ensure this is in src/main/resources

    private static HikariDataSource dataSource; // Keep this static

    // Static initializer block: runs once when the class is loaded
    static {
        log.info("Initializing HikariCP connection pool...");
        Properties props = null; // Initialize to null
        HikariConfig config = null; // Initialize to null
        try {
            props = loadProperties();
            config = new HikariConfig();

            // --- Log the exact values being used ---
            String dbUrl = props.getProperty("db.url", "jdbc:postgresql://localhost:5432/postgres");
            String dbUser = props.getProperty("db.user", "postgres");
            // Avoid logging password in production logs if possible, but useful for debugging:
            String dbPassword = props.getProperty("db.password", "1234");
            String dbDriver = props.getProperty("db.driver", "org.postgresql.Driver");
            log.info("Attempting to configure HikariCP with:");
            log.info("  db.url = {}", dbUrl);
            log.info("  db.user = {}", dbUser);
            // log.info("  db.password = <HIDDEN>"); // Better for production
            log.info("  db.password = {}", dbPassword); // Use for local debugging only
            log.info("  db.driver = {}", dbDriver);
            // --- End logging ---

            // Configure HikariCP from properties
            config.setJdbcUrl(dbUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPassword);
            config.setDriverClassName(dbDriver);

            // Pooling settings (keep the parsing)
            config.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.pool.maxSize", "10")));
            config.setMinimumIdle(Integer.parseInt(props.getProperty("db.pool.minIdle", "2")));
            config.setIdleTimeout(Long.parseLong(props.getProperty("db.pool.idleTimeout", "600000")));
            config.setConnectionTimeout(Long.parseLong(props.getProperty("db.pool.connectionTimeout", "30000")));
            config.setMaxLifetime(Long.parseLong(props.getProperty("db.pool.maxLifetime", "1800000")));
            config.setAutoCommit(Boolean.parseBoolean(props.getProperty("db.pool.autoCommit", "true")));

            log.info("HikariConfig object created. Attempting to initialize HikariDataSource...");
            dataSource = new HikariDataSource(config); // <<< Point of potential failure
            log.info("HikariCP DataSource initialized successfully for JDBC URL: {}", config.getJdbcUrl());

        } catch (Exception e) {
            // Log the full stack trace of the exception 'e'
            log.error("!!! CRITICAL: Failed to initialize HikariCP DataSource !!!", e);
            // Optionally log config again if helpful
            if (config != null) {
                log.error("Failed configuration details: URL={}, User={}, Driver={}", config.getJdbcUrl(), config.getUsername(), config.getDriverClassName());
            } else if (props != null) {
                log.error("Failed configuration properties: {}", props);
            } else {
                log.error("Properties could not be loaded.");
            }
        }
    }

    // loadProperties method remains the same as you provided
    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = ConnectionManager.class.getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                log.warn("!!! {} not found on classpath. Using hardcoded defaults (NOT recommended for production).", CONFIG_FILE);
                // Set some basic defaults if file not found
                props.setProperty("db.url", "jdbc:postgresql://localhost:5432/postgres"); // Example DB name
                props.setProperty("db.user", "postgres"); // Corrected property name
                props.setProperty("db.password", "1234"); // Corrected property name
                props.setProperty("db.driver", "org.postgresql.Driver");
                props.setProperty("db.pool.maxSize", "5");
                return props;
            }
            props.load(input);
            log.info("Loaded database configuration from {}", CONFIG_FILE);
        } catch (IOException e) {
            log.error("!!! Error loading database configuration file: {}. Using defaults.", CONFIG_FILE, e);
            // Set defaults on error too
            props.setProperty("db.url", "jdbc:postgresql://localhost:5432/postgres");
            props.setProperty("db.user", "postgres"); // Corrected property name
            props.setProperty("db.password", "1234"); // Corrected property name
            props.setProperty("db.driver", "org.postgresql.Driver");
            props.setProperty("db.pool.maxSize", "5");
        }
        return props;
    }

    /**
     * Gets a connection from the configured HikariCP connection pool.
     *
     * @return A database connection.
     * @throws SQLException if the DataSource was not initialized or fails to provide a connection.
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            log.error("Datasource is null! Initialization likely failed during class loading.");
            throw new SQLException("Database connection pool is not available. Check application startup logs.");
        }
        log.trace("Requesting connection from pool...");
        Connection conn = dataSource.getConnection(); // This might block if pool is exhausted
        log.trace("Connection obtained from pool.");
        return conn;
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ConnectionManager() {
        // Utility class should not be instantiated
    }

    /**
     * Optional: Add a shutdown hook or a manual method to close the DataSource
     * when the application stops, especially in non-server environments.
     * For web applications, the server shutdown usually handles this implicitly if managed correctly.
     */
    public static void closeDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing HikariCP DataSource...");
            dataSource.close();
            log.info("HikariCP DataSource closed.");
        }
    }
}