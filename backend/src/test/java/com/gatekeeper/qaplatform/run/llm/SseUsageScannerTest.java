package com.gatekeeper.qaplatform.run.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseUsageScannerTest {

    private static final String STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":300,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"the word usage in the text"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":40}}

            """;

    @Test
    void readsInputFromStartAndFinalOutputFromDelta() {
        SseUsageScanner scanner = new SseUsageScanner(new ObjectMapper());
        byte[] bytes = STREAM.getBytes(StandardCharsets.UTF_8);

        scanner.accept(bytes, bytes.length);

        assertThat(scanner.tokens()).isEqualTo(new TokenCounts(10, 40, 20, 300));
        assertThat(scanner.tokens().billable()).isEqualTo(10 + 40 + 20 + 30);
    }

    @Test
    void handlesChunksThatSplitLinesAnywhere() {
        SseUsageScanner scanner = new SseUsageScanner(new ObjectMapper());
        byte[] bytes = STREAM.getBytes(StandardCharsets.UTF_8);

        for (byte value : bytes) {
            scanner.accept(new byte[] {value}, 1);
        }

        assertThat(scanner.tokens()).isEqualTo(new TokenCounts(10, 40, 20, 300));
    }

    @Test
    void aStreamCutOffAfterTheStartStillCountsTheInput() {
        SseUsageScanner scanner = new SseUsageScanner(new ObjectMapper());
        byte[] bytes = STREAM.substring(0, STREAM.indexOf("event: content_block_delta")).getBytes(StandardCharsets.UTF_8);

        scanner.accept(bytes, bytes.length);

        assertThat(scanner.tokens().input()).isEqualTo(10);
    }

    @Test
    void malformedAndHugeLinesAreIgnored() {
        SseUsageScanner scanner = new SseUsageScanner(new ObjectMapper());
        byte[] bad = "data: {not json usage\n".getBytes(StandardCharsets.UTF_8);
        scanner.accept(bad, bad.length);
        byte[] huge = ("data: " + "usage".repeat(300_000) + "\n").getBytes(StandardCharsets.UTF_8);
        scanner.accept(huge, huge.length);

        assertThat(scanner.tokens()).isEqualTo(TokenCounts.NONE);
    }
}
