package com.ethanluong.ticketreservation.api.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/**
 * RFC 7807 (ProblemDetail) responses for all exception paths. Types are URIs
 * the client can key off of — stable contract instead of message-string parsing.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://ticket-reservation.example/errors/";

    @ExceptionHandler(SeatNotAvailableException.class)
    public ProblemDetail onSeatNotAvailable(SeatNotAvailableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(TYPE_BASE + "seat-not-available"));
        pd.setTitle("Seat not available");
        pd.setProperty("seatId", ex.getSeatId());
        pd.setProperty("timestamp", OffsetDateTime.now());
        return pd;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Another request modified this seat first. Retry with fresh state.");
        pd.setType(URI.create(TYPE_BASE + "optimistic-lock"));
        pd.setTitle("Concurrent modification");
        pd.setProperty("timestamp", OffsetDateTime.now());
        return pd;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail onNotFound(ResourceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create(TYPE_BASE + "not-found"));
        pd.setTitle("Resource not found");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setType(URI.create(TYPE_BASE + "validation"));
        pd.setTitle("Validation failed");
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create(TYPE_BASE + "validation"));
        pd.setTitle("Validation failed");
        return pd;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail onBadCredentials(BadCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        pd.setType(URI.create(TYPE_BASE + "bad-credentials"));
        pd.setTitle("Authentication failed");
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        pd.setType(URI.create(TYPE_BASE + "access-denied"));
        pd.setTitle("Forbidden");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        pd.setType(URI.create(TYPE_BASE + "internal"));
        pd.setTitle("Internal server error");
        return pd;
    }
}
