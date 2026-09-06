package com.walletledger.shared.response;

import lombok.Getter;

/**
 * Uniform envelope for every controller response, success or error.
 */
@Getter
public final class ApiResponse<T> {

    private final boolean success;
    private final int statusCode;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, int statusCode, String message, T data) {
        this.success = success;
        this.statusCode = statusCode;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> success(int statusCode, T data) {
        return new ApiResponse<>(true, statusCode, null, data);
    }

    public static <T> ApiResponse<T> error(int statusCode, String message) {
        return new ApiResponse<>(false, statusCode, message, null);
    }

    public static <T> ApiResponse<T> error(int statusCode, String message, T data) {
        return new ApiResponse<>(false, statusCode, message, data);
    }
}
