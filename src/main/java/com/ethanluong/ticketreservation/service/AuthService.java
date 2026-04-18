package com.ethanluong.ticketreservation.service;

import com.ethanluong.ticketreservation.api.dto.AuthRequest;
import com.ethanluong.ticketreservation.api.dto.AuthResponse;
import com.ethanluong.ticketreservation.api.dto.RegisterRequest;
import com.ethanluong.ticketreservation.domain.entity.User;
import com.ethanluong.ticketreservation.domain.repository.UserRepository;
import com.ethanluong.ticketreservation.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .displayName(req.displayName())
                .build();
        user = userRepository.save(user);
        return AuthResponse.bearer(
                jwtService.issue(user.getId(), user.getEmail()),
                jwtService.expirationSeconds()
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest req) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        } catch (Exception ex) {
            throw new BadCredentialsException("Invalid credentials");
        }
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return AuthResponse.bearer(
                jwtService.issue(user.getId(), user.getEmail()),
                jwtService.expirationSeconds()
        );
    }
}
