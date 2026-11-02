import type { CreateProjectRequest, DepartmentCategory, FeatureInput, RepositoryType } from "../../../api/projects";

/**
 * What the wizard has collected so far. It stays in memory only: it holds passwords and tokens, so
 * it must never be written to browser storage.
 */
export interface Draft {
  name: string;
  zohoPortalId: string;
  zohoProjectId: string;
  designType: "NONE" | "FIGMA" | "UPLOAD";
  figmaUrl: string;
  figmaToken: string;
  designFiles: File[];
  repositories: { type: RepositoryType; repoFullName: string; defaultBranch: string }[];
  stagingUrl: string;
  testAccounts: { role: string; username: string; password: string; loginUrl: string }[];
  features: FeatureInput[];
  departmentMappings: {
    category: DepartmentCategory;
    defaultZohoUserId: string;
    fallbackZohoUserId: string;
    securityLeadZohoUserId: string;
  }[];
}

/** Field errors keyed by the same paths the server uses, such as features[2].title. */
export type Errors = Record<string, string>;

export interface StepProps {
  draft: Draft;
  update: (changes: Partial<Draft>) => void;
  errors: Errors;
}

export const MAX_DESIGN_FILES = 50;
export const MAX_DESIGN_FILE_BYTES = 10 * 1024 * 1024;

export const emptyFeature = (): FeatureInput => ({
  title: "",
  description: "",
  acceptanceCriteria: "",
  priority: "MEDIUM",
});

const emptyMapping = (category: DepartmentCategory) => ({
  category,
  defaultZohoUserId: "",
  fallbackZohoUserId: "",
  securityLeadZohoUserId: "",
});

export const initialDraft = (): Draft => ({
  name: "",
  zohoPortalId: "",
  zohoProjectId: "",
  designType: "NONE",
  figmaUrl: "",
  figmaToken: "",
  designFiles: [],
  repositories: [
    { type: "FRONTEND", repoFullName: "", defaultBranch: "main" },
    { type: "BACKEND", repoFullName: "", defaultBranch: "main" },
  ],
  stagingUrl: "",
  testAccounts: [],
  features: [emptyFeature()],
  departmentMappings: (["DESIGN", "FRONTEND", "BACKEND", "SECURITY"] as const).map(emptyMapping),
});

export function toRequest(draft: Draft): CreateProjectRequest {
  const design =
    draft.designType === "FIGMA"
      ? { type: "FIGMA" as const, figmaUrl: draft.figmaUrl.trim(), token: draft.figmaToken }
      : draft.designType === "UPLOAD"
        ? { type: "UPLOAD" as const }
        : null;
  return {
    name: draft.name.trim(),
    zohoPortalId: draft.zohoPortalId.trim(),
    zohoProjectId: draft.zohoProjectId.trim(),
    design,
    repositories: draft.repositories.map((repository) => ({
      ...repository,
      repoFullName: repository.repoFullName.trim(),
      defaultBranch: repository.defaultBranch.trim(),
    })),
    stagingUrl: draft.stagingUrl.trim(),
    testAccounts: draft.testAccounts.map((account) => ({
      ...account,
      role: account.role.trim(),
      username: account.username.trim(),
      loginUrl: account.loginUrl.trim(),
    })),
    features: draft.features,
    departmentMappings: draft.departmentMappings.map((mapping) => ({
      category: mapping.category,
      defaultZohoUserId: mapping.defaultZohoUserId.trim(),
      fallbackZohoUserId: mapping.fallbackZohoUserId.trim(),
      securityLeadZohoUserId: mapping.category === "SECURITY" ? mapping.securityLeadZohoUserId.trim() : null,
    })),
  };
}

const REQUIRED = "This field is required.";
const NEEDS_IMAGE = "Choose at least one image.";
const NEEDS_REPOSITORY = "Add at least one repository.";
const NEEDS_FEATURE = "Add at least one feature.";
const QUICK_CHECK_MESSAGES = new Set([REQUIRED, NEEDS_IMAGE, NEEDS_REPOSITORY, NEEDS_FEATURE]);

