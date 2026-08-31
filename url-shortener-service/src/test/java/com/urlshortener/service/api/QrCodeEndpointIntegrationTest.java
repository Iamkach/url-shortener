package com.urlshortener.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 004 — GET /api/urls/{code}/qr. Covers the happy path (a scannable PNG that encodes the
 * absolute short URL), plus the 404 / 410 error paths shared with the redirect path (spec 003).
 */
@SpringBootTest
@AutoConfigureMockMvc
class QrCodeEndpointIntegrationTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String createShortCode(Map<String, Object> requestBody) throws Exception {
        String response = mockMvc.perform(post("/api/urls")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("shortCode").asText();
    }

    private static String decodeQr(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        return new QRCodeReader().decode(bitmap).getText();
    }

    @Test
    void qr_returnsScannablePngEncodingTheAbsoluteShortUrl() throws Exception {
        String shortCode = createShortCode(Map.of("longUrl", "https://example.com/qr-happy-path"));

        byte[] png = mockMvc.perform(get("/api/urls/" + shortCode + "/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(png).hasSizeGreaterThan(PNG_MAGIC.length);
        assertThat(java.util.Arrays.copyOf(png, PNG_MAGIC.length)).isEqualTo(PNG_MAGIC);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);

        assertThat(decodeQr(png)).isEqualTo("http://localhost:8080/" + shortCode);
    }

    @Test
    void qr_worksForACustomAlias() throws Exception {
        String shortCode = createShortCode(Map.of(
                "longUrl", "https://example.com/qr-alias",
                "customAlias", "spec004-qr-alias"));

        byte[] png = mockMvc.perform(get("/api/urls/" + shortCode + "/qr"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(decodeQr(png)).isEqualTo("http://localhost:8080/spec004-qr-alias");
    }

    @Test
    void qr_returns404ForUnknownCode() throws Exception {
        mockMvc.perform(get("/api/urls/doesnotexist/qr"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qr_returns410ForSoftExpiredCode_butMetadataStaysReadable() throws Exception {
        String shortCode = createShortCode(Map.of(
                "longUrl", "https://example.com/qr-expired",
                "expiresAt", Instant.now().minusSeconds(60).toString()));

        mockMvc.perform(get("/api/urls/" + shortCode + "/qr"))
                .andExpect(status().isGone());

        // Soft-expire (spec 003, C3): the row and its metadata stay readable.
        mockMvc.perform(get("/api/urls/" + shortCode))
                .andExpect(status().isOk());
    }

    @Test
    void qr_stillWorksWhenExpiresAtIsInTheFuture() throws Exception {
        String shortCode = createShortCode(Map.of(
                "longUrl", "https://example.com/qr-future",
                "expiresAt", Instant.now().plusSeconds(3600).toString()));

        byte[] png = mockMvc.perform(get("/api/urls/" + shortCode + "/qr"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(decodeQr(png)).isEqualTo("http://localhost:8080/" + shortCode);
    }
}
