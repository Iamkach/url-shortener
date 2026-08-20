package com.urlshortener.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createThenFetchMetadata_roundTrips() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "https://example.com/create-then-fetch"));

        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/create-then-fetch"))
                .andReturn().getResponse().getContentAsString();

        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/api/urls/" + shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com/create-then-fetch"));
    }

    @Test
    void create_rejectsInvalidUrlWith400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "not-a-url"));

        mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void get_returns404ForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/urls/doesnotexist"))
                .andExpect(status().isNotFound());
    }
}
