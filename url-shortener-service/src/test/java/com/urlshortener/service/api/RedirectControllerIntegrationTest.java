package com.urlshortener.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RedirectControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void redirect_returns302WithLocationHeader() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "https://example.com/redirect-target"));
        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/redirect-target"));
    }

    @Test
    void redirect_returns404ForUnknownCode() throws Exception {
        mockMvc.perform(get("/doesnotexist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redirect_returns410ForExpiredLink_butMetadataStaysReadable() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "longUrl", "https://example.com/expired-target",
                "expiresAt", Instant.now().minusSeconds(60).toString()));
        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/" + shortCode)).andExpect(status().isGone());

        // Soft-expire (spec 003, C3): metadata is still readable even though the redirect is blocked.
        mockMvc.perform(get("/api/urls/" + shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/expired-target"));
    }

    @Test
    void redirect_stillWorksWhenExpiresAtIsInTheFuture() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "longUrl", "https://example.com/not-yet-expired",
                "expiresAt", Instant.now().plusSeconds(3600).toString()));
        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/" + shortCode)).andExpect(status().isFound());
    }
}
