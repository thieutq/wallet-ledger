package com.walletledger.infrastructure.security.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Api-Key";

    private final ApiClientRepository apiClientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER_NAME);

        if (apiKey != null) {
            apiClientRepository.findAll().stream()
                    .filter(client -> client.isActive() && passwordEncoder.matches(apiKey, client.getHashedApiKey()))
                    .findFirst()
                    .ifPresent(client -> {
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_SERVICE"));
                        var authentication = new UsernamePasswordAuthenticationToken(client.getName(), null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }

        chain.doFilter(request, response);
    }
}
