package com.walletledger.domain.user.dto;

import java.time.LocalDateTime;

public record UserResponse(
        String id,
        String username,
        String role,
        String status,
        LocalDateTime createdAt
) {
}
