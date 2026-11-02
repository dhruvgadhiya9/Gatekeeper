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

**Status:** Phase 1 (skeleton and local environment). Login, the intake wizard and the
integrations come in later phases. The API has no authentication until Phase 2, so keep it
on your own machine.

## Prerequisites

- Docker Desktop, running
- JDK 21 and Node 24 (only for running outside Docker). Maven is included as `mvnw`.

## Configure

Copy the example file, then replace the passwords and set `APP_ENCRYPTION_KEY`:

```powershell
Copy-Item infra/.env.example infra/.env
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

The backend refuses to start without a valid key. Keep the key safe, because changing it
makes stored credentials unreadable.

## Run everything in Docker

```powershell
docker compose -f infra/docker-compose.yml up -d --build --wait
```

| Service        | URL                                    |
| -------------- | -------------------------------------- |
| Frontend       | http://localhost:5173                  |
| Backend health | http://localhost:8081/actuator/health  |
| API docs       | http://localhost:8081/api/docs/ui      |
| RabbitMQ UI    | http://localhost:15672                 |
| MinIO console  | http://localhost:9001                  |

Stop it with `docker compose -f infra/docker-compose.yml down`. Add `-v` to also delete the data.

## Run the backend and frontend outside Docker

Start the infrastructure, then run each app in its own terminal:

```powershell
docker compose -f infra/docker-compose.yml up -d --wait postgres rabbitmq minio

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
