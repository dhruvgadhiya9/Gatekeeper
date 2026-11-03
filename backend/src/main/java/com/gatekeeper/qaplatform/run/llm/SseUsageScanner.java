package com.gatekeeper.qaplatform.run.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads token usage from a streaming response as its bytes pass through, without changing them.
 * {@code message_start} holds the input counts, and {@code message_delta} the cumulative output.
 */
class SseUsageScanner {

    /** Longer lines are content, not the small usage events this looks for. */
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final String DATA_PREFIX = "data:";

    private final ObjectMapper objectMapper;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean lineTooLong;
    private TokenCounts tokens = TokenCounts.NONE;

    SseUsageScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Chunks can split a line anywhere, so bytes are kept until the line ends. */
    void accept(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            byte value = buffer[i];
            if (value == '\n') {
                if (!lineTooLong) {
                    scanLine(line.toString(StandardCharsets.UTF_8));
                }
                line.reset();
                lineTooLong = false;
            } else if (line.size() < MAX_LINE_BYTES) {
                line.write(value);
            } else {
                lineTooLong = true;
            }
        }
    }

    TokenCounts tokens() {
        return tokens;
    }

    private void scanLine(String text) {
        if (!text.startsWith(DATA_PREFIX) || !text.contains("usage")) {
            return;
        }
        try {
            JsonNode event = objectMapper.readTree(text.substring(DATA_PREFIX.length()).trim());
            String type = event.path("type").asText();
            if ("message_start".equals(type)) {
                tokens = tokens.merge(event.path("message").path("usage"));
            } else if ("message_delta".equals(type)) {
                tokens = tokens.merge(event.path("usage"));
            }
        } catch (JsonProcessingException e) {
            // A line that isn't JSON can't hold usage. The bytes still pass through untouched.
        }
    }
}
