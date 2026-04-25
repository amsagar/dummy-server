package com.pods.agent.service;

import com.pods.agent.config.JwtUtil;
import com.pods.agent.domain.User;
import com.pods.agent.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> signup(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalStateException("User already exists with this email");
        }
        long now = System.currentTimeMillis();
        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(password))
                .createdAt(now)
                .updatedAt(now)
                .build();
        userRepository.save(user);
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "email", user.getEmail())
        );
    }

    public Map<String, Object> login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "email", user.getEmail())
        );
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
