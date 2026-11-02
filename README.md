# Gatekeeper

Internal QA agent platform. A user registers a project, AI agents test it in a
disposable sandbox, confirmed bugs are filed and assigned in Zoho Projects, and a
report is generated.

| Folder      | Contents                                                     |
| ----------- | ------------------------------------------------------------ |
| `backend/`  | Java 21, Spring Boot 3 API. Holds all secrets and external calls |
| `frontend/` | React, TypeScript, Vite and Tailwind UI                      |
| `runner/`   | Docker sandbox image and the QA agent prompt                 |
| `infra/`    | docker-compose for local development                         |
| `docs/`     | Build plan (`docs/build-phases.md`)                          |

The architecture and rules are in `CLAUDE.md`.

The backend and frontend code doesn't exist yet. It starts in Phase 1 of the build plan.