import { TextField } from "../../../components/form";
import type { StepProps } from "./draft";

export function StagingStep({ draft, update, errors }: StepProps) {
  return (
    <TextField
      label="Staging URL"
      type="url"
      placeholder="https://staging.example.com"
      hint="The agents test this address. Production addresses are refused."
      value={draft.stagingUrl}
      error={errors.stagingUrl}
      onChange={(stagingUrl) => update({ stagingUrl })}
    />
  );
}
