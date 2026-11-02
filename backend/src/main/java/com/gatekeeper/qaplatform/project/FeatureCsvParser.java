package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.project.dto.CreateProjectRequest.FeatureInput;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads features from a CSV with the columns title, description, acceptance_criteria and priority.
 * Rows go through the same validation as features typed into the wizard.
 */
@Component
public class FeatureCsvParser {

    public static final int MAX_BYTES = 1_048_576;
    private static final int MAX_ROWS = 500;
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String CRITERIA = "acceptance_criteria";
    private static final String PRIORITY = "priority";
    private static final Set<String> COLUMNS = Set.of(TITLE, DESCRIPTION, CRITERIA, PRIORITY);
    private static final char BYTE_ORDER_MARK = '﻿';

    public record RowError(long row, String message) {}

    public record Result(List<FeatureInput> features, List<RowError> errors) {}

    private final Validator validator;

    public FeatureCsvParser(Validator validator) {
        this.validator = validator;
    }

    public Result parse(byte[] csv) {
        if (csv.length > MAX_BYTES) {
            throw new RequestValidationException("file", "must be 1 MB or smaller");
        }
        String text = new String(csv, StandardCharsets.UTF_8);
        // Excel adds a byte order mark, which would otherwise become part of the first column name.
        text = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (Reader reader = new StringReader(text); CSVParser parser = format.parse(reader)) {
            checkHeader(parser);
            List<FeatureInput> features = new ArrayList<>();
            List<RowError> errors = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (record.getRecordNumber() > MAX_ROWS) {
                    throw new RequestValidationException("file", "must have " + MAX_ROWS + " rows or fewer");
                }
                readRow(record, features, errors);
            }
            return new Result(features, errors);
        } catch (IOException | IllegalArgumentException | IllegalStateException | java.io.UncheckedIOException e) {
            throw new RequestValidationException("file", "isn't a valid CSV file");
        }
    }

    private static void checkHeader(CSVParser parser) {
        Set<String> found = Set.copyOf(parser.getHeaderNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList());
        if (!found.contains(TITLE)) {
            throw new RequestValidationException("file", "needs a header row with a title column");
        }
        if (!COLUMNS.containsAll(found)) {
            throw new RequestValidationException(
                    "file", "can only have the columns title, description, acceptance_criteria and priority");
        }
    }

    private void readRow(CSVRecord record, List<FeatureInput> features, List<RowError> errors) {
        // The header is line 1, so data rows are numbered as they appear in a spreadsheet.
        long row = record.getRecordNumber() + 1;
        FeatureSpec.Priority priority;
        try {
            String value = column(record, PRIORITY);
            priority = value.isEmpty()
                    ? FeatureSpec.Priority.MEDIUM
                    : FeatureSpec.Priority.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new RowError(row, "priority must be CRITICAL, HIGH, MEDIUM or LOW"));
            return;
        }
        FeatureInput feature = new FeatureInput(
                column(record, TITLE), column(record, DESCRIPTION), column(record, CRITERIA), priority);
        var violations = validator.validate(feature);
        if (violations.isEmpty()) {
            features.add(feature);
        } else {
            violations.forEach(violation ->
                    errors.add(new RowError(row, violation.getPropertyPath() + " " + violation.getMessage())));
        }
    }

    private static String column(CSVRecord record, String name) {
        return record.isMapped(name) && record.isSet(name) ? record.get(name) : "";
    }
}
