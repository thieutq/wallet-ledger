package com.walletledger.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.domain.ledger.TransferRepository;
import com.walletledger.domain.ledger.TransferType;
import com.walletledger.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test for the Phase 1 auth flow (register/login/me), against a
 * real Postgres via Testcontainers (see AbstractIntegrationTest). Run by
 * Failsafe (mvn failsafe:integration-test failsafe:verify), not Surefire.
 */
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void registerThenLoginThenGetMeReturnsExpectedFields() throws Exception {
        Map<String, String> registerBody = Map.of("username", "alice", "password", "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("token").asText();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void getMeWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerDuplicateUsernameReturns409() throws Exception {
        Map<String, String> body = Map.of("username", "bob", "password", "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void registerWithBlankUsernameReturns400() throws Exception {
        Map<String, String> body = Map.of("username", "", "password", "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        Map<String, String> body = Map.of("username", "shortpw", "password", "123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rootAdminSeedCanLogIn() throws Exception {
        Map<String, String> body = Map.of("username", "root", "password", "123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
    }

    @Test
    void firstLoginGrantsSignupBonusExactlyOnce() throws Exception {
        Map<String, String> body = Map.of("username", "carol", "password", "password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        String token = login(body);
        mockMvc.perform(get("/api/v1/players/me/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(100));

        long bonusTransfers = transferRepository.findAll().stream()
                .filter(t -> t.getType() == TransferType.BONUS && "signup-bonus-v1".equals(t.getReferenceId()))
                .count();
        assertThat(bonusTransfers).isEqualTo(1);
    }

    @Test
    void repeatedLoginsDoNotGrantTheSignupBonusAgain() throws Exception {
        Map<String, String> body = Map.of("username", "dave", "password", "password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        login(body);
        login(body);
        String token = login(body);

        mockMvc.perform(get("/api/v1/players/me/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(100));
    }

    private String login(Map<String, String> credentials) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(credentials)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
}
