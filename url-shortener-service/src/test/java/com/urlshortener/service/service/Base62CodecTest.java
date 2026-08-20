package com.urlshortener.service.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62CodecTest {

    private final Base62Codec codec = new Base62Codec();

    @Test
    void encode_padsShortValuesToMinimumLength() {
        assertThat(codec.encode(0)).hasSize(4);
        assertThat(codec.encode(1)).hasSize(4);
        assertThat(codec.encode(61)).hasSize(4);
    }

    @Test
    void encode_isDeterministicAndUnique() {
        assertThat(codec.encode(12345)).isEqualTo(codec.encode(12345));
        assertThat(codec.encode(1)).isNotEqualTo(codec.encode(2));
    }

    @Test
    void encode_growsBeyondMinimumLengthForLargeValues() {
        String encoded = codec.encode(62L * 62 * 62 * 62); // one past 4-char capacity
        assertThat(encoded).hasSize(5);
    }

    @Test
    void encode_rejectsNegativeValues() {
        assertThatThrownBy(() -> codec.encode(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
