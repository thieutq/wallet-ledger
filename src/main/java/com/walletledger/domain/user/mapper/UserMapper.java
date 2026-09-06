package com.walletledger.domain.user.mapper;

import com.walletledger.domain.user.User;
import com.walletledger.domain.user.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    @Mapping(target = "status", expression = "java(user.getStatus().name())")
    UserResponse toResponse(User user);
}
