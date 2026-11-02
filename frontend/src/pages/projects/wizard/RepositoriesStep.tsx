import { useEffect, useState } from "react";
import {
  getGitHubStatus,
  listGitHubInstallations,
  listGitHubRepositories,
  type GitHubInstallation,
  type GitHubRepository,
} from "../../../api/integrations";
import type { RepositoryInput, RepositoryType } from "../../../api/projects";
import { SecondaryButton, SelectField, TextField } from "../../../components/form";
import type { StepProps } from "./draft";

const TYPES: readonly { value: RepositoryType; label: string }[] = [
  { value: "FRONTEND", label: "Frontend" },
  { value: "BACKEND", label: "Backend" },
  { value: "OTHER", label: "Other" },
];

/** "typed" until the GitHub App is set up, and if its status can't be read. */
type PickerState =
  | { mode: "checking" }
  | { mode: "typed" }
  | { mode: "failed" }
  | { mode: "picker"; installations: GitHubInstallation[] };

function useGitHubPicker() {
  const [picker, setPicker] = useState<PickerState>({ mode: "checking" });
  const [repositories, setRepositories] = useState<Record<number, GitHubRepository[]>>({});

  useEffect(() => {
    let ignore = false;
    async function load() {
      let configured: boolean;
      try {
        configured = (await getGitHubStatus()).configured;
      } catch {
        configured = false;
      }
      if (!configured) {
        return { mode: "typed" } as const;
      }
      try {
        return { mode: "picker", installations: await listGitHubInstallations() } as const;
      } catch {
        return { mode: "failed" } as const;
      }
    }
    void load().then((state) => {
      if (!ignore) {
        setPicker(state);
      }
    });
    return () => {
      ignore = true;
    };
  }, []);

  async function loadRepositories(installationId: number) {
    if (repositories[installationId]) {
      return;
    }
    const loaded = await listGitHubRepositories(installationId);
    setRepositories((current) => ({ ...current, [installationId]: loaded }));
  }

  return { picker, repositories, loadRepositories };
}

const NOT_CHOSEN = "";

export function RepositoriesStep({ draft, update, errors }: StepProps) {
  const { picker, repositories, loadRepositories } = useGitHubPicker();
  const [loadProblem, setLoadProblem] = useState<string | null>(null);

  function change(index: number, changes: Partial<RepositoryInput>) {
    update({
      repositories: draft.repositories.map((item, position) => (position === index ? { ...item, ...changes } : item)),
    });
  }

  function loadForInstallation(installationId: number) {
    setLoadProblem(null);
    loadRepositories(installationId).catch(() => setLoadProblem("Could not load that account's repositories."));
  }

  const savedInstallations = draft.repositories.map((repository) => repository.installationId).join(",");
  useEffect(() => {
    if (picker.mode !== "picker") {
      return;
    }
    draft.repositories.forEach((repository) => {
      if (repository.installationId) {
        loadForInstallation(repository.installationId);
      }
    });
    // Runs when the picker opens or a row's account changes, not on every keystroke.
  }, [picker.mode, savedInstallations]);

  return (
    <>
      {picker.mode === "typed" && (
        <p className="text-sm text-slate-600">
          The GitHub App isn't connected yet, so type each repository as owner/repository.
        </p>
      )}
      {picker.mode === "failed" && (
        <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">
          Could not load your GitHub accounts. Go back and return to this step to try again.
        </p>
      )}
      {picker.mode === "picker" && picker.installations.length === 0 && (
        <p role="alert" className="rounded bg-amber-50 p-3 text-sm text-amber-900">
          The GitHub App isn't installed on any account yet. Install it, then return to this step.
        </p>
      )}
      {loadProblem && <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{loadProblem}</p>}
      {errors.repositories && <p className="text-sm text-red-700">{errors.repositories}</p>}

      {draft.repositories.map((repository, index) => {
        const available = repository.installationId ? repositories[repository.installationId] : undefined;
        return (
          <fieldset key={index} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
            <legend className="px-1 text-sm font-medium text-slate-700">Repository {index + 1}</legend>
            <SelectField
              label="Type"
              value={repository.type}
              options={TYPES}
              error={errors[`repositories[${index}].type`]}
              onChange={(type) => change(index, { type })}
            />
            {picker.mode === "picker" ? (
              <>
                <SelectField
                  label="GitHub account"
                  value={repository.installationId ? String(repository.installationId) : NOT_CHOSEN}
                  options={[
                    { value: NOT_CHOSEN, label: "Choose an account" },
                    ...picker.installations.map((item) => ({ value: String(item.id), label: item.account })),
                  ]}
                  error={errors[`repositories[${index}].installationId`]}
                  onChange={(value) =>
                    change(index, { installationId: value ? Number(value) : null, repoFullName: "", defaultBranch: "" })
                  }
                />
                <SelectField
                  label="Repository"
                  value={repository.repoFullName}
                  options={[
                    {
                      value: repository.repoFullName,
                      label: repository.repoFullName || (available ? "Choose a repository" : "Choose an account first"),
                    },
                    ...(available ?? [])
                      .filter((item) => item.fullName !== repository.repoFullName)
                      .map((item) => ({ value: item.fullName, label: item.fullName })),
                  ]}
                  error={errors[`repositories[${index}].repoFullName`]}
                  onChange={(fullName) =>
                    change(index, {
                      repoFullName: fullName,
                      defaultBranch: available?.find((item) => item.fullName === fullName)?.defaultBranch ?? "",
                    })
                  }
                />
              </>
            ) : (
              <TextField
                label="Repository"
                placeholder="owner/repository"
                value={repository.repoFullName}
                error={errors[`repositories[${index}].repoFullName`]}
                onChange={(repoFullName) => change(index, { repoFullName })}
              />
            )}
            <TextField
              label="Default branch"
              value={repository.defaultBranch}
              error={errors[`repositories[${index}].defaultBranch`]}
              onChange={(defaultBranch) => change(index, { defaultBranch })}
            />
            <div>
              <SecondaryButton
                label={`Remove repository ${index + 1}`}
                onClick={() => update({ repositories: draft.repositories.filter((_, item) => item !== index) })}
              >
                Remove
              </SecondaryButton>
            </div>
          </fieldset>
        );
      })}
      <div>
        <SecondaryButton
          onClick={() =>
            update({
              repositories: [...draft.repositories, { type: "OTHER", repoFullName: "", defaultBranch: "main" }],
            })
          }
        >
          Add repository
        </SecondaryButton>
      </div>
    </>
  );
}
