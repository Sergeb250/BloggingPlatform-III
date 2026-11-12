package com.blogPlatform.III.blog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        int status,
        String message,
        T data,
        Map<String, String> errors,
        String path,
        LocalDateTime timestamp
) {
    public static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return success(HttpStatus.OK, message, data);
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(String message, T data) {
        return success(HttpStatus.CREATED, message, data);
    }

    public static ResponseEntity<ApiResponse<Void>> error(HttpStatusCode status, String message, String path) {
        return error(status, message, null, path);
    }

    public static ResponseEntity<ApiResponse<Void>> error(HttpStatusCode status, String message,
                                                          Map<String, String> errors, String path) {
        ApiResponse<Void> body = new ApiResponse<>(false, status.value(), message, null, errors, path, LocalDateTime.now());
        return ResponseEntity.status(status).body(body);
    }

    private static <T> ResponseEntity<ApiResponse<T>> success(HttpStatus status, String message, T data) {
        ApiResponse<T> body = new ApiResponse<>(true, status.value(), message, data, null, null, LocalDateTime.now());
        return ResponseEntity.status(status).body(body);
    }
}
