package com.walletledger.domain.user;

import com.walletledger.domain.account.Account;
import com.walletledger.domain.account.AccountRepository;
import com.walletledger.domain.account.AccountType;
import com.walletledger.domain.user.dto.LoginRequest;
import com.walletledger.domain.user.dto.LoginResponse;
import com.walletledger.domain.user.dto.RegisterRequest;
import com.walletledger.domain.user.dto.UserResponse;
import com.walletledger.domain.user.mapper.UserMapper;
import com.walletledger.infrastructure.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String CURRENCY = "COINS";

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        User user = User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build();

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }

        Account account = Account.builder()
                .ownerId(user.getId())
                .type(AccountType.PLAYER)
                .currency(CURRENCY)
                .balance(0)
                .held(0)
                .build();
        accountRepository.save(account);

        return userMapper.toResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account suspended");
        }

        String token = jwtService.generateToken(user.getId(), user.getRole().name());
        return new LoginResponse(token, userMapper.toResponse(user));
    }
}
