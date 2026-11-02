CREATE TABLE design_upload (
    id               uuid PRIMARY KEY,
    design_source_id uuid          NOT NULL REFERENCES design_source (id) ON DELETE CASCADE,
    file_name        varchar(255)  NOT NULL,
    storage_key      varchar(1024) NOT NULL UNIQUE,
    content_type     varchar(100)  NOT NULL,
    size_bytes       bigint        NOT NULL,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL
);
CREATE INDEX idx_design_upload_source ON design_upload (design_source_id);
