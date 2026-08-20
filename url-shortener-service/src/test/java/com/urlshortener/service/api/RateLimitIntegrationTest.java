package com.urlshortener.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses its own low capacity (via distinct @SpringBootTest properties, which gives it a
 * separate cached Spring context and therefore an isolated RateLimiter bean) so this test
 * doesn't share bucket state with -- or get flaky interference from -- other integration
 * tests that also POST /api/urls against the default capacity=20 bucket.
 */
@SpringBootTest(properties = {"app.rate-limit.capacity=3", "app.rate-limit.refill-per-minute=3"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void postUrls_rejectsRequestsBeyondCapacityWith429() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("longUrl", "https://example.com/rate-limit-test"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/urls").contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
    }
}
