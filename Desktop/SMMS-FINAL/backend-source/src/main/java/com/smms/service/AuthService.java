package com.smms.service;

import com.smms.dto.request.LoginRequest;
import com.smms.dto.response.AuthResponse;
import com.smms.entity.Tenant;
import com.smms.entity.User;
import com.smms.exception.AuthException;
import com.smms.repository.TenantRepository;
import com.smms.repository.UserRepository;
import com.smms.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepo.findByUsernameAndDeletedAtIsNull(req.getUsername())
            .orElseThrow(() -> new AuthException("Invalid credentials"));

        if (!user.isActive()) throw new AuthException("Account disabled");
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new AuthException("Invalid credentials");
        }

        user.setLastLogin(Instant.now());
        userRepo.save(user);

        String token   = jwtService.generateToken(user);
        String refresh = jwtService.generateRefreshToken(user);

        Tenant tenant = user.getTenantId() != null
            ? tenantRepo.findById(user.getTenantId()).orElse(null) : null;

        return AuthResponse.builder()
            .token(token)
            .refreshToken(refresh)
            .role(user.getRole())
            .userId(user.getId())
            .username(user.getUsername())
            .fullName(user.getFullName())
            .fullNameAr(user.getFullNameAr())
            .centerId(user.getCenter()   != null ? user.getCenter().getId()   : null)
            .centerName(user.getCenter() != null ? user.getCenter().getName() : null)
            .centerNameAr(user.getCenter() != null ? user.getCenter().getNameAr() : null)
            .tenantId(tenant   != null ? tenant.getId()   : null)
            .tenantName(tenant != null ? tenant.getName() : null)
            .build();
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        String username = jwtService.extractUsername(refreshToken);
        User user = userRepo.findByUsernameAndDeletedAtIsNull(username)
            .orElseThrow(() -> new AuthException("Invalid token"));
        if (!jwtService.isTokenValid(refreshToken, user)) throw new AuthException("Token expired");
        return AuthResponse.builder()
            .token(jwtService.generateToken(user))
            .refreshToken(refreshToken)
            .role(user.getRole())
            .userId(user.getId())
            .username(user.getUsername())
            .fullName(user.getFullName())
            .fullNameAr(user.getFullNameAr())
            .build();
    }
}
