package com.urlshortener.service.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders a fixed 256x256 PNG QR code for a piece of text using ZXing {@code core} only
 * (plan.md §2). {@code core} has no image I/O, so the {@link BitMatrix} is walked into a
 * {@link BufferedImage} by hand and written with {@link ImageIO} -- no {@code zxing:javase}.
 */
@Component
public class QrCodeRenderer {

    private static final int SIZE = 256;
    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    public byte[] pngFor(String text) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.MARGIN, 1,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            BitMatrix matrix = new QRCodeWriter()
                    .encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);

            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y < SIZE; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (WriterException | IOException e) {
            throw new QrCodeGenerationException("Failed to render QR code for: " + text, e);
        }
    }
}
