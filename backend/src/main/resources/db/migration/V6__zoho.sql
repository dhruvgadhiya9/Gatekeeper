-- The single Zoho connection made by an administrator. The refresh token is AES-256-GCM ciphertext.
CREATE TABLE zoho_connection (
    id                      uuid PRIMARY KEY,
    encrypted_refresh_token text         NOT NULL,
    connected_by            varchar(320) NOT NULL,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    version                 bigint       NOT NULL
);

-- Zoho has no documented API for listing severity and classification IDs, so admins enter them.
CREATE TABLE zoho_field_mapping (
    id             uuid PRIMARY KEY,
    portal_id      varchar(64) NOT NULL,
    kind           varchar(16) NOT NULL CHECK (kind IN ('SEVERITY', 'CLASSIFICATION')),
    gatekeeper_key varchar(16) NOT NULL,
    zoho_option_id varchar(64) NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL,
    UNIQUE (portal_id, kind, gatekeeper_key)
);

-- Written before calling Zoho, so a repeated attempt can't create a second issue.
CREATE TABLE zoho_issue_link (
    id            uuid PRIMARY KEY,
    project_id    uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    fingerprint   varchar(64) NOT NULL,
    status        varchar(16) NOT NULL CHECK (status IN ('PENDING', 'UNCERTAIN', 'CREATED')),
    zoho_issue_id varchar(64),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL,
    UNIQUE (project_id, fingerprint)
);
