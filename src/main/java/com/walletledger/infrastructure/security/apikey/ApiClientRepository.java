package com.walletledger.infrastructure.security.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, String> {
    Optional<ApiClient> findByName(String name);
}
