package com.cqwlw.maintenance.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogSanitizerTest {

    @Test
    void removesWholeQueryAndLeavesSafePrefix() {
        String raw = "I/O error on GET request for \"https://example.test/login?secret=s&key=k\": connection reset";
        assertEquals("I/O error on GET request for \"https://example.test/login?<query-redacted>",
                LogSanitizer.withoutQuery(raw));
    }

    @Test
    void keepsMessageWithoutQueryAndNormalizesEmpty() {
        assertEquals("timeout", LogSanitizer.withoutQuery("timeout"));
        assertEquals("-", LogSanitizer.withoutQuery(null));
        assertEquals("-", LogSanitizer.withoutQuery(""));
    }
}
