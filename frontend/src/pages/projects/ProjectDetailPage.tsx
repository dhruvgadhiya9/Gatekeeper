import { useEffect, useId, useState, type ReactNode } from "react";
import { Link, useLocation, useParams } from "react-router";
import { ApiError } from "../../api/client";
import { getProject, uploadDesigns, type ProjectDetail } from "../../api/projects";
import { useAuth } from "../../auth/AuthContext";
import { DEPARTMENT_LABELS } from "./labels";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="mb-3 text-lg font-semibold text-slate-900">{title}</h2>
      {children}
    </section>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 py-1 text-sm sm:grid-cols-[12rem_1fr]">
      <dt className="text-slate-600">{label}</dt>
      <dd className="break-words text-slate-900">{children}</dd>
    </div>
  );
}

function DesignSection({
  project,
  canUpload,
  initialProblem,
  onUploaded,
}: {
  project: ProjectDetail;
  canUpload: boolean;
  initialProblem: string | null;
  onUploaded: () => Promise<void>;
}) {
  const inputId = useId();
  const [problem, setProblem] = useState(initialProblem);
  const [uploading, setUploading] = useState(false);
  const design = project.design;

  async function upload(files: File[]) {
    setUploading(true);
    setProblem(null);
    try {
      await uploadDesigns(project.id, files);
      await onUploaded();
    } catch (error) {
      const fieldMessage = error instanceof ApiError ? error.fieldErrors[0]?.message : undefined;
      setProblem(fieldMessage ? `Upload failed: a file ${fieldMessage}.` : "Upload failed. Try again.");
    } finally {
      setUploading(false);
    }
  }

  if (!design) {
    return <Section title="Design"><p className="text-sm text-slate-600">No design to compare.</p></Section>;
  }
  if (design.type === "FIGMA") {
    return (
      <Section title="Design">
        <dl>
          <Detail label="Figma file key">{design.figmaFileKey}</Detail>
          <Detail label="Access token">{design.tokenStored ? "Stored" : "Not stored"}</Detail>
        </dl>
      </Section>
    );
  }
  return (
    <Section title="Design">
      {problem && <p role="alert" className="mb-3 rounded bg-red-50 p-3 text-sm text-red-800">{problem}</p>}
      {design.uploads.length === 0 ? (
        <p className="text-sm text-slate-600">No images uploaded yet.</p>
      ) : (
        <ul className="list-inside list-disc text-sm text-slate-800">
          {design.uploads.map((upload) => (
            <li key={upload.id} className="break-all">{upload.fileName}</li>
          ))}
        </ul>
      )}
      {canUpload && (
        <div className="mt-3 flex flex-col gap-1">
          <label htmlFor={inputId} className="text-sm font-medium text-slate-800">
            {uploading ? "Uploading…" : "Add images"}
          </label>
          <input
            id={inputId}
            type="file"
            multiple
            disabled={uploading}
            accept="image/png,image/jpeg"
            className="text-sm"
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = "";
              if (files.length > 0) {
                void upload(files);
              }
            }}
          />
        </div>
      )}
    </Section>
  );
}

export function ProjectDetailPage() {
  const { id = "" } = useParams();
  const location = useLocation();
  const { state } = useAuth();
  const canEdit = state.status === "authenticated" && state.user.role !== "VIEWER";
  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const uploadFailed = (location.state as { uploadFailed?: boolean } | null)?.uploadFailed === true;

  useEffect(() => {
    let ignore = false;
    getProject(id)
      .then((loaded) => {
        if (!ignore) {
          setProject(loaded);
        }
      })
      .catch((error: unknown) => {
        if (!ignore) {
          setLoadError(error instanceof ApiError && error.status === 404 ? "This project doesn't exist." : "Could not load this project.");
        }
      });
    return () => {
      ignore = true;
    };
  }, [id]);

  if (loadError) {
    return (
      <div className="flex flex-col gap-2">
        <p role="alert" className="text-slate-700">{loadError}</p>
        <Link to="/projects" className="text-sm underline">Back to projects</Link>
      </div>
    );
  }
  if (!project) {
    return <p role="status" className="text-slate-600">Loading…</p>;
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <Link to="/projects" className="text-sm text-slate-600 underline">Projects</Link>
        <h1 className="mt-1 text-2xl font-semibold break-words text-slate-900">{project.name}</h1>
        <p className="text-sm text-slate-600">
          Created by {project.createdBy} on {new Date(project.createdAt).toLocaleDateString()}
        </p>
      </div>

      <Section title="Overview">
        <dl>
          <Detail label="Staging URL">{project.stagingUrl}</Detail>
          <Detail label="Zoho portal ID">{project.zohoPortalId}</Detail>
          <Detail label="Zoho project ID">{project.zohoProjectId}</Detail>
        </dl>
      </Section>

      <DesignSection
        project={project}
        canUpload={canEdit}
        initialProblem={uploadFailed ? "The project was created, but its images didn't upload. Add them again below." : null}
        onUploaded={async () => setProject(await getProject(id))}
      />

      <Section title="Repositories">
        <ul className="flex flex-col gap-1 text-sm text-slate-800">
          {project.repositories.map((repository) => (
            <li key={repository.id} className="break-all">
              <span className="font-medium">{repository.repoFullName}</span> · {repository.type.toLowerCase()} · {repository.defaultBranch}
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Test accounts">
        {project.testAccounts.length === 0 ? (
          <p className="text-sm text-slate-600">No test accounts.</p>
        ) : (
          <ul className="flex flex-col gap-1 text-sm text-slate-800">
            {project.testAccounts.map((account) => (
              <li key={account.id} className="break-all">
                <span className="font-medium">{account.role}</span> · {account.username} · {account.loginUrl}
              </li>
            ))}
          </ul>
        )}
        {project.testAccounts.length > 0 && (
          <p className="mt-2 text-xs text-slate-500">Passwords are stored encrypted and are never shown.</p>
        )}
      </Section>

      <Section title={`Features (${project.features.length})`}>
        <ul className="flex flex-col gap-3 text-sm">
          {project.features.map((feature) => (
            <li key={feature.id}>
              <p className="font-medium text-slate-900">
                {feature.title} <span className="font-normal text-slate-600">· {feature.priority.toLowerCase()}</span>
              </p>
              {feature.description && <p className="whitespace-pre-wrap text-slate-700">{feature.description}</p>}
              {feature.acceptanceCriteria && (
                <p className="whitespace-pre-wrap text-slate-600">Acceptance criteria: {feature.acceptanceCriteria}</p>
              )}
            </li>
          ))}
        </ul>
      </Section>

      <Section title="Department mapping">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 text-slate-600">
              <tr>
                <th scope="col" className="py-2 pr-3 font-medium">Department</th>
                <th scope="col" className="py-2 pr-3 font-medium">Assignee</th>
                <th scope="col" className="py-2 pr-3 font-medium">Fallback</th>
                <th scope="col" className="py-2 font-medium">Security lead</th>
              </tr>
            </thead>
            <tbody>
              {project.departmentMappings.map((mapping) => (
                <tr key={mapping.category} className="border-b border-slate-100 last:border-0">
                  <td className="py-2 pr-3 text-slate-900">{DEPARTMENT_LABELS[mapping.category]}</td>
                  <td className="py-2 pr-3 text-slate-700">{mapping.defaultZohoUserId}</td>
                  <td className="py-2 pr-3 text-slate-700">{mapping.fallbackZohoUserId}</td>
                  <td className="py-2 text-slate-700">{mapping.securityLeadZohoUserId ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </div>
  );
}
