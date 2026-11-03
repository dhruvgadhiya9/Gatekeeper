import { SelectField, TextField } from "../../../components/form";
import { DEPARTMENT_LABELS } from "../labels";
import { selectOptions, useZohoDirectory } from "../useZohoDirectory";
import type { Draft, StepProps } from "./draft";

export function DepartmentsStep({ draft, update, errors }: StepProps) {
  const zoho = useZohoDirectory(draft.zohoPortalId, draft.zohoProjectId);
  const people = zoho.users.map((user) => ({
    value: user.zpuid,
    label: user.email ? `${user.name} (${user.email})` : user.name,
  }));

  function change(index: number, changes: Partial<Draft["departmentMappings"][number]>) {
    update({
      departmentMappings: draft.departmentMappings.map((item, position) =>
        position === index ? { ...item, ...changes } : item,
      ),
    });
  }

  function personField(label: string, value: string, error: string | undefined, onChange: (value: string) => void, hint?: string) {
    return zoho.mode === "connected" ? (
      <SelectField
        label={label}
        hint={hint}
        value={value}
        options={selectOptions(people, value, "Choose a person")}
        error={error}
        onChange={onChange}
      />
    ) : (
      <TextField label={`${label} (Zoho user ID)`} hint={hint} value={value} error={error} onChange={onChange} />
    );
  }

  return (
    <>
      <p className="text-sm text-slate-600">
        Choose who gets each department's bugs, and a fallback such as the team lead.
      </p>
      {zoho.failed && (
        <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">
          Could not load the people on that Zoho project. Go back and return to this step to try again.
        </p>
      )}
      {errors.departmentMappings && <p className="text-sm text-red-700">{errors.departmentMappings}</p>}
      {draft.departmentMappings.map((mapping, index) => {
        const name = DEPARTMENT_LABELS[mapping.category];
        return (
          <fieldset key={mapping.category} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
            <legend className="px-1 text-sm font-medium text-slate-700">{name}</legend>
            {personField(
              `${name} assignee`,
              mapping.defaultZohoUserId,
              errors[`departmentMappings[${index}].defaultZohoUserId`],
              (defaultZohoUserId) => change(index, { defaultZohoUserId }),
            )}
            {personField(
              `${name} fallback`,
              mapping.fallbackZohoUserId,
              errors[`departmentMappings[${index}].fallbackZohoUserId`],
              (fallbackZohoUserId) => change(index, { fallbackZohoUserId }),
            )}
            {mapping.category === "SECURITY" &&
              personField(
                "Security lead",
                mapping.securityLeadZohoUserId,
                errors[`departmentMappings[${index}].securityLeadZohoUserId`],
                (securityLeadZohoUserId) => change(index, { securityLeadZohoUserId }),
                "Added as a follower on high and critical security bugs.",
              )}
          </fieldset>
        );
      })}
    </>
  );
}
