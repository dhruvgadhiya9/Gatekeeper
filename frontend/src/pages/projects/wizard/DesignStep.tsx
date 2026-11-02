import { useId, useState } from "react";
import { SecondaryButton, SelectField, TextField } from "../../../components/form";
import { MAX_DESIGN_FILE_BYTES, MAX_DESIGN_FILES, type StepProps } from "./draft";

const DESIGN_TYPES = [
  { value: "NONE", label: "No design to compare" },
  { value: "FIGMA", label: "Figma file" },
  { value: "UPLOAD", label: "Upload images" },
] as const;

export function DesignStep({ draft, update, errors }: StepProps) {
  const fileInputId = useId();
  const [fileProblem, setFileProblem] = useState<string | null>(null);

  function addFiles(selected: File[]) {
    const accepted = selected.filter(
      (file) => /\.(png|jpe?g)$/i.test(file.name) && file.size <= MAX_DESIGN_FILE_BYTES,
    );
    const combined = [...draft.designFiles, ...accepted];
    if (combined.length > MAX_DESIGN_FILES) {
      setFileProblem(`A project can have at most ${MAX_DESIGN_FILES} images.`);
      return;
    }
    setFileProblem(
      accepted.length < selected.length ? "Some files were skipped. Images must be PNG or JPEG, up to 10 MB." : null,
    );
    update({ designFiles: combined });
  }

  return (
    <>
      <SelectField
        label="Design source"
        value={draft.designType}
        options={DESIGN_TYPES}
        error={errors["design.type"]}
        onChange={(designType) => update({ designType })}
      />
      {draft.designType === "FIGMA" && (
        <>
          <TextField
            label="Figma file link"
            type="url"
            placeholder="https://www.figma.com/design/…"
            value={draft.figmaUrl}
            error={errors["design.figmaUrl"]}
            onChange={(figmaUrl) => update({ figmaUrl })}
          />
          <TextField
            label="Figma access token"
            type="password"
            autoComplete="new-password"
            hint="Stored encrypted and never shown again."
            value={draft.figmaToken}
            error={errors["design.token"]}
            onChange={(figmaToken) => update({ figmaToken })}
          />
        </>
      )}
      {draft.designType === "UPLOAD" && (
        <div className="flex flex-col gap-2">
          <label htmlFor={fileInputId} className="text-sm font-medium text-slate-800">
            Design images
          </label>
          <input
            id={fileInputId}
            type="file"
            multiple
            accept="image/png,image/jpeg"
            className="text-sm"
            onChange={(event) => {
              addFiles(Array.from(event.target.files ?? []));
              event.target.value = "";
            }}
          />
          <p className="text-xs text-slate-500">PNG or JPEG, up to 10 MB each. They upload when you create the project.</p>
          {(fileProblem ?? errors["design.files"]) && (
            <p className="text-sm text-red-700">{fileProblem ?? errors["design.files"]}</p>
          )}
          {draft.designFiles.length > 0 && (
            <ul className="flex flex-col gap-1">
              {draft.designFiles.map((file, index) => (
                <li key={`${file.name}-${index}`} className="flex items-center justify-between gap-2 text-sm text-slate-700">
                  <span className="break-all">{file.name}</span>
                  <SecondaryButton
                    label={`Remove ${file.name}`}
                    onClick={() => update({ designFiles: draft.designFiles.filter((_, item) => item !== index) })}
                  >
                    Remove
                  </SecondaryButton>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  );
}
