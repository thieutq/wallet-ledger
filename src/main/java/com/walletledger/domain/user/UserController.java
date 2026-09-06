package com.walletledger.domain.user;

import com.walletledger.domain.user.dto.UserResponse;
import com.walletledger.domain.user.mapper.UserMapper;
import com.walletledger.shared.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "Authenticated user profile")
public class UserController {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Operation(summary = "Get the authenticated user's profile")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(Authentication authentication) {
        User user = userRepository.findById(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK.value(), userMapper.toResponse(user)));
    }
}
