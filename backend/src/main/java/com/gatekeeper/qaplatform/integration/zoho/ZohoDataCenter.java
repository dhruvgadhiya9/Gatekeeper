package com.gatekeeper.qaplatform.integration.zoho;

import java.util.Arrays;
import java.util.Locale;

/**
 * Zoho's data centers. Addresses only ever come from this table, never from a redirect's
 * {@code accounts-server} parameter, because the client secret is sent to them.
 */
public enum ZohoDataCenter {
    US("us", "https://accounts.zoho.com", "https://projects.zoho.com"),
    EU("eu", "https://accounts.zoho.eu", "https://projects.zoho.eu"),
    IN("in", "https://accounts.zoho.in", "https://projects.zoho.in"),
    AU("au", "https://accounts.zoho.com.au", "https://projects.zoho.com.au"),
    JP("jp", "https://accounts.zoho.jp", "https://projects.zoho.jp"),
    CA("ca", "https://accounts.zohocloud.ca", "https://projects.zohocloud.ca"),
    CN("cn", "https://accounts.zoho.com.cn", "https://projects.zoho.com.cn"),
    SA("sa", "https://accounts.zoho.sa", "https://projects.zoho.sa"),
    UK("uk", "https://accounts.zoho.uk", "https://projects.zoho.uk"),
    AE("ae", "https://accounts.zoho.ae", "https://projects.zoho.ae"),
    SG("sg", "https://accounts.zoho.sg", "https://projects.zoho.sg");

    private final String location;
    private final String accountsUrl;
    private final String apiUrl;

    ZohoDataCenter(String location, String accountsUrl, String apiUrl) {
        this.location = location;
        this.accountsUrl = accountsUrl;
        this.apiUrl = apiUrl;
    }

    public String location() {
        return location;
    }

    public String accountsUrl() {
        return accountsUrl;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public static ZohoDataCenter fromLocation(String location) {
        String wanted = location == null ? "" : location.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(center -> center.location.equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("app.zoho.data-center isn't a known Zoho data center"));
    }
}
