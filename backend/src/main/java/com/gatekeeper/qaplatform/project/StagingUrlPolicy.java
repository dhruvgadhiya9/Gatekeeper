package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.config.StagingProperties;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides which URLs the agents may be pointed at. It never contacts the URL or resolves DNS, so
 * submitting a URL can't be used to probe the network. The sandbox's egress allow-list (Phase 6)
 * covers hostnames that resolve somewhere unexpected.
 */
@Component
public class StagingUrlPolicy {

    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "metadata.google.internal");
    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    /** Forms like 2130706433 or 0x7f.1, which some HTTP clients read as an IP address. */
    private static final Pattern NUMERIC_LAST_LABEL = Pattern.compile("(^|.*\\.)(\\d+|0x[0-9a-f]*)$");

    private final StagingProperties properties;

    public StagingUrlPolicy(StagingProperties properties) {
        this.properties = properties;
    }

    /** Returns why the URL is not allowed, or empty if it is allowed. */
    public Optional<String> findProblem(String url) {
        Optional<String> host = parseHost(url);
        if (host.isEmpty()) {
            return Optional.of("must be a complete http or https URL with no username or password in it");
        }
        return checkHost(host.get());
    }

    /** True if {@code url} is on {@code stagingUrl}'s host or one of its subdomains. */
    public boolean isSameSite(String url, String stagingUrl) {
        Optional<String> host = parseHost(url);
        Optional<String> stagingHost = parseHost(stagingUrl);
        return host.isPresent() && stagingHost.isPresent() && matchesDomain(host.get(), stagingHost.get());
    }

    private Optional<String> checkHost(String host) {
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost") || isUnsafeNumericHost(host)) {
            return Optional.of("must not point to this machine, a link-local address or a cloud metadata service");
        }
        boolean isProduction = properties.productionDomains().stream().anyMatch(domain -> matchesDomain(host, domain));
        return isProduction ? Optional.of("is a production address, and production can never be tested") : Optional.empty();
    }

    /** Normalizes case, a trailing dot and international names, so none can slip past a check. */
    private static Optional<String> parseHost(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null
                    || !SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                return Optional.empty();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            host = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            return host.isEmpty() ? Optional.empty() : Optional.of(host.startsWith("[") ? host : IDN.toASCII(host));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean isUnsafeNumericHost(String host) {
        boolean isIpv6 = host.startsWith("[");
        if (!isIpv6 && !NUMERIC_LAST_LABEL.matcher(host).matches()) {
            return false;
        }
        if (!isIpv6 && !DOTTED_QUAD.matcher(host).matches()) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
