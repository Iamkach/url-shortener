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
        return create(longUrl, expiresAt, null);
    }

    /**
     * @param customAlias optional (spec 003); when present it's used verbatim as the
     *                     shortCode instead of the Base62-derived one, after validation
     *                     and a uniqueness check.
     */
    @Transactional
    public ShortUrl create(String longUrl, Instant expiresAt, String customAlias) {
        validator.validate(longUrl);

        ShortUrl entity = new ShortUrl();
        entity.setLongUrl(longUrl);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(expiresAt);

        if (customAlias != null) {
            validator.validateAlias(customAlias);
            if (repository.findByShortCode(customAlias).isPresent()) {
                throw new AliasAlreadyExistsException("customAlias '" + customAlias + "' is already in use");
            }
            entity.setShortCode(customAlias);
            return repository.save(entity);
        }

        // Save first so the DB assigns the id that seeds the short code (see plan.md §3, spec 001).
        entity = repository.save(entity);
        entity.setShortCode(codec.encode(entity.getId()));
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public ShortUrl resolve(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException("No short URL found for code: " + shortCode));
    }

    /** resolve(), then enforce the spec-003 soft-expire rule shared by the redirect and QR read paths. */
    @Transactional(readOnly = true)
    public ShortUrl resolveUnexpired(String shortCode) {
        ShortUrl entity = resolve(shortCode);
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(Instant.now())) {
            throw new LinkExpiredException("Short link '" + shortCode + "' expired at " + entity.getExpiresAt());
        }
        return entity;
    }
}
