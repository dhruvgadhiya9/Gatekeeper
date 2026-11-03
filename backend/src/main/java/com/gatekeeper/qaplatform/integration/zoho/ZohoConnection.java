package com.gatekeeper.qaplatform.integration.zoho;

import com.gatekeeper.qaplatform.common.crypto.EncryptedStringConverter;
import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The one Zoho account the platform acts through. An administrator connects it once. */
@Entity
@Table(name = "zoho_connection")
public class ZohoConnection extends BaseEntity {

    /** Plain text in memory, ciphertext in the database. Never expose in a DTO. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "encrypted_refresh_token")
    private String refreshToken;

    private String connectedBy;

    protected ZohoConnection() {}

    public ZohoConnection(String refreshToken, String connectedBy) {
        this.refreshToken = refreshToken;
        this.connectedBy = connectedBy;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getConnectedBy() {
        return connectedBy;
    }
}
