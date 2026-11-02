-- Users are identified by issuer + subject, because an email address can change or be reassigned.
CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    issuer        varchar(500) NOT NULL,
    subject       varchar(255) NOT NULL,
    email         varchar(320) NOT NULL,
    display_name  varchar(200),
    role          varchar(16)  NOT NULL CHECK (role IN ('ADMIN', 'QA_LEAD', 'VIEWER')),
    enabled       boolean      NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL,
    UNIQUE (issuer, subject)
);
