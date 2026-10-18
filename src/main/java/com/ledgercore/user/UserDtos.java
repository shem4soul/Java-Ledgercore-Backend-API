package com.ledgercore.user;

import com.ledgercore.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class UserDtos {
    private UserDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 10, max = 72, message = "password must be 10-72 characters")
            @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$", message = "password needs upper, lower case and a digit")
            String password,
            @NotBlank @Size(max = 150) String fullName) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

    public record UserResponse(UUID id, String email, String fullName, Set<Role> roles, boolean enabled, Instant createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRoles(), u.isEnabled(), u.getCreatedAt());
        }
    }

    public record EnabledRequest(boolean enabled) {}

    public record RolesRequest(@NotEmpty Set<Role> roles) {}
}
