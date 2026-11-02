import { SecondaryButton, TextField } from "../../../components/form";
import type { Draft, StepProps } from "./draft";

export function TestAccountsStep({ draft, update, errors }: StepProps) {
  function change(index: number, changes: Partial<Draft["testAccounts"][number]>) {
    update({
      testAccounts: draft.testAccounts.map((item, position) => (position === index ? { ...item, ...changes } : item)),
    });
  }

  return (
    <>
      <p className="text-sm text-slate-600">
        Add a login for each role the agents should test. Use test accounts only. Passwords are stored encrypted and never
        shown again.
      </p>
      {draft.testAccounts.map((account, index) => (
        <fieldset key={index} className="flex flex-col gap-3 rounded border border-slate-200 p-3">
          <legend className="px-1 text-sm font-medium text-slate-700">Account {index + 1}</legend>
          <TextField
            label="Role"
            placeholder="admin"
            value={account.role}
            error={errors[`testAccounts[${index}].role`]}
            onChange={(role) => change(index, { role })}
          />
          <TextField
            label="Username"
            value={account.username}
            error={errors[`testAccounts[${index}].username`]}
            onChange={(username) => change(index, { username })}
          />
          <TextField
            label="Password"
            type="password"
            autoComplete="new-password"
            value={account.password}
            error={errors[`testAccounts[${index}].password`]}
            onChange={(password) => change(index, { password })}
          />
          <TextField
            label="Login page URL"
            type="url"
            hint="Must be on the staging site or one of its subdomains."
            value={account.loginUrl}
            error={errors[`testAccounts[${index}].loginUrl`]}
            onChange={(loginUrl) => change(index, { loginUrl })}
          />
          <div>
            <SecondaryButton
              label={`Remove account ${index + 1}`}
              onClick={() => update({ testAccounts: draft.testAccounts.filter((_, item) => item !== index) })}
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
              testAccounts: [...draft.testAccounts, { role: "", username: "", password: "", loginUrl: draft.stagingUrl }],
            })
          }
        >
          Add test account
        </SecondaryButton>
      </div>
    </>
  );
}
