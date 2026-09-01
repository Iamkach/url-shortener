package com.urlshortener.service.service;

/** Unchecked wrapper for ZXing {@code WriterException} / PNG {@code IOException} on the QR path. */
public class QrCodeGenerationException extends RuntimeException {
    public QrCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
