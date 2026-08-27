package com.ethanluong.ticketreservation.api.controller;

import com.ethanluong.ticketreservation.api.dto.AuthRequest;
import com.ethanluong.ticketreservation.api.dto.AuthResponse;
import com.ethanluong.ticketreservation.api.dto.RegisterRequest;
import com.ethanluong.ticketreservation.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(authService.register(req));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody AuthRequest req) {
        return authService.login(req);
    }
}
