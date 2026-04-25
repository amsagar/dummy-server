package com.pods.agent.exceptions;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@NoArgsConstructor
public class AgentServiceException extends RuntimeException {

    public static final String SOURCE_APPLICATION = "PODS-AI-AGENT";

    private ErrorDefinition errorDefinition;

    public AgentServiceException(ErrorDefinition errorDefinition) {
        this.errorDefinition = errorDefinition;
    }

    public AgentServiceException(ErrorDefinition errorDefinition, String message) {
        errorDefinition.setMessage(message);
        errorDefinition.setSourceApplication(SOURCE_APPLICATION);
        this.errorDefinition = errorDefinition;
    }

    public AgentServiceException(String message) {
        this.errorDefinition = ErrorDefinition.builder()
                .label("BAD_REQUEST")
                .code("400")
                .level(ErrorLevel.REQUEST)
                .severity(ErrorSeverity.NONFATAL)
                .message(message)
                .httpStatus(HttpStatus.BAD_REQUEST)
                .sourceApplication(SOURCE_APPLICATION)
                .build();
    }

    public AgentServiceException(String message, HttpStatus httpStatus) {
        this.errorDefinition = ErrorDefinition.builder()
                .label(httpStatus.getReasonPhrase())
                .code(String.valueOf(httpStatus.value()))
                .level(ErrorLevel.REQUEST)
                .severity(ErrorSeverity.NONFATAL)
                .message(message)
                .httpStatus(httpStatus)
                .sourceApplication(SOURCE_APPLICATION)
                .build();
    }

    public AgentServiceException(Exception e) {
        this.errorDefinition = ErrorDefinition.builder()
                .label("INTERNAL_SERVER_ERROR")
                .code("500")
                .level(ErrorLevel.SECURITY)
                .severity(ErrorSeverity.FATAL)
                .message(e.getMessage())
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .sourceApplication(SOURCE_APPLICATION)
                .build();
    }
}
