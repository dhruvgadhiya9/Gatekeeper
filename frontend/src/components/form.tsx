import { useId, type ReactNode } from "react";

const inputClass =
  "w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 aria-[invalid=true]:border-red-600";

interface FieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
}

function FieldWrapper({ id, label, error, hint, children }: Omit<FieldProps, "value" | "onChange"> & { id: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-slate-800">
        {label}
      </label>
      {children}
      {hint && <p id={`${id}-hint`} className="text-xs text-slate-500">{hint}</p>}
      {error && <p id={`${id}-error`} className="text-sm text-red-700">{error}</p>}
    </div>
  );
}

function describedBy(id: string, error?: string, hint?: string) {
  const ids = [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean);
  return ids.length > 0 ? ids.join(" ") : undefined;
}

export function TextField({
  type = "text",
  autoComplete,
  placeholder,
  ...props
}: FieldProps & { type?: "text" | "password" | "url"; autoComplete?: string; placeholder?: string }) {
  const id = useId();
  return (
    <FieldWrapper id={id} {...props}>
      <input
        id={id}
        type={type}
        value={props.value}
        autoComplete={autoComplete ?? "off"}
        placeholder={placeholder}
        aria-invalid={Boolean(props.error)}
        aria-describedby={describedBy(id, props.error, props.hint)}
        onChange={(event) => props.onChange(event.target.value)}
        className={inputClass}
      />
    </FieldWrapper>
  );
}

export function TextAreaField(props: FieldProps) {
  const id = useId();
  return (
    <FieldWrapper id={id} {...props}>
      <textarea
        id={id}
        rows={3}
        value={props.value}
        aria-invalid={Boolean(props.error)}
        aria-describedby={describedBy(id, props.error, props.hint)}
        onChange={(event) => props.onChange(event.target.value)}
        className={inputClass}
      />
    </FieldWrapper>
  );
}

export function SelectField<T extends string>({
  options,
  ...props
}: Omit<FieldProps, "onChange" | "value"> & {
  value: T;
  onChange: (value: T) => void;
  options: readonly { value: T; label: string }[];
}) {
  const id = useId();
  return (
    <FieldWrapper id={id} {...props}>
      <select
        id={id}
        value={props.value}
        aria-invalid={Boolean(props.error)}
        aria-describedby={describedBy(id, props.error, props.hint)}
        onChange={(event) => props.onChange(event.target.value as T)}
        className={inputClass}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </FieldWrapper>
  );
}

export function SecondaryButton(props: { onClick: () => void; children: ReactNode; label?: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={props.onClick}
      disabled={props.disabled}
      aria-label={props.label}
      className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-50"
    >
      {props.children}
    </button>
  );
}
