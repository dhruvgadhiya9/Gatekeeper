# Gatekeeper

Internal QA agent platform. A user registers a project, AI agents test it in a
disposable sandbox, confirmed bugs are filed and assigned in Zoho Projects, and a
report is generated.

| Folder      | Contents                                                         |
| ----------- | ---------------------------------------------------------------- |
| `backend/`  | Java 21, Spring Boot 3 API. Holds all secrets and external calls |
| `frontend/` | React, TypeScript, Vite and Tailwind UI                          |
| `runner/`   | Sandbox image (placeholder until Phase 6) and the QA agent prompt |
| `infra/`    | docker-compose for local development                             |
| `docs/`     | Build plan (`docs/build-phases.md`)                              |

- Architecture and rules: `CLAUDE.md`
- Branching and commit conventions: `CONTRIBUTING.md`

**Status:** Phase 6 complete (skeleton, local environment, login, roles, audit log, project intake
and editing, GitHub App, Figma and Zoho Projects integrations, runner sandbox and LLM proxy). The
sandbox works, but nothing in the app starts a run until Phase 7.

## Prerequisites

- Docker Desktop, running
- JDK 21 and Node 24 (only for running outside Docker). Maven is included as `mvnw`.

## Configure

Copy the example file, then replace every `change-me` value. Run this command twice, using
one result for `APP_ENCRYPTION_KEY` and the other for `APP_JWT_SECRET`:

