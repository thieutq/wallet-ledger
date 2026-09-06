package com.walletledger.domain.user;

import com.walletledger.domain.user.dto.LoginRequest;
import com.walletledger.domain.user.dto.RegisterRequest;
import com.walletledger.domain.user.dto.UserResponse;
import com.walletledger.domain.user.mapper.UserMapper;
import com.walletledger.infrastructure.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test — UserRepository/PasswordEncoder/JwtService/UserMapper all
 * mocked, no Spring context, no DB. Run by Surefire (mvn test).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AuthService authService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Test
    void registerHashesPasswordBeforeSaving() {
        RegisterRequest request = new RegisterRequest("alice", "plaintext-password");
        when(passwordEncoder.encode("plaintext-password")).thenReturn("hashed-password");
        when(userMapper.toResponse(any(User.class))).thenReturn(
                new UserResponse("id-1", "alice", "CUSTOMER", "ACTIVE", LocalDateTime.now()));

        authService.register(request);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(saved.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void loginRejectsWrongPasswordWithoutIssuingToken() {
        User existing = User.builder()
                .id("id-1")
                .username("alice")
                .passwordHash("hashed-password")
                .role(UserRole.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password")))
                .isInstanceOf(ResponseStatusException.class);

        verify(jwtService, never()).generateToken(any(), any());
    }

    @Test
    void loginRejectsSuspendedUserEvenWithCorrectPassword() {
        User suspended = User.builder()
                .id("id-2")
                .username("bob")
                .passwordHash("hashed-password")
                .role(UserRole.CUSTOMER)
                .status(UserStatus.SUSPENDED)
                .build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(suspended));
        when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob", "correct-password")))
                .isInstanceOf(ResponseStatusException.class);

        verify(jwtService, never()).generateToken(any(), any());
    }
}
