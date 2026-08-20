package com.urlshortener.service.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class UrlValidator {

    private static final int MAX_LENGTH = 2048;

    /** @throws IllegalArgumentException if the URL is missing, malformed, or not http(s). */
    public void validate(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new IllegalArgumentException("longUrl must not be blank");
        }
        if (longUrl.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("longUrl exceeds maximum length of " + MAX_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(longUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("longUrl is not a well-formed URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("longUrl must use http or https scheme");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("longUrl must include a host");
        }
    }
}