```powershell
Copy-Item infra/.env.example infra/.env
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

The backend refuses to start without valid keys. Keep `APP_ENCRYPTION_KEY` safe, because
changing it makes stored credentials unreadable.

## Run everything in Docker

```powershell
docker compose -f infra/docker-compose.yml up -d --build --wait
```

| Service        | URL                                    |
| -------------- | -------------------------------------- |
| Frontend       | http://localhost:5173                  |
| Backend health | http://localhost:8081/actuator/health  |
| API docs (sign in first) | http://localhost:5173/api/docs/ui |
| Keycloak admin | http://keycloak.localhost:8180         |
| RabbitMQ UI    | http://localhost:15672                 |
| MinIO console  | http://localhost:9001                  |

Stop it with `docker compose -f infra/docker-compose.yml down`. Add `-v` to also delete the data.

## Sign in

Open http://localhost:5173 and sign in as `admin`, `qalead` or `viewer`. All three use the
`KEYCLOAK_TEST_USER_PASSWORD` value from `infra/.env`.

- `admin` becomes an admin because its verified email is listed in `APP_ADMIN_EMAILS`.
- Everyone else starts as Viewer. Admins change roles on the **Users** page, and changes apply
  immediately. The `qalead` account is a Viewer until an admin promotes it.
- Keycloak's realm is only imported when it doesn't exist yet, and it's lost when the
  container is recreated, so test users always come from `infra/keycloak/gatekeeper-realm.json`.

To use another provider, change the `OIDC_*` values in `infra/.env` and register
`<PUBLIC_URL>/login/oauth2/code/oidc` as the redirect URI with that provider. The provider must
send a verified email claim for `APP_ADMIN_EMAILS` and `APP_ALLOWED_EMAIL_DOMAINS` to apply.
Set `APP_ALLOWED_EMAIL_DOMAINS` for Google, and `COOKIE_SECURE=true` anywhere other than local HTTP.

Signing out ends the Gatekeeper session only, not the session at the identity provider.

## Create a project

Admins and QA leads use **Projects > New project**. Viewers can read projects but not create them.

Before real use, set `APP_PRODUCTION_DOMAINS` in `infra/.env` so that production can't be entered
as a staging URL. With it empty, only local and cloud metadata addresses are refused.

The wizard's Features step accepts a CSV with the columns `title` (required), `description`,
`acceptance_criteria` and `priority`, and links to a template.

Admins and QA leads can edit a project. Password and token fields start empty, which keeps the
stored values. If a login page moves to another site, its password must be entered again. Only
admins can delete a project, and a project with test runs can't be deleted.

## Connect GitHub

Until this is done, the wizard takes repositories typed as `owner/repository`.

1. Create a GitHub App in your organization's developer settings. Its webhook can stay inactive.
2. Give it these repository permissions: **Contents: Read**, **Metadata: Read**, **Pull requests: Read and write**.
3. Generate a private key, and install the App on the repositories to be tested.
4. Set `GITHUB_APP_ID` and `GITHUB_APP_PRIVATE_KEY` in `infra/.env` (`.env.example` shows how to
   encode the key), then restart the backend.

The wizard then offers accounts and repositories to choose from. Existing projects must choose
theirs the next time they're edited.

## Connect Zoho Projects

Until this is done, the wizard takes typed Zoho IDs.

1. In the [Zoho API console](https://api-console.zoho.com/), create a **Server-based Application**.
   Its redirect URI is `<PUBLIC_URL>/api/integrations/zoho/callback`.
2. Set `ZOHO_CLIENT_ID`, `ZOHO_CLIENT_SECRET` and `ZOHO_DATA_CENTER` in `infra/.env`, then restart
   the backend.
3. As an admin, open **Settings** and choose **Connect Zoho**. The wizard then offers portals,
   projects and people. Existing projects are checked against Zoho the next time they're edited.
4. In **Settings**, enter the severity and classification IDs for each portal, and map GitHub
   usernames to Zoho users. Zoho has no documented way to list those IDs. A blank one leaves that
   field unset on the bug, and its value is written in the description.

Attaching screenshots and reports requires WorkDrive access (`WorkDrive.files.ALL` and
`WorkDrive.workspace.ALL`), because that's how Zoho stores files. To avoid granting it, set
`ZOHO_ATTACHMENTS_ENABLED=false` before connecting.

Disconnecting in **Settings** revokes Gatekeeper's access at Zoho.

## Sandboxes

Each run gets a disposable container with the testing tools and no secrets.

| It gets | It never gets |
| ------- | ------------- |
| A job token for this run only, which stops working when the run ends | The Anthropic API key |
| This project's test logins | Zoho, GitHub App or database credentials |
| A read-only clone token per repository, removed after cloning | The Docker socket or any host files |

Its only network route is an egress proxy that allows the staging site, the backend, storage and
package registries. It runs as non-root on a read-only filesystem, with CPU, memory and time limits.
Model requests go through the backend's proxy, which adds the real key, stops a run at its token
budget (`SANDBOX_TOKEN_BUDGET`) and records usage.

To enable them:

```powershell
docker build -t gatekeeper-egress-proxy:local runner/egress-proxy
docker build -t gatekeeper-runner:local runner
docker compose -f infra/docker-compose.yml -f infra/docker-compose.sandbox.yml up -d --build --wait
```

Set `ANTHROPIC_API_KEY` in `infra/.env` first. **Runs cost money**: each one is billed per token, up
to its budget. The sandbox override gives the backend control of Docker, which is the same as root
on the machine, so use it only for local development.

## Use Figma designs

Create a Figma personal access token with the **File content: Read** scope (`file_content:read`),
and enter it with the file's link in the wizard's Design step. On the project page, **Choose frames
from Figma** then stores the chosen frames as PNG images for comparison.

## Run the backend and frontend outside Docker

The backend must resolve `keycloak.localhost`, which browsers do automatically but Java on
Windows doesn't. Add this line once to `C:\Windows\System32\drivers\etc\hosts` as an administrator:

```
127.0.0.1 keycloak.localhost
```

Start the infrastructure, then run each app in its own terminal:

```powershell
docker compose -f infra/docker-compose.yml up -d --wait postgres rabbitmq minio keycloak

cd backend
./mvnw spring-boot:run -D"spring-boot.run.profiles=local"

cd frontend
npm install
npm run dev
```

`JAVA_HOME` must point to JDK 21, even if `java -version` already shows 21, because `mvnw`
reads `JAVA_HOME` first.

## Test

```powershell
cd backend; ./mvnw verify      # Docker must be running for the PostgreSQL tests
cd frontend; npm test; npm run build
```

Two more test groups need locally built images. Neither spends money, because the model is simulated:

```powershell
cd backend
# Isolation on real Docker: non-root, read-only, and the egress allow-list.
./mvnw test -D"test.excludedGroups=" -Dgroups=docker-sandbox
# A full run with the real runner image and Claude Code CLI. Needs Docker Desktop.
./mvnw test -D"test.excludedGroups=" -Dgroups=runner-image
```

## CI

`.github/workflows/ci.yml` builds and tests both apps on pushes and PRs to `main` and
`Develop`, and runs OWASP Dependency-Check. Add an `NVD_API_KEY` repository secret
([free key](https://nvd.nist.gov/developers/request-an-api-key)), because the vulnerability
database download is very slow without one.
