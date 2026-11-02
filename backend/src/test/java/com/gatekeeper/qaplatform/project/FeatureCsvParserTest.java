package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FeatureCsvParserTest {

    private final FeatureCsvParser parser =
            new FeatureCsvParser(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void readsAllColumnsIncludingQuotedCommasAndLineBreaks() {
        var result = parse("""
                title,description,acceptance_criteria,priority
                Login,"Users, and admins","Line one
                Line two",high
                """);

        assertThat(result.errors()).isEmpty();
        var feature = result.features().getFirst();
        assertThat(feature.title()).isEqualTo("Login");
        assertThat(feature.description()).isEqualTo("Users, and admins");
        assertThat(feature.acceptanceCriteria()).contains("Line two");
        assertThat(feature.priority()).isEqualTo(FeatureSpec.Priority.HIGH);
    }

    @Test
    void acceptsExcelFilesWithByteOrderMarkAndMixedCaseHeaders() {
        var result = parse("﻿Title,PRIORITY\nCheckout,LOW\n");

        assertThat(result.features()).singleElement().satisfies(feature -> {
            assertThat(feature.title()).isEqualTo("Checkout");
            assertThat(feature.priority()).isEqualTo(FeatureSpec.Priority.LOW);
        });
    }

    @Test
    void priorityDefaultsToMedium() {
        assertThat(parse("title\nSearch\n").features().getFirst().priority()).isEqualTo(FeatureSpec.Priority.MEDIUM);
    }

    @Test
    void reportsBadRowsWithSpreadsheetLineNumbersAndKeepsGoodOnes() {
        var result = parse("""
                title,priority
                Good,low
                ,low
                Other,urgent
                """);

        assertThat(result.features()).hasSize(1);
        assertThat(result.errors()).extracting(FeatureCsvParser.RowError::row).containsExactly(3L, 4L);
        assertThat(result.errors().get(1).message()).contains("priority");
    }

    @Test
    void shortRowsAreHandled() {
        var result = parse("title,description,priority\nOnly title\n");

        assertThat(result.features()).singleElement().satisfies(feature ->
                assertThat(feature.priority()).isEqualTo(FeatureSpec.Priority.MEDIUM));
    }

    @Test
    void rejectsFileWithoutTitleColumn() {
        assertInvalid("name,priority\nLogin,low\n");
    }

    @Test
    void rejectsUnknownColumns() {
        assertInvalid("title,owner\nLogin,me\n");
    }

    @Test
    void rejectsEmptyFile() {
        assertInvalid("");
    }

    @Test
    void rejectsTooManyRows() {
        assertInvalid("title\n" + "x\n".repeat(501));
    }

    @Test
    void rejectsTooLargeFile() {
        byte[] large = new byte[FeatureCsvParser.MAX_BYTES + 1];

        assertThatThrownBy(() -> parser.parse(large)).isInstanceOf(RequestValidationException.class);
    }

    private FeatureCsvParser.Result parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    private void assertInvalid(String csv) {
        assertThatThrownBy(() -> parse(csv)).isInstanceOf(RequestValidationException.class);
    }
}
