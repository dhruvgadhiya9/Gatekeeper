package com.gatekeeper.qaplatform.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FigmaUrlParserTest {

    private final FigmaUrlParser parser = new FigmaUrlParser();

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.figma.com/design/AbC123/My-File?node-id=1-2",
        "https://figma.com/file/AbC123/My-File",
        "https://www.figma.com/proto/AbC123",
    })
    void extractsFileKey(String url) {
        assertThat(parser.parseFileKey(url)).contains("AbC123");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://www.figma.com/design/AbC123/x",
        "https://figma.com.evil.test/design/AbC123/x",
        "https://evil.test/figma.com/design/AbC123",
        "https://www.figma.com/community/AbC123",
        "https://www.figma.com/design/",
        "not a url",
    })
    void rejectsOtherLinks(String url) {
        assertThat(parser.parseFileKey(url)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(parser.parseFileKey(null)).isEmpty();
    }
}
