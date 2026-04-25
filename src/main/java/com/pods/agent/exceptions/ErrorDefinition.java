package com.pods.agent.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorDefinition {

    private String label;
    private String code;
    private ErrorLevel level;
    private ErrorSeverity severity;
    private String message;
    private HttpStatus httpStatus;
    private String sourceApplication;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> causedBy;
}
