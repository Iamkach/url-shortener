package com.urlshortener.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void analytics_reflectsClicksAfterRedirects() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "https://example.com/analytics-target"));
        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/" + shortCode)).andExpect(status().isFound());
        mockMvc.perform(get("/" + shortCode)).andExpect(status().isFound());

        // recordAsync runs off-thread; poll until both clicks land rather than sleeping a fixed amount.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/api/urls/" + shortCode + "/analytics"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalClicks").value(2))
                        .andExpect(jsonPath("$.lastAccessedAt").isNotEmpty()));
    }

    @Test
    void analytics_returns404ForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/urls/doesnotexist/analytics"))
                .andExpect(status().isNotFound());
    }

    @Test
    void analytics_zeroClicksForNeverAccessedLink() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "https://example.com/never-clicked"));
        String response = mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andReturn().getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(response).get("shortCode").asText();

        mockMvc.perform(get("/api/urls/" + shortCode + "/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.lastAccessedAt").doesNotExist());
    }
}
