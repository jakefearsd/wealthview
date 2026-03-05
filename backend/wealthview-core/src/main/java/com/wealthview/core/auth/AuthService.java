package com.wealthview.core.auth;

import com.wealthview.core.auth.dto.AuthResponse;
import com.wealthview.core.auth.dto.LoginRequest;
import com.wealthview.core.auth.dto.RegisterRequest;
import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidInviteCodeException;
import com.wealthview.persistence.entity.UserEntity;
import com.wealthview.persistence.repository.InviteCodeRepository;
import com.wealthview.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final InviteCodeRepository inviteCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository,
                       InviteCodeRepository inviteCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.inviteCodeRepository = inviteCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        log.info("User {} logged in for tenant {}", user.getId(), user.getTenantId());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEntityException("Email already registered");
        }

        var inviteCode = inviteCodeRepository.findByCode(request.inviteCode())
                .orElseThrow(() -> new InvalidInviteCodeException("Invalid invite code"));

        if (inviteCode.isConsumed()) {
            throw new InvalidInviteCodeException("Invite code has already been used");
        }

        if (inviteCode.isExpired()) {
            throw new InvalidInviteCodeException("Invite code has expired");
        }

        var user = new UserEntity(
                inviteCode.getTenant(),
                request.email(),
                passwordEncoder.encode(request.password()),
                "member"
        );
        user = userRepository.save(user);

        inviteCode.setConsumedBy(user);
        inviteCode.setConsumedAt(OffsetDateTime.now());
        inviteCodeRepository.save(inviteCode);

        log.info("User {} registered for tenant {}", user.getId(), user.getTenantId());
        return buildAuthResponse(user);
    }

    public AuthResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        var userId = jwtTokenProvider.extractUserId(refreshToken);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        var role = user.isSuperAdmin() ? "super_admin" : user.getRole();
        var accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getTenantId(), role, user.getEmail());
        var refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                role
        );
    }
}
