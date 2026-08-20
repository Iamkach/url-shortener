package com.urlshortener.service.service;

import org.springframework.stereotype.Component;

/**
 * Encodes a positive long (a DB row id) into a Base62 string, left-padded to a minimum
 * length so single-digit ids don't produce a suspiciously short/obvious code. See
 * specs/001-core-url-shortener/plan.md §3 for why sequence-derived codes were chosen over
 * random-with-collision-retry.
 */
@Component
public class Base62Codec {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();
    private static final int MIN_LENGTH = 4;

    public String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot encode a negative value: " + value);
        }
        StringBuilder sb = new StringBuilder();
        long remaining = value;
        do {
            sb.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        } while (remaining > 0);
        while (sb.length() < MIN_LENGTH) {
            sb.append(ALPHABET.charAt(0));
        }
        return sb.reverse().toString();
    }
}
