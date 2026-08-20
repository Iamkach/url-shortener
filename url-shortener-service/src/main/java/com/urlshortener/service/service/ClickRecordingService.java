package com.urlshortener.service.service;

import com.urlshortener.service.domain.ClickEvent;
import com.urlshortener.service.repository.ClickEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClickRecordingService {

    private final ClickEventRepository repository;

    /** Fire-and-forget: must never add latency or failure risk to the redirect path (spec.md B4). */
    @Async
    public void recordAsync(String shortCode) {
        repository.save(new ClickEvent(shortCode, Instant.now()));
    }

    public long countClicks(String shortCode) {
        return repository.countByShortCode(shortCode);
    }

    public Optional<Instant> lastAccessedAt(String shortCode) {
        return repository.findTopByShortCodeOrderByClickedAtDesc(shortCode).map(ClickEvent::getClickedAt);
    }
}
