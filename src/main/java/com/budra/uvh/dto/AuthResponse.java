package com.budra.uvh.dto;

public class AuthResponse {
    public String message;
    public String token;
    public AuthResponse() {}

    public AuthResponse(String message, String token) {
        this.message = message;
        this.token = token;
    }


    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}

