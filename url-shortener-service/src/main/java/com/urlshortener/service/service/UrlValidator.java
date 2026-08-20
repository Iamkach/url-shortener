package com.urlshortener.service.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class UrlValidator {

    private static final int MAX_LENGTH = 2048;

    // Reserved so a custom alias can never shadow a real application route (spec 003, C2).
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "api", "urls", "swagger-ui", "v3", "h2-console", "actuator", "favicon.ico", "robots.txt");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

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

    /** No-op when {@code alias} is null -- customAlias is optional (spec 003). */
    public void validateAlias(String alias) {
        if (alias == null) {
            return;
        }
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new IllegalArgumentException("customAlias must match [a-zA-Z0-9_-]{1,64}");
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase())) {
            throw new IllegalArgumentException("customAlias '" + alias + "' is reserved");
        }
    }
}
