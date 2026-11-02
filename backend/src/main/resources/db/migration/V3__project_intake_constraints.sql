-- A project has at most one design source, and a role and username pair appears once.
ALTER TABLE design_source ADD CONSTRAINT uq_design_source_project UNIQUE (project_id);
DROP INDEX idx_design_source_project;

ALTER TABLE test_account ADD CONSTRAINT uq_test_account_project_role_username UNIQUE (project_id, role, username);

CREATE INDEX idx_project_created_at ON project (created_at DESC);
