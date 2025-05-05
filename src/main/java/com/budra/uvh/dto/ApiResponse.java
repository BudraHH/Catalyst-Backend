package com.budra.uvh.dto;

public class ApiResponse {
    public String message;
    public String data;
    public String error;

    // Constructor for success responses
    public ApiResponse(String message, String data) {
        this.message = message;
        this.data = data;
        this.error = null;
    }

    // Constructor for error responses
    public ApiResponse(String error) {
        this.message = null;
        this.data = null;
        this.error = error;
    }

    public ApiResponse() {}

    // Getters and Setters (optional but good practice)
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}