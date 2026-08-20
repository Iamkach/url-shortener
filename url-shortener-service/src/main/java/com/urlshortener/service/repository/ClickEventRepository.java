package com.urlshortener.service.repository;

import com.urlshortener.service.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortCode(String shortCode);

    Optional<ClickEvent> findTopByShortCodeOrderByClickedAtDesc(String shortCode);
}
