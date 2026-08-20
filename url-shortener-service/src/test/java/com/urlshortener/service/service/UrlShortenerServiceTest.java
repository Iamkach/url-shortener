package com.urlshortener.service.service;

import com.urlshortener.service.domain.ShortUrl;
import com.urlshortener.service.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    @Mock
    private ShortUrlRepository repository;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(repository, new UrlValidator(), new Base62Codec());
    }

    @Test
    void create_persistsThenAssignsShortCodeDerivedFromGeneratedId() {
        ShortUrl saved = new ShortUrl();
        saved.setId(42L);
        saved.setLongUrl("https://example.com");
        when(repository.save(any(ShortUrl.class))).thenAnswer(invocation -> {
            ShortUrl arg = invocation.getArgument(0);
            if (arg.getId() == null) {
                arg.setId(42L);
            }
            return arg;
        });

        ShortUrl result = service.create("https://example.com", null);

        assertThat(result.getShortCode()).isEqualTo(new Base62Codec().encode(42L));
        assertThat(result.getLongUrl()).isEqualTo("https://example.com");
        verify(repository, org.mockito.Mockito.times(2)).save(any(ShortUrl.class));
    }

    @Test
    void create_rejectsInvalidLongUrl() {
        assertThatThrownBy(() -> service.create("not a url", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_returnsMatchingEntity() {
        ShortUrl entity = new ShortUrl();
        entity.setShortCode("abcd");
        entity.setLongUrl("https://example.com");
        when(repository.findByShortCode("abcd")).thenReturn(Optional.of(entity));

        assertThat(service.resolve("abcd")).isSameAs(entity);
    }

    @Test
    void resolve_throwsWhenCodeUnknown() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void create_withCustomAlias_usesAliasVerbatimAsShortCode() {
        when(repository.findByShortCode("my-brand")).thenReturn(Optional.empty());
        when(repository.save(any(ShortUrl.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShortUrl result = service.create("https://example.com", null, "my-brand");

        assertThat(result.getShortCode()).isEqualTo("my-brand");
        verify(repository, org.mockito.Mockito.times(1)).save(any(ShortUrl.class));
    }

    @Test
    void create_withCustomAlias_rejectsWhenAlreadyTaken() {
        ShortUrl existing = new ShortUrl();
        existing.setShortCode("my-brand");
        when(repository.findByShortCode("my-brand")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create("https://example.com", null, "my-brand"))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void create_withCustomAlias_rejectsReservedWord() {
        assertThatThrownBy(() -> service.create("https://example.com", null, "api"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
