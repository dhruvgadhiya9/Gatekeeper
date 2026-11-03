import { SelectField, TextField } from "../../../components/form";
import { selectOptions, useZohoDirectory } from "../useZohoDirectory";
import type { Draft, StepProps } from "./draft";

/** A department's people belong to one Zoho project, so choosing another clears them. */
function withoutDepartmentUsers(draft: Draft): Draft["departmentMappings"] {
  return draft.departmentMappings.map((mapping) => ({
    ...mapping,
    defaultZohoUserId: "",
    fallbackZohoUserId: "",
    securityLeadZohoUserId: "",
  }));
}

export function BasicsStep({ draft, update, errors }: StepProps) {
  const zoho = useZohoDirectory(draft.zohoPortalId, draft.zohoProjectId);

  return (
    <>
      <TextField label="Project name" value={draft.name} error={errors.name} onChange={(name) => update({ name })} />

      {zoho.mode === "connected" ? (
        <>
          <SelectField
            label="Zoho portal"
            value={draft.zohoPortalId}
            options={selectOptions(
              zoho.portals.map((portal) => ({ value: portal.id, label: portal.name })),
              draft.zohoPortalId,
              "Choose a portal",
            )}
            error={errors.zohoPortalId}
            onChange={(zohoPortalId) =>
              update({ zohoPortalId, zohoProjectId: "", departmentMappings: withoutDepartmentUsers(draft) })
            }
          />
          <SelectField
            label="Zoho project"
            value={draft.zohoProjectId}
            options={selectOptions(
              zoho.projects.map((project) => ({ value: project.id, label: project.name })),
              draft.zohoProjectId,
              draft.zohoPortalId ? "Choose a project" : "Choose a portal first",
            )}
            error={errors.zohoProjectId}
            onChange={(zohoProjectId) =>
              update({ zohoProjectId, departmentMappings: withoutDepartmentUsers(draft) })
            }
          />
          {zoho.failed && (
            <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">
              Could not load that portal's projects. Try choosing it again.
            </p>
          )}
        </>
      ) : (
        <>
          {zoho.mode === "failed" && (
            <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">
              Could not load your Zoho portals. Go back and return to this step to try again.
            </p>
          )}
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
      )}
    </>
  );
}
