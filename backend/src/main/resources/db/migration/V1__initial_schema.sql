-- Initial schema for the domain model described in CLAUDE.md.
-- Columns prefixed encrypted_ hold AES-256-GCM ciphertext, never plain text.

CREATE TABLE project (
    id              uuid PRIMARY KEY,
    name            varchar(200)  NOT NULL,
    zoho_portal_id  varchar(64),
    zoho_project_id varchar(64),
    staging_url     varchar(2048) NOT NULL,
    created_by      varchar(320)  NOT NULL,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    version         bigint        NOT NULL
);

CREATE TABLE repo_connection (
    id              uuid PRIMARY KEY,
    project_id      uuid         NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    type            varchar(16)  NOT NULL CHECK (type IN ('FRONTEND', 'BACKEND', 'OTHER')),
    provider        varchar(16)  NOT NULL CHECK (provider IN ('GITHUB')),
    repo_full_name  varchar(255) NOT NULL,
    default_branch  varchar(255) NOT NULL,
    installation_id bigint,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL,
    UNIQUE (project_id, repo_full_name)
);

CREATE TABLE design_source (
    id              uuid PRIMARY KEY,
    project_id      uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    type            varchar(16) NOT NULL CHECK (type IN ('FIGMA', 'UPLOAD')),
    figma_file_key  varchar(128),
    encrypted_token text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL
);
CREATE INDEX idx_design_source_project ON design_source (project_id);

CREATE TABLE test_account (
    id                 uuid PRIMARY KEY,
    project_id         uuid          NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    role               varchar(100)  NOT NULL,
    username           varchar(320)  NOT NULL,
    encrypted_password text          NOT NULL,
    login_url          varchar(2048) NOT NULL,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,
    version            bigint        NOT NULL
);
CREATE INDEX idx_test_account_project ON test_account (project_id);

CREATE TABLE feature_spec (
    id                  uuid PRIMARY KEY,
    project_id          uuid         NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    title               varchar(300) NOT NULL,
    description         text,
    acceptance_criteria text,
    priority            varchar(16)  NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL
);
CREATE INDEX idx_feature_spec_project ON feature_spec (project_id);

CREATE TABLE test_run (
    id          uuid PRIMARY KEY,
    project_id  uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    status      varchar(32) NOT NULL CHECK (status IN (
                    'QUEUED', 'PLANNING', 'AWAITING_APPROVAL', 'RUNNING', 'TRIAGING',
                    'REPORTING', 'DONE', 'FAILED', 'CANCELLED')),
    started_at  timestamptz,
    finished_at timestamptz,
    token_usage bigint         NOT NULL DEFAULT 0,
    cost_usd    numeric(12, 4) NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL,
    updated_at  timestamptz    NOT NULL,
    version     bigint         NOT NULL
);
CREATE INDEX idx_test_run_project ON test_run (project_id);

CREATE TABLE test_plan (
    id          uuid PRIMARY KEY,
    run_id      uuid        NOT NULL UNIQUE REFERENCES test_run (id) ON DELETE CASCADE,
    markdown    text        NOT NULL,
    status      varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    approved_by varchar(320),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint      NOT NULL
);

CREATE TABLE run_step (
    id         uuid PRIMARY KEY,
    run_id     uuid        NOT NULL REFERENCES test_run (id) ON DELETE CASCADE,
    agent      varchar(16) NOT NULL CHECK (agent IN (
                   'DESIGN', 'FRONTEND', 'BACKEND', 'API', 'SECURITY', 'ACCESSIBILITY', 'PERFORMANCE')),
    status     varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'PASSED', 'FAILED', 'SKIPPED')),
    log        text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL
);
CREATE INDEX idx_run_step_run ON run_step (run_id);

CREATE TABLE finding (
    id                    uuid PRIMARY KEY,
    run_id                uuid         NOT NULL REFERENCES test_run (id) ON DELETE CASCADE,
    category              varchar(16)  NOT NULL CHECK (category IN (
                              'DESIGN', 'FRONTEND', 'BACKEND', 'SECURITY', 'UNKNOWN')),
    severity              varchar(16)  NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    title                 varchar(500) NOT NULL,
    steps_to_reproduce    text[]       NOT NULL DEFAULT '{}',
    expected              text,
    actual                text,
    file_path             varchar(1024),
    line_start            integer,
    evidence_keys         text[]       NOT NULL DEFAULT '{}',
    fingerprint           varchar(64),
    confirmed             boolean      NOT NULL DEFAULT false,
    zoho_issue_id         varchar(64),
    assignee_zoho_user_id varchar(64),
    status                varchar(32)  NOT NULL CHECK (status IN (
                              'NEW', 'AWAITING_APPROVAL', 'FILED', 'DUPLICATE', 'NOT_REPRODUCED', 'REJECTED')),
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL
);
CREATE INDEX idx_finding_run ON finding (run_id);
CREATE INDEX idx_finding_fingerprint ON finding (fingerprint);

CREATE TABLE department_mapping (
    id                         uuid PRIMARY KEY,
    project_id                 uuid        NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    category                   varchar(16) NOT NULL CHECK (category IN (
                                   'DESIGN', 'FRONTEND', 'BACKEND', 'SECURITY', 'UNKNOWN')),
    default_zoho_user_id       varchar(64) NOT NULL,
    fallback_zoho_user_id      varchar(64) NOT NULL,
    security_lead_zoho_user_id varchar(64),
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint      NOT NULL,
    UNIQUE (project_id, category)
);

CREATE TABLE code_owner_override (
    id           uuid PRIMARY KEY,
    project_id   uuid          NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    path_pattern varchar(1024) NOT NULL,
    zoho_user_id varchar(64)   NOT NULL,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    version      bigint        NOT NULL,
    UNIQUE (project_id, path_pattern)
);

CREATE TABLE user_mapping (
    id              uuid PRIMARY KEY,
    github_username varchar(100) NOT NULL UNIQUE,
    zoho_user_id    varchar(64)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL
);

CREATE TABLE report (
    id           uuid PRIMARY KEY,
    run_id       uuid        NOT NULL UNIQUE REFERENCES test_run (id) ON DELETE CASCADE,
    html_key     varchar(1024),
    pdf_key      varchar(1024),
    summary_json jsonb,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL
);

-- Audit rows outlive what they describe, so they have no foreign keys.
CREATE TABLE audit_event (
    id           uuid PRIMARY KEY,
    actor        varchar(320) NOT NULL,
    action       varchar(100) NOT NULL,
    target       varchar(500),
    details_json jsonb,
    occurred_at  timestamptz  NOT NULL
);
CREATE INDEX idx_audit_event_occurred_at ON audit_event (occurred_at);
