package com.urlshortener.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "http://example.com/path?x=1", "https://sub.example.com:8080/a/b"})
    void validate_acceptsWellFormedHttpUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsBlank() {
        assertThatThrownBy(() -> validator.validate("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "not a url", "javascript:alert(1)", "example.com"})
    void validate_rejectsNonHttpOrMalformed(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsExcessivelyLongUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> validator.validate(longUrl)).isInstanceOf(IllegalArgumentException.class);
    }
}
