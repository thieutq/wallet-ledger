package com.walletledger.domain.user.dto;

public record LoginResponse(
        String token,
        UserResponse user
) {
}
