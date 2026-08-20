package com.urlshortener.service.service;

import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.repository.ShortUrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class UrlShortenerService {

    private final ShortUrlRepository repository;
    private final UrlValidator validator;
    private final Base62Codec codec;

    @Transactional
    public ShortUrl create(String longUrl, Instant expiresAt) {
        validator.validate(longUrl);

        ShortUrl entity = new ShortUrl();
        entity.setLongUrl(longUrl);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(expiresAt);
        // Save first so the DB assigns the id that seeds the short code (see plan.md §3).
        entity = repository.save(entity);
        entity.setShortCode(codec.encode(entity.getId()));
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public ShortUrl resolve(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("No short URL found for code: " + shortCode));
    }
}
