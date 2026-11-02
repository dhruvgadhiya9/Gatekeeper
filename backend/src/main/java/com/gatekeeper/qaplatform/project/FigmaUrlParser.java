package com.gatekeeper.qaplatform.project;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FigmaUrlParser {

    private static final Set<String> HOSTS = Set.of("figma.com", "www.figma.com");
    private static final Pattern PATH = Pattern.compile("^/(?:file|design|proto)/([A-Za-z0-9]{1,128})(?:/.*)?$");

    /** Returns the file key from a figma.com link, or empty if it isn't one. */
    public Optional<String> parseFileKey(String url) {
        if (url == null) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(url.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost()) || uri.getPath() == null) {
                return Optional.empty();
            }
            Matcher matcher = PATH.matcher(uri.getPath());
            return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
