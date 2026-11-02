import { TextField } from "../../../components/form";
import type { StepProps } from "./draft";

export function BasicsStep({ draft, update, errors }: StepProps) {
  return (
    <>
      <TextField label="Project name" value={draft.name} error={errors.name} onChange={(name) => update({ name })} />
      <TextField
        label="Zoho portal ID"
        value={draft.zohoPortalId}
        error={errors.zohoPortalId}
        onChange={(zohoPortalId) => update({ zohoPortalId })}
      />
      <TextField
        label="Zoho project ID"
        value={draft.zohoProjectId}
        error={errors.zohoProjectId}
        onChange={(zohoProjectId) => update({ zohoProjectId })}
      />
    </>
  );
}
