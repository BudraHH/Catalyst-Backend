package com.budra.uvh.controllers;

import com.budra.uvh.dto.AuthResponse;
import com.budra.uvh.dto.Credentials;
import com.budra.uvh.dto.MessageResponse;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.mindrot.jbcrypt.BCrypt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

    // Stores email -> BCrypt hashed password
    private static final Map<String, String> userCredentials = new ConcurrentHashMap<>();
    // Stores active session token -> email
    private static  Map<String, String> activeSessions;

    public static Map<String, String> getActiveSessions() {
        return activeSessions;
    }

    static {
        log.info("Initializing dummy user credentials (Hashing Passwords)...");

        userCredentials.put("hari@zoho.com", BCrypt.hashpw("hari", BCrypt.gensalt()));
        userCredentials.put("budra@zoho.com", BCrypt.hashpw("hari", BCrypt.gensalt()));
        log.info("Dummy credentials initialized.");
        activeSessions = new ConcurrentHashMap<>();
    }

    @POST
    @Path("/signin")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response signIn(Credentials credentials) {
        log.info("Received sign-in request for email: {}", credentials != null ? credentials.email : "null");

        if (credentials == null || credentials.email == null || credentials.email.trim().isEmpty()
                || credentials.password == null || credentials.password.isEmpty()) {
            log.warn("Sign-in attempt with missing email or password.");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse("Email and password are required."))
                    .build();
        }

        String email = credentials.email.trim().toLowerCase();
        String providedPassword = credentials.password;

        String storedHash = userCredentials.get(email);
        if (storedHash == null) {
            log.warn("Sign-in attempt failed: User not found for email {}", email);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new MessageResponse("Invalid email or password."))
                    .build();
        }

        if (BCrypt.checkpw(providedPassword, storedHash)) {

            log.info("Password verified for user {}", email);

            String token = UUID.randomUUID().toString();

            activeSessions.put(token, email);
            log.info("Generated session token {} for user {}", token, email);
            log.info("");
            log.info("Active sessions map : " +  getActiveSessions());
            log.info("");

            return Response.status(Response.Status.OK)
                    .entity(new AuthResponse("Sign-in successful.", token))
                    .build();
        } else {

            log.warn("Sign-in attempt failed: Invalid password for email {}", email);

            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new MessageResponse("Invalid email or password."))
                    .build();
        }
    }

    @POST
    @Path("/signout")
    public Response signOut(@HeaderParam("Authorization") String token) {
        log.info("Received sign-out request for token: {}", token != null ? token.substring(0, Math.min(8, token.length()))+"..." : "null"); // Log partial token

        if (token == null || token.trim().isEmpty()) {
            log.warn("Sign-out attempt with missing token.");
            // Return 400 Bad Request as the required header is missing
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse("Authentication token is required."))
                    .build();
        }


        String removedEmail = activeSessions.remove(token.trim());

        if (removedEmail != null) {
            log.info("Successfully signed out user {} associated with token.", removedEmail);
        } else {
            log.warn("Sign-out request received for an invalid or expired token.");

        }

        return Response.ok()
                .entity(new MessageResponse("Sign-out successful."))
                .build();
    }

    public static String validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        return activeSessions.get(token.trim());
    }
}