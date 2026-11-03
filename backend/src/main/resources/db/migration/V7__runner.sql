ALTER TABLE test_run
    ADD COLUMN token_budget        bigint NOT NULL DEFAULT 0,
    ADD COLUMN deadline_at         timestamptz,
    ADD COLUMN sandbox_finished_at timestamptz,
    ADD COLUMN agent_exit_code     integer,
    ADD COLUMN results_manifest    jsonb;

-- One row per model request a run makes through the LLM proxy. Never holds prompts or responses.
CREATE TABLE llm_usage (
    id                 uuid PRIMARY KEY,
    run_id             uuid           NOT NULL REFERENCES test_run (id) ON DELETE CASCADE,
    model              varchar(100)   NOT NULL,
    upstream_status    integer        NOT NULL,
    input_tokens       bigint         NOT NULL,
    output_tokens      bigint         NOT NULL,
    cache_write_tokens bigint         NOT NULL,
    cache_read_tokens  bigint         NOT NULL,
    billable_tokens    bigint         NOT NULL,
    cost_usd           numeric(12, 6) NOT NULL,
    created_at         timestamptz    NOT NULL,
    updated_at         timestamptz    NOT NULL,
    version            bigint         NOT NULL
);
CREATE INDEX idx_llm_usage_run ON llm_usage (run_id);
