package com.gatekeeper.qaplatform.run.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** Token counts from a Messages API {@code usage} object. */
public record TokenCounts(long input, long output, long cacheWrite, long cacheRead) {

    public static final TokenCounts NONE = new TokenCounts(0, 0, 0, 0);
    private static final int CACHE_READ_DIVISOR = 10;

    /**
     * What counts against a run's budget. Agents re-read their whole conversation from the cache on
     * every turn, so cache reads dwarf everything else but cost about a tenth as much. Counting them
     * in full would end runs early without saving money.
     */
    public long billable() {
        return input + output + cacheWrite + cacheRead / CACHE_READ_DIVISOR;
    }

    /** Fields missing from {@code usage} keep this object's values. */
    public TokenCounts merge(JsonNode usage) {
        return new TokenCounts(
                usage.path("input_tokens").asLong(input),
                usage.path("output_tokens").asLong(output),
                usage.path("cache_creation_input_tokens").asLong(cacheWrite),
                usage.path("cache_read_input_tokens").asLong(cacheRead));
    }
}
