package com.pods.agent.exceptions;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ErrorDefinition> handleAgentServiceException(AgentServiceException e) {
        log.error("Service exception: {}", e.getErrorDefinition().getMessage());
        return ResponseEntity
                .status(e.getErrorDefinition().getHttpStatus())
                .body(e.getErrorDefinition());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDefinition> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntityFactory.badRequest("Field validation failed", errors);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorDefinition> handleDataAccessException(DataAccessException e) {
        log.error("Database error: {}", e.getMessage(), e);
        return ResponseEntityFactory.internalServerError("A database error occurred. Please try again later.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDefinition> handleException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntityFactory.internalServerError(e.getLocalizedMessage());
    }

}
