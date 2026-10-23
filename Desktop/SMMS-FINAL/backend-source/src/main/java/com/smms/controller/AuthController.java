package com.smms.controller;

import com.smms.dto.request.LoginRequest;
import com.smms.dto.response.AuthResponse;
import com.smms.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestHeader("X-Refresh-Token") String token) {
        return ResponseEntity.ok(authService.refresh(token));
    }

    /**
     * Ends the caller's login session (and the one of X-Refresh-Token when it is the same user's).
     * Always 204, also without or with an invalid token, so it reveals nothing.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken) {
        try {
            authService.logout(authorization, refreshToken);
        } catch (RuntimeException e) {
            log.warn("Logout bookkeeping failed ({})", e.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }
}
