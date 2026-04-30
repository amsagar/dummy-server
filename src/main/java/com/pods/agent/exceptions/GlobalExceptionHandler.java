package com.pods.agent.exceptions;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AgentServiceException.class)
    public ResponseEntity<ErrorDefinition> handleAgentServiceException(AgentServiceException e, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("Suppressed AgentServiceException on SSE endpoint {}: {}", request.getRequestURI(), e.getErrorDefinition().getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Service exception: {}", e.getErrorDefinition().getMessage());
        return ResponseEntity
                .status(e.getErrorDefinition().getHttpStatus())
                .body(e.getErrorDefinition());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDefinition> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("Suppressed validation exception on SSE endpoint {}: {}", request.getRequestURI(), ex.getMessage());
            return ResponseEntity.noContent().build();
        }
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntityFactory.badRequest("Field validation failed", errors);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorDefinition> handleDataAccessException(DataAccessException e, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("Suppressed DataAccessException on SSE endpoint {}: {}", request.getRequestURI(), e.getMessage());
            return ResponseEntity.noContent().build();
        }
        log.error("Database error: {}", e.getMessage(), e);
        return ResponseEntityFactory.internalServerError("A database error occurred. Please try again later.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDefinition> handleException(Exception e, HttpServletRequest request) {
        boolean disconnectedClient = isDisconnectedClientError(e);
        boolean sseRequest = isSseRequest(request);
        if (sseRequest || disconnectedClient) {
            log.warn("Suppressed exception on SSE endpoint {}: {}", request.getRequestURI(), e.toString());
            return ResponseEntity.noContent().build();
        }
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntityFactory.internalServerError(e.getLocalizedMessage());
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) return true;
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/stream");
    }

    private static boolean isDisconnectedClientError(Exception e) {
        if (e == null) return false;
        if (e instanceof AsyncRequestNotUsableException) return true;
        String message = e.getMessage();
        if (message != null && message.toLowerCase().contains("disconnected client")) return true;
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null && causeMessage.toLowerCase().contains("broken pipe")) return true;
            cause = cause.getCause();
        }
        return false;
    }

}
