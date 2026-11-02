# Contributing

## Branches

| Branch    | Purpose                                     | Rules                            |
| --------- | ------------------------------------------- | -------------------------------- |
| `main`    | Released, production-ready code             | Merge from `Develop` by PR only  |
| `Develop` | Integration branch; the next release        | Merge from short-lived branches  |

Short-lived branches start from `Develop` and merge back into it:

| Prefix       | Use for                             | Example                         |
| ------------ | ----------------------------------- | ------------------------------- |
| `feature/`   | New functionality                   | `feature/backend-domain-model`  |
| `fix/`       | Bug fixes                           | `fix/staging-url-validation`    |
| `chore/`     | Tooling and maintenance             | `chore/project-setup`           |
| `docs/`      | Documentation only                  | `docs/local-setup`              |
| `ci/`        | CI pipelines                        | `ci/github-actions`             |
| `hotfix/`    | Urgent fix; branches from `main`    | `hotfix/token-leak`             |

Use lowercase, hyphenated names, and keep each branch to one concern.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <imperative summary>
```

- Types: `feat`, `fix`, `docs`, `chore`, `ci`, `test`, `refactor`, `build`.
- Scopes: `backend`, `frontend`, `runner`, `infra`, `repo`.

Example: `feat(backend): add AES-GCM converter for encrypted columns`

## Merging

1. Update your branch from `Develop`.
2. Make sure the build and tests pass.
3. Open a PR into `Develop` and merge with a merge commit (`--no-ff`) so each
   change stays visible as a unit in the history.
4. Delete the branch after merging.

## Never commit

Secrets, `.env` files, tokens or real test credentials. Only `.env.example` with
placeholder values belongs in the repo.
