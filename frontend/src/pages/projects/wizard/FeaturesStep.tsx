import { useId, useState } from "react";
import { ApiError } from "../../../api/client";
import { parseFeatureCsv, PRIORITIES, type FeatureCsvResult, type FeatureInput } from "../../../api/projects";
import { SecondaryButton, SelectField, TextAreaField, TextField } from "../../../components/form";
import { emptyFeature, type StepProps } from "./draft";

const PRIORITY_OPTIONS = PRIORITIES.map((value) => ({ value, label: value }));

/** A row nobody has typed in yet. It's replaced by an import, not kept as a blank feature. */
function isBlank(feature: FeatureInput) {
  return !feature.title.trim() && !feature.description.trim() && !feature.acceptanceCriteria.trim();
}

export function FeaturesStep({ draft, update, errors }: StepProps) {
  const fileInputId = useId();
  const [csvResult, setCsvResult] = useState<FeatureCsvResult | null>(null);
  const [csvProblem, setCsvProblem] = useState<string | null>(null);

  function change(index: number, changes: Partial<FeatureInput>) {
    update({ features: draft.features.map((item, position) => (position === index ? { ...item, ...changes } : item)) });
  }

  async function importCsv(file: File) {
    setCsvProblem(null);
    setCsvResult(null);
    try {
      const result = await parseFeatureCsv(file);
      setCsvResult(result);
      update({ features: [...draft.features.filter((feature) => !isBlank(feature)), ...result.features] });
    } catch (error) {
      const fieldMessage = error instanceof ApiError ? error.fieldErrors[0]?.message : undefined;
      setCsvProblem(fieldMessage ? `The file ${fieldMessage}.` : "Could not read that file.");
    }
  }

  return (
    <>
      <div className="flex flex-col gap-1 rounded border border-slate-200 p-3">
        <label htmlFor={fileInputId} className="text-sm font-medium text-slate-800">
          Import from CSV
        </label>
        <input
          id={fileInputId}
          type="file"
          accept=".csv,text/csv"
          className="text-sm"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (file) {
              void importCsv(file);
            }
          }}
        />
        <p className="text-xs text-slate-500">
          Columns: title, description, acceptance_criteria, priority.{" "}
          <a href="/features-template.csv" download className="underline">
            Download a template
          </a>
        </p>
        {csvProblem && <p role="alert" className="text-sm text-red-700">{csvProblem}</p>}
        {csvResult && (
          <div role="status" className="text-sm text-slate-700">
            <p>Imported {csvResult.features.length} features.</p>
            {csvResult.errors.length > 0 && (
              <>
                <p className="mt-1 text-red-700">{csvResult.errors.length} rows were skipped:</p>
                <ul className="list-inside list-disc text-red-700">
                  {csvResult.errors.slice(0, 20).map((error) => (
                    <li key={`${error.row}-${error.message}`}>
                      Row {error.row}: {error.message}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        )}
      </div>

      {errors.features && <p className="text-sm text-red-700">{errors.features}</p>}
      {draft.features.map((feature, index) => (
        <fieldset key={index} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
          <legend className="px-1 text-sm font-medium text-slate-700">Feature {index + 1}</legend>
          <TextField
            label="Title"
            value={feature.title}
            error={errors[`features[${index}].title`]}
            onChange={(title) => change(index, { title })}
          />
          <TextAreaField
            label="Description"
            value={feature.description}
            error={errors[`features[${index}].description`]}
            onChange={(description) => change(index, { description })}
          />
          <TextAreaField
            label="Acceptance criteria"
            value={feature.acceptanceCriteria}
            error={errors[`features[${index}].acceptanceCriteria`]}
            onChange={(acceptanceCriteria) => change(index, { acceptanceCriteria })}
          />
          <SelectField
            label="Priority"
            value={feature.priority}
            options={PRIORITY_OPTIONS}
            error={errors[`features[${index}].priority`]}
            onChange={(priority) => change(index, { priority })}
          />
          <div>
            <SecondaryButton
              label={`Remove feature ${index + 1}`}
              onClick={() => update({ features: draft.features.filter((_, item) => item !== index) })}
            >
              Remove
            </SecondaryButton>
          </div>
        </fieldset>
      ))}
      <div>
        <SecondaryButton onClick={() => update({ features: [...draft.features, emptyFeature()] })}>
          Add feature
        </SecondaryButton>
      </div>
    </>
  );
}
