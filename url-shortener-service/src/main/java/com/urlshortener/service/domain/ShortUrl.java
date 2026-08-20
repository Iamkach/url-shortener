package com.urlshortener.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "short_url", uniqueConstraints = @UniqueConstraint(columnNames = "shortCode"))
@Getter
@Setter
@NoArgsConstructor
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Derived from {@code id} via Base62 once the row is persisted; null until then. */
    @Column(unique = true)
    private String shortCode;

    @Column(length = 2048, nullable = false)
    private String longUrl;

    private Instant createdAt;

    private Instant expiresAt;
}
