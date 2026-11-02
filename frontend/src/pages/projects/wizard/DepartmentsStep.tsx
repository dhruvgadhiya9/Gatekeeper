import { TextField } from "../../../components/form";
import { DEPARTMENT_LABELS } from "../labels";
import type { Draft, StepProps } from "./draft";

export function DepartmentsStep({ draft, update, errors }: StepProps) {
  function change(index: number, changes: Partial<Draft["departmentMappings"][number]>) {
    update({
      departmentMappings: draft.departmentMappings.map((item, position) =>
        position === index ? { ...item, ...changes } : item,
      ),
    });
  }

  return (
    <>
      <p className="text-sm text-slate-600">
        Enter the Zoho user who gets each department's bugs, and a fallback such as the team lead.
      </p>
      {errors.departmentMappings && <p className="text-sm text-red-700">{errors.departmentMappings}</p>}
      {draft.departmentMappings.map((mapping, index) => {
        const name = DEPARTMENT_LABELS[mapping.category];
        return (
          <fieldset key={mapping.category} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
            <legend className="px-1 text-sm font-medium text-slate-700">{name}</legend>
            <TextField
              label={`${name} assignee (Zoho user ID)`}
              value={mapping.defaultZohoUserId}
              error={errors[`departmentMappings[${index}].defaultZohoUserId`]}
              onChange={(defaultZohoUserId) => change(index, { defaultZohoUserId })}
            />
            <TextField
              label={`${name} fallback (Zoho user ID)`}
              value={mapping.fallbackZohoUserId}
              error={errors[`departmentMappings[${index}].fallbackZohoUserId`]}
              onChange={(fallbackZohoUserId) => change(index, { fallbackZohoUserId })}
            />
            {mapping.category === "SECURITY" && (
              <TextField
                label="Security lead (Zoho user ID)"
                hint="Added as a follower on high and critical security bugs."
                value={mapping.securityLeadZohoUserId}
                error={errors[`departmentMappings[${index}].securityLeadZohoUserId`]}
                onChange={(securityLeadZohoUserId) => change(index, { securityLeadZohoUserId })}
              />
            )}
          </fieldset>
        );
      })}
    </>
  );
}
