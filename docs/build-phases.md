# Build phases

Run these one at a time. Review, run and commit each phase before starting the next.

"Part C" below means the sandbox agent prompt, stored at `runner/prompts/qa-agent.md`.
The master spec is `CLAUDE.md`.

## Phase 1 — Skeleton and local environment
```
Read CLAUDE.md. Create the monorepo skeleton: backend/ (Spring Boot 3, Java 21,
Maven), frontend/ (React + TS + Vite + Tailwind), runner/ (empty Dockerfile
placeholder), infra/docker-compose.yml with Postgres 16, RabbitMQ, MinIO,
backend and frontend. Add Flyway V1 migration for all entities in the domain
model, JPA entities and repositories, the AES-GCM encryption converter for
encrypted fields, global exception handler, springdoc, Actuator health, and
.env.example. Add a GitHub Actions CI that builds and tests backend and
frontend and runs OWASP Dependency-Check. Do not implement integrations yet.
Show me how to run everything locally.
```

## Phase 2 — Security and users
```
Implement authentication with Spring Security: OIDC login (configurable issuer,
Google Workspace / Azure AD / Keycloak), roles ADMIN, QA_LEAD, VIEWER, JWT for
the SPA, CSRF and CORS config, rate limiting on login and run-start endpoints,
and the AuditEvent service with an aspect that records annotated actions. Add
the frontend login flow and a protected layout. Include tests.
```

## Phase 3 — Project intake (the page that asks for everything)
```
Build the project intake: REST endpoints and a multi-step React wizard:
1) Project name + Zoho portal and Zoho project (dropdowns loaded later in Phase 5;
   for now free text IDs), 2) Design source (Figma file URL + token, or upload),
3) Repositories: frontend and backend (and optional others) via GitHub App
   installation picker, 4) Staging URL with production deny-list validation,
5) Test accounts per role (passwords encrypted, never returned to UI),
6) Features to test: title, description, acceptance criteria, priority; allow
   CSV upload, 7) Department mapping: Design/Frontend/Backend/Security → Zoho user.
Validate everything server-side. Add project list and project detail pages.
```

## Phase 4 — GitHub App and Figma integrations
```
Implement integration/github: GitHub App JWT auth, installation access tokens,
list installations and repos, short-lived clone token for the runner, read
CODEOWNERS, git blame for a file/line range via the API. Implement
integration/figma: list frames of a file and export selected frames as PNG to
storage. Wrap all calls with Resilience4j retry and circuit breaker, add
WireMock tests. Never log tokens. Replace the free-text repositories in the
intake wizard (Phase 3) with the GitHub App installation picker.
```

## Phase 5 — Zoho Projects integration
```
Implement integration/zoho per CLAUDE.md. First read the current official Zoho
Projects API docs and list for me the exact endpoints and payloads you will use
(portals, projects, users, create issue/bug with assignee, update, add comment,
add follower, upload attachment, search open issues). Wait for my confirmation,
then implement: OAuth connect flow (admin connects once; refresh token stored
encrypted; data center configurable), token refresh cache, typed client,
ZohoAssignmentService with idempotency by fingerprint, and UserMapping between
GitHub usernames and Zoho users (admin UI to edit). Replace the free-text Zoho
IDs in the intake wizard with dropdowns. WireMock tests for every call.
```

## Phase 6 — Runner sandbox image and launcher
```
Build runner/Dockerfile per CLAUDE.md (non-root, pinned versions) with Claude
Code CLI, Playwright + browsers, JDK/Maven/Gradle, Python/pytest, Go, Semgrep,
Gitleaks, OSV-Scanner, ZAP baseline, axe-core. Add runner/entrypoint.sh that:
receives a job token and job URL, fetches the job spec from the backend, clones
repos with the short-lived token, starts the app if a start command is given
(otherwise uses the staging URL), loads runner/prompts/qa-agent.md, runs Claude
Code headless (`claude -p ... --output-format json`) with
ANTHROPIC_BASE_URL pointing at the backend LLM proxy, uploads results and
evidence via pre-signed URLs, and calls back. In the backend implement
SandboxLauncher (Docker Java client) with CPU/memory/time limits, no Docker
socket, egress allow-list network, and auto-removal. Implement the LLM proxy
endpoint with per-run token budget and usage logging.
```

## Phase 7 — Run pipeline, plan approval and live progress
```
Start sandboxes with RunSandboxService.launch; the runner currently only runs the
test step, so add the planning step to runner/entrypoint.sh and a planning prompt.
Implement the run pipeline states from CLAUDE.md with RabbitMQ consumers.
PLANNING produces a Markdown test plan; the UI shows it with edit/approve/reject.
RUNNING streams step progress to the UI via Server-Sent Events. Implement result
ingestion: validate findings.json against the JSON schema in Part C, store
findings and evidence keys. Add cancel run, timeouts, and failure handling.
At run start, re-check the staging URL with StagingUrlPolicy, because the
production deny-list may have changed since the project was created. The run-start
endpoint must be POST /api/projects/{id}/runs, which is already rate limited.
```

## Phase 8 — Triage and auto-assignment in Zoho
```
Use ZohoAssignmentService.fileIssue for every issue; it already handles idempotency,
field IDs and auditing. Implement finding/ and routing/: confirm failures by re-running the specific
test once in the sandbox; compute fingerprint (category + normalized title +
file path + route); dedupe against our DB and open Zoho issues; use the
Anthropic Java SDK to classify category/severity only when the runner left it
unknown (structured JSON output, temperature 0); resolve owner in the exact
order in CLAUDE.md; create the Zoho issue with title, steps, expected/actual,
severity, environment, run link, and attach screenshot/trace; add followers;
for HIGH/CRITICAL security require QA_LEAD approval if the setting is on.
Show a findings table in the UI with filters and the Zoho link per bug.
```

## Phase 9 — Reports and dashboard
```
Implement report/: Thymeleaf template + OpenHTMLtoPDF. Contents: summary
(features tested, passed, failed, go/no-go), per-feature results with
screenshots, bugs by department and severity with Zoho links and assignees,
design mismatches side by side, security findings, run cost and duration.
Store HTML and PDF in storage, attach PDF to the Zoho project, show the report
page in the UI with download. Add a project dashboard: pass rate over runs,
open bugs by department, escaped vs caught bugs (manual input), cost per run.
```

## Phase 10 — Hardening
```
Do a security review of the whole codebase against CLAUDE.md security rules and
the OWASP Top 10. Run /security-review, Semgrep and Dependency-Check, fix what
you find, and list anything you could not fix. Add Kubernetes manifests and a
KubernetesJobSandboxLauncher, NetworkPolicy for runner egress, and a
production deployment checklist.
```

## Notes
- **Why Spring Boot sits in the middle:** all secrets, Zoho/GitHub calls and approvals live in the Java backend; the AI only runs inside a disposable container with no real credentials. This is the main safety benefit of the design.
- **Claude's agent loop has no Java SDK** (the Claude Agent SDK is TypeScript/Python), so the sandbox runs Claude Code headless, and Spring Boot controls it. Spring Boot uses the official Anthropic Java SDK only for direct calls like triage.
- Keep the plan-approval step (Phase 7) and the security-approval setting (Phase 8) on during the pilot; turn auto-filing fully on once false alarms are low.
