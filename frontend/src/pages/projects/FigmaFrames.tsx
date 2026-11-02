import { useState } from "react";
import { ApiError } from "../../api/client";
import { exportFigmaFrames, listFigmaFrames, type FigmaFrame } from "../../api/integrations";
import { SecondaryButton } from "../../components/form";

const messageFrom = (error: unknown, fallback: string) =>
  error instanceof ApiError && error.status === 409 ? error.message : fallback;

/** Lets admins and QA leads choose which Figma frames to store for design comparison. */
export function FigmaFrames({ projectId, onExported }: { projectId: string; onExported: () => Promise<void> }) {
  const [frames, setFrames] = useState<FigmaFrame[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);

  async function load() {
    setBusy(true);
    setProblem(null);
    setOutcome(null);
    try {
      setFrames(await listFigmaFrames(projectId));
    } catch (error) {
      setProblem(messageFrom(error, "Could not load frames from Figma. Try again shortly."));
    } finally {
      setBusy(false);
    }
  }

  async function exportSelected() {
    setBusy(true);
    setProblem(null);
    setOutcome(null);
    try {
      const result = await exportFigmaFrames(projectId, [...selected]);
      setOutcome(`Exported ${result.exported.length} of ${selected.size} frames.`);
      if (result.failed.length > 0) {
        setProblem(`These frames failed to export: ${result.failed.map((frame) => frame.name).join(", ")}.`);
      }
      setSelected(new Set(result.failed.map((frame) => frame.id)));
      await onExported();
    } catch (error) {
      setProblem(messageFrom(error, "Could not export from Figma. Try again shortly."));
    } finally {
      setBusy(false);
    }
  }

  function toggle(id: string, checked: boolean) {
    const next = new Set(selected);
    if (checked) {
      next.add(id);
    } else {
      next.delete(id);
    }
    setSelected(next);
  }

  return (
    <div className="mt-3 flex flex-col gap-2">
      {problem && <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{problem}</p>}
      {outcome && <p role="status" className="text-sm text-slate-700">{outcome}</p>}
      {frames === null ? (
        <div>
          <SecondaryButton disabled={busy} onClick={() => void load()}>
            {busy ? "Loading…" : "Choose frames from Figma"}
          </SecondaryButton>
        </div>
      ) : frames.length === 0 ? (
        <p className="text-sm text-slate-600">This Figma file has no top-level frames.</p>
      ) : (
        <>
          <ul className="flex max-h-72 flex-col gap-1 overflow-y-auto text-sm text-slate-800">
            {frames.map((frame) => (
              <li key={frame.id}>
                <label className="flex items-start gap-2 break-words">
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={selected.has(frame.id)}
                    onChange={(event) => toggle(frame.id, event.target.checked)}
                  />
                  <span>
                    {frame.name} <span className="text-slate-500">· {frame.page}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <div>
            <SecondaryButton disabled={busy || selected.size === 0} onClick={() => void exportSelected()}>
              {busy ? "Exporting…" : `Export ${selected.size} selected`}
            </SecondaryButton>
          </div>
        </>
      )}
    </div>
  );
}
