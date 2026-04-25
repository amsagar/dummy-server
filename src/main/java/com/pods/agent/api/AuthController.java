package com.pods.agent.api;

import com.pods.agent.exceptions.ResponseEntityFactory;
import com.pods.agent.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication APIs")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    @Operation(summary = "Create user account and return JWT")
    public ResponseEntity<?> signup(@Valid @RequestBody AuthRequest request) {
        try {
            return ResponseEntity.ok(authService.signup(request.email(), request.password()));
        } catch (IllegalStateException e) {
            return ResponseEntityFactory.resourceConflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.badRequest(e.getMessage());
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Login and return JWT")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest request) {
        try {
            return ResponseEntity.ok(authService.login(request.email(), request.password()));
        } catch (IllegalArgumentException e) {
            return ResponseEntityFactory.unAuthorized(e.getMessage());
        }
    }

    public record AuthRequest(@Email @NotBlank String email,
                              @NotBlank String password) {
    }
}
