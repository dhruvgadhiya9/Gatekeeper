import { useEffect, useState } from "react";
import { Link } from "react-router";
import { listProjects, type Page, type ProjectSummary } from "../../api/projects";
import { useAuth } from "../../auth/AuthContext";
import { SecondaryButton } from "../../components/form";

export function ProjectListPage() {
  const { state } = useAuth();
  const canCreate = state.status === "authenticated" && state.user.role !== "VIEWER";
  const [pageNumber, setPageNumber] = useState(0);
  const [page, setPage] = useState<Page<ProjectSummary> | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let ignore = false;
    setFailed(false);
    listProjects(pageNumber)
      .then((loaded) => {
        if (!ignore) {
          setPage(loaded);
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
  }, [pageNumber]);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold text-slate-900">Projects</h1>
        {canCreate && (
          <Link to="/projects/new" className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700">
            New project
          </Link>
        )}
      </div>

      {failed && <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">Could not load projects.</p>}
      {!page && !failed && <p role="status" className="text-slate-600">Loading…</p>}
      {page?.totalItems === 0 && (
        <p className="text-slate-600">
          No projects yet.{canCreate ? " Create one to start testing." : " A QA lead or admin can create one."}
        </p>
      )}
      {page && page.items.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 text-slate-600">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Name</th>
                <th scope="col" className="px-3 py-2 font-medium">Staging URL</th>
                <th scope="col" className="px-3 py-2 font-medium">Created by</th>
                <th scope="col" className="px-3 py-2 font-medium">Created</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((project) => (
                <tr key={project.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-3 py-2">
                    <Link to={`/projects/${project.id}`} className="font-medium text-slate-900 underline">
                      {project.name}
                    </Link>
                  </td>
                  <td className="px-3 py-2 break-all text-slate-700">{project.stagingUrl}</td>
                  <td className="px-3 py-2 text-slate-700">{project.createdBy}</td>
                  <td className="px-3 py-2 whitespace-nowrap text-slate-700">
                    {new Date(project.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {page && page.totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center gap-3 text-sm text-slate-600">
          <SecondaryButton disabled={page.page === 0} onClick={() => setPageNumber(page.page - 1)}>
            Previous
          </SecondaryButton>
          <span>
            Page {page.page + 1} of {page.totalPages}
          </span>
          <SecondaryButton disabled={page.page + 1 >= page.totalPages} onClick={() => setPageNumber(page.page + 1)}>
            Next
          </SecondaryButton>
        </nav>
      )}
    </div>
  );
}
