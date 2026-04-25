package com.pods.agent.service;

import com.pods.agent.config.JwtUtil;
import com.pods.agent.domain.User;
import com.pods.agent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void signupCreatesUserAndReturnsToken() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByEmail("a@b.com")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuthService service = new AuthService(repo, new BCryptPasswordEncoder(), new JwtUtil("test-secret-key-for-auth-service-1234567890", 100000));

        Map<String, Object> res = service.signup("a@b.com", "password123");

        assertTrue(res.containsKey("token"));
        Map<?, ?> user = (Map<?, ?>) res.get("user");
        assertEquals("a@b.com", user.get("email"));
    }

    @Test
    void loginRejectsInvalidPassword() {
        UserRepository repo = mock(UserRepository.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        when(repo.findByEmail("a@b.com")).thenReturn(Optional.of(User.builder()
                .id("u1")
                .email("a@b.com")
                .passwordHash(encoder.encode("correct-password"))
                .build()));
        AuthService service = new AuthService(repo, encoder, new JwtUtil("test-secret-key-for-auth-service-1234567890", 100000));

        assertThrows(IllegalArgumentException.class, () -> service.login("a@b.com", "wrong-password"));
    }
}
