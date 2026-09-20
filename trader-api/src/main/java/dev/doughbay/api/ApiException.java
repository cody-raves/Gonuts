package dev.doughbay.api;

/** API failure with any secrets already redacted from the message. */
public class ApiException extends Exception {
    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
