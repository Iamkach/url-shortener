package com.urlshortener.service.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Deliberately not a JPA relationship to {@link ShortUrl} — kept as a standalone,
 * append-only table so a hot link's click volume never contends with reads/writes on the
 * ShortUrl row itself. See specs/002-click-analytics-ratelimit/plan.md §2.
 */
@Entity
@Table(name = "click_event", indexes = @Index(name = "idx_click_event_short_code", columnList = "shortCode"))
@Getter
@Setter
@NoArgsConstructor
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shortCode;

    private Instant clickedAt;

    public ClickEvent(String shortCode, Instant clickedAt) {
        this.shortCode = shortCode;
        this.clickedAt = clickedAt;
    }
}
