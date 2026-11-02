import type { RepositoryType } from "../../../api/projects";
import { SecondaryButton, SelectField, TextField } from "../../../components/form";
import type { Draft, StepProps } from "./draft";

const TYPES: readonly { value: RepositoryType; label: string }[] = [
  { value: "FRONTEND", label: "Frontend" },
  { value: "BACKEND", label: "Backend" },
  { value: "OTHER", label: "Other" },
];

export function RepositoriesStep({ draft, update, errors }: StepProps) {
  function change(index: number, changes: Partial<Draft["repositories"][number]>) {
    update({
      repositories: draft.repositories.map((item, position) => (position === index ? { ...item, ...changes } : item)),
    });
  }

  return (
    <>
      {errors.repositories && <p className="text-sm text-red-700">{errors.repositories}</p>}
      {draft.repositories.map((repository, index) => (
        <fieldset key={index} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
          <legend className="px-1 text-sm font-medium text-slate-700">Repository {index + 1}</legend>
          <SelectField
            label="Type"
            value={repository.type}
            options={TYPES}
            error={errors[`repositories[${index}].type`]}
            onChange={(type) => change(index, { type })}
          />
          <TextField
            label="Repository"
            placeholder="owner/repository"
            value={repository.repoFullName}
            error={errors[`repositories[${index}].repoFullName`]}
            onChange={(repoFullName) => change(index, { repoFullName })}
          />
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
      ))}
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
