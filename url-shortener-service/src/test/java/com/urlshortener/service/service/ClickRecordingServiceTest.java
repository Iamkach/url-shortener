package com.urlshortener.service.service;

import com.urlshortener.service.domain.ClickEvent;
import com.urlshortener.service.repository.ClickEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickRecordingServiceTest {

    @Mock
    private ClickEventRepository repository;

    private ClickRecordingService service;

    @Test
    void recordAsync_savesAClickEventForTheGivenCode() {
        service = new ClickRecordingService(repository);
        service.recordAsync("abcd");

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getShortCode()).isEqualTo("abcd");
        assertThat(captor.getValue().getClickedAt()).isNotNull();
    }

    @Test
    void countClicks_delegatesToRepository() {
        service = new ClickRecordingService(repository);
        when(repository.countByShortCode("abcd")).thenReturn(5L);

        assertThat(service.countClicks("abcd")).isEqualTo(5L);
    }

    @Test
    void lastAccessedAt_returnsEmptyWhenNeverClicked() {
        service = new ClickRecordingService(repository);
        when(repository.findTopByShortCodeOrderByClickedAtDesc("abcd")).thenReturn(Optional.empty());

        assertThat(service.lastAccessedAt("abcd")).isEmpty();
    }

    @Test
    void lastAccessedAt_returnsTimestampOfMostRecentClick() {
        service = new ClickRecordingService(repository);
        Instant now = Instant.now();
        when(repository.findTopByShortCodeOrderByClickedAtDesc("abcd"))
                .thenReturn(Optional.of(new ClickEvent("abcd", now)));

        assertThat(service.lastAccessedAt("abcd")).contains(now);
    }
}
