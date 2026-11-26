package com.example.server.utils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MediaDurationTest {
    @Test void parsesFractionalDurationAndRejectsInvalidValues() {
        assertEquals(30517, MediaDuration.parseMillis("30.517000"));
        assertEquals(1, MediaDuration.parseMillis("0.0001"));
        for (String value : new String[]{"NaN", "Infinity", "0", "-1", "N/A"}) {
            assertThrows(IllegalArgumentException.class, () -> MediaDuration.parseMillis(value));
        }
    }
}
