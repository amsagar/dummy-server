package com.pods.agent.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public class ResponseEntityFactory {

    private static final String SOURCE_APPLICATION = AgentServiceException.SOURCE_APPLICATION;

    private ResponseEntityFactory() {}

    public static ResponseEntity<ErrorDefinition> badRequest(String message) {
        return ResponseEntity.badRequest().body(ErrorDefinition.builder()
                .label("Bad Request")
                .code("400")
                .level(ErrorLevel.REQUEST)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.BAD_REQUEST)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> badRequest(String message, Map<String, String> causedBy) {
        return ResponseEntity.badRequest().body(ErrorDefinition.builder()
                .label("Bad Request")
                .code("400")
                .level(ErrorLevel.REQUEST)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.BAD_REQUEST)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .causedBy(causedBy)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorDefinition.builder()
                .label("Not Found")
                .code("404")
                .level(ErrorLevel.RESOURCE)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.NOT_FOUND)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> resourceConflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorDefinition.builder()
                .label("Conflict")
                .code("409")
                .level(ErrorLevel.CONFLICT)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.CONFLICT)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> unAuthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorDefinition.builder()
                .label("Unauthorized")
                .code("401")
                .level(ErrorLevel.SECURITY)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.UNAUTHORIZED)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorDefinition.builder()
                .label("Forbidden")
                .code("403")
                .level(ErrorLevel.SECURITY)
                .severity(ErrorSeverity.NONFATAL)
                .httpStatus(HttpStatus.FORBIDDEN)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }

    public static ResponseEntity<ErrorDefinition> internalServerError(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorDefinition.builder()
                .label("Internal Server Error")
                .code("500")
                .level(ErrorLevel.BUSINESS)
                .severity(ErrorSeverity.FATAL)
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .message(message)
                .sourceApplication(SOURCE_APPLICATION)
                .build());
    }
}