function required(errors: Errors, path: string, value: string) {
  if (!value.trim()) {
    errors[path] = REQUIRED;
  }
}

/** Quick checks so people can move on with confidence. The server repeats and extends them. */
const STEP_CHECKS: ((draft: Draft, errors: Errors) => void)[] = [
  (draft, errors) => {
    required(errors, "name", draft.name);
    required(errors, "zohoPortalId", draft.zohoPortalId);
    required(errors, "zohoProjectId", draft.zohoProjectId);
  },
  (draft, errors) => {
    if (draft.designType === "FIGMA") {
      required(errors, "design.figmaUrl", draft.figmaUrl);
      required(errors, "design.token", draft.figmaToken);
    }
    if (draft.designType === "UPLOAD" && draft.designFiles.length === 0) {
      errors["design.files"] = NEEDS_IMAGE;
    }
  },
  (draft, errors) => {
    if (draft.repositories.length === 0) {
      errors.repositories = NEEDS_REPOSITORY;
    }
    draft.repositories.forEach((repository, index) => {
      required(errors, `repositories[${index}].repoFullName`, repository.repoFullName);
      required(errors, `repositories[${index}].defaultBranch`, repository.defaultBranch);
    });
  },
  (draft, errors) => required(errors, "stagingUrl", draft.stagingUrl),
  (draft, errors) => {
    draft.testAccounts.forEach((account, index) => {
      required(errors, `testAccounts[${index}].role`, account.role);
      required(errors, `testAccounts[${index}].username`, account.username);
      required(errors, `testAccounts[${index}].password`, account.password);
      required(errors, `testAccounts[${index}].loginUrl`, account.loginUrl);
    });
  },
  (draft, errors) => {
    if (draft.features.length === 0) {
      errors.features = NEEDS_FEATURE;
    }
    draft.features.forEach((feature, index) => required(errors, `features[${index}].title`, feature.title));
  },
  (draft, errors) => {
    draft.departmentMappings.forEach((mapping, index) => {
      required(errors, `departmentMappings[${index}].defaultZohoUserId`, mapping.defaultZohoUserId);
      required(errors, `departmentMappings[${index}].fallbackZohoUserId`, mapping.fallbackZohoUserId);
      if (mapping.category === "SECURITY") {
        required(errors, `departmentMappings[${index}].securityLeadZohoUserId`, mapping.securityLeadZohoUserId);
      }
    });
  },
];

export function validateStep(step: number, draft: Draft): Errors {
  const errors: Errors = {};
  STEP_CHECKS[step]?.(draft, errors);
  return errors;
}

/**
 * Removes this step's quick-check errors once the user has fixed them. Errors from the server stay
 * until the next submit, because only the server can say whether they're resolved.
 */
export function withoutResolvedErrors(errors: Errors, step: number, draft: Draft): Errors {
  const remaining = validateStep(step, draft);
  return Object.fromEntries(
    Object.entries(errors).filter(([path, message]) => !QUICK_CHECK_MESSAGES.has(message) || path in remaining),
  );
}

const STEP_BY_FIELD: Record<string, number> = {
  name: 0,
  zohoPortalId: 0,
  zohoProjectId: 0,
  design: 1,
  repositories: 2,
  stagingUrl: 3,
  testAccounts: 4,
  features: 5,
  departmentMappings: 6,
};

/** The first wizard step with an error, so the user lands where they can fix it. */
export function firstStepWithError(fields: string[]): number | null {
  const steps = fields
    .map((field) => STEP_BY_FIELD[field.split(/[.[]/, 1)[0] ?? ""])
    .filter((step): step is number => step !== undefined);
  return steps.length > 0 ? Math.min(...steps) : null;
}
