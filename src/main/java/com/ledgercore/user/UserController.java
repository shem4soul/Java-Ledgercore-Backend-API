package com.ledgercore.user;

import com.ledgercore.common.BusinessException;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return users.findById(principal.id()).map(UserResponse::from)
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "User not found"));
    }
}
