import { useEffect, useRef, useState, type ComponentType } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ApiError } from "../../../api/client";
import { createProject, getProject, updateProject, uploadDesigns, type ProjectDetail } from "../../../api/projects";
import { SecondaryButton } from "../../../components/form";
import { BasicsStep } from "./BasicsStep";
import { DepartmentsStep } from "./DepartmentsStep";
import { DesignStep } from "./DesignStep";
import {
  draftFromProject,
  firstStepWithError,
  initialDraft,
  toRequest,
  validateStep,
  withoutResolvedErrors,
  type Draft,
  type Errors,
  type StepProps,
} from "./draft";
import { FeaturesStep } from "./FeaturesStep";
import { RepositoriesStep } from "./RepositoriesStep";
import { StagingStep } from "./StagingStep";
import { TestAccountsStep } from "./TestAccountsStep";

const STEPS: { title: string; component: ComponentType<StepProps> }[] = [
  { title: "Project", component: BasicsStep },
  { title: "Design", component: DesignStep },
  { title: "Repositories", component: RepositoriesStep },
  { title: "Staging", component: StagingStep },
  { title: "Test accounts", component: TestAccountsStep },
  { title: "Features", component: FeaturesStep },
  { title: "Departments", component: DepartmentsStep },
];

/** @param existing the project being edited; without it, the wizard creates a new one */
function ProjectWizard({ existing }: { existing?: ProjectDetail }) {
  const navigate = useNavigate();
  const [draft, setDraft] = useState<Draft>(() => (existing ? draftFromProject(existing) : initialDraft()));
  const [step, setStep] = useState(0);
  const [errors, setErrors] = useState<Errors>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const current = STEPS[step] ?? STEPS[0]!;
  const CurrentStep = current.component;
  const isLastStep = step === STEPS.length - 1;
  const submitLabel = existing ? "Save changes" : "Create project";

  function goTo(next: number) {
    setStep(next);
    window.scrollTo({ top: 0 });
    // Moves keyboard and screen reader users to the new step.
    requestAnimationFrame(() => headingRef.current?.focus());
  }

  function update(changes: Partial<Draft>) {
    setDraft((previous) => ({ ...previous, ...changes }));
    setErrors((current) => withoutResolvedErrors(current, step, { ...draft, ...changes }));
  }

  function next() {
    const found = validateStep(step, draft);
    setErrors(found);
    if (Object.keys(found).length === 0) {
      setProblem(null);
      goTo(step + 1);
    }
  }

  async function submit() {
    const found = validateStep(step, draft);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSubmitting(true);
    setProblem(null);
    try {
      const project = existing
        ? await updateProject(existing.id, toRequest(draft, existing.version))
        : await createProject(toRequest(draft));
      let uploadFailed = false;
      if (draft.designType === "UPLOAD" && draft.designFiles.length > 0) {
        uploadFailed = await uploadDesigns(project.id, draft.designFiles).then(
          () => false,
          () => true,
        );
      }
      navigate(`/projects/${project.id}`, { state: { uploadFailed } });
    } catch (error) {
      showSubmitError(error);
      setSubmitting(false);
    }
  }

  function showSubmitError(error: unknown) {
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      // The server sends fragments such as "must not be blank", because it doesn't know the label.
      setErrors(Object.fromEntries(error.fieldErrors.map(({ field, message }) => [field, `This field ${message}.`])));
      const target = firstStepWithError(error.fieldErrors.map(({ field }) => field));
      setProblem("Some details need fixing. Nothing has been saved yet.");
      if (target !== null) {
        goTo(target);
      }
      return;
    }
    setProblem(error instanceof ApiError ? error.message : "Could not save the project. Check your connection and try again.");
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5">
      <div className="flex flex-col gap-1">
        <p className="text-sm text-slate-600">
          {existing ? `Editing ${existing.name} · ` : ""}Step {step + 1} of {STEPS.length}
        </p>
        <h1 ref={headingRef} tabIndex={-1} className="text-2xl font-semibold text-slate-900 outline-none">
          {current.title}
        </h1>
      </div>
      <ol className="flex gap-1" aria-hidden="true">
        {STEPS.map((item, index) => (
          <li key={item.title} className={`h-1 flex-1 rounded ${index <= step ? "bg-slate-800" : "bg-slate-200"}`} />
        ))}
      </ol>

      {problem && <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{problem}</p>}

      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (isLastStep) {
            void submit();
          } else {
            next();
          }
        }}
      >
        <CurrentStep draft={draft} update={update} errors={errors} />
        <div className="flex flex-wrap items-center gap-3 border-t border-slate-200 pt-4">
          {step > 0 && (
            <SecondaryButton
              disabled={submitting}
              onClick={() => {
                setErrors({});
                goTo(step - 1);
              }}
            >
              Back
            </SecondaryButton>
          )}
          <Link to={existing ? `/projects/${existing.id}` : "/projects"} className="text-sm text-slate-600 underline">
            Cancel
          </Link>
          <button
            type="submit"
            disabled={submitting}
            className="ml-auto rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
          >
            {isLastStep ? (submitting ? "Saving…" : submitLabel) : "Next"}
          </button>
        </div>
      </form>
    </div>
  );
}

export function ProjectWizardPage() {
  return <ProjectWizard />;
}

export function ProjectEditPage() {
  const { id = "" } = useParams();
  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let ignore = false;
    getProject(id)
      .then((loaded) => {
        if (!ignore) {
          setProject(loaded);
        }
      })
      .catch(() => {
        if (!ignore) {
          setFailed(true);
        }
      });
    return () => {
      ignore = true;
    };
  }, [id]);

  if (failed) {
    return <p role="alert" className="text-slate-700">Could not load this project.</p>;
  }
  return project ? <ProjectWizard existing={project} /> : <p role="status" className="text-slate-600">Loading…</p>;
}
