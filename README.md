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

**Status:** Phase 2 complete (skeleton, local environment, login, roles and audit log). The
intake wizard and the integrations come in later phases.

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

## CI

`.github/workflows/ci.yml` builds and tests both apps on pushes and PRs to `main` and
`Develop`, and runs OWASP Dependency-Check. Add an `NVD_API_KEY` repository secret
([free key](https://nvd.nist.gov/developers/request-an-api-key)), because the vulnerability
database download is very slow without one.
