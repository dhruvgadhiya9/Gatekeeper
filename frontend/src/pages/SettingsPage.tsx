import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useSearchParams } from "react-router";
import { ApiError } from "../api/client";
import {
  createUserMapping,
  deleteUserMapping,
  disconnectZoho,
  getZohoFieldOptions,
  getZohoStatus,
  listUserMappings,
  listZohoPortals,
  saveZohoFieldOptions,
  startZohoConnect,
  type UserMapping,
  type ZohoPortal,
  type ZohoStatus,
} from "../api/zoho";
import { SecondaryButton, SelectField, TextField } from "../components/form";

/** Only these fixed messages are shown, so a crafted link can't put its own text on this page. */
const CONNECT_OUTCOMES: Record<string, { message: string; ok: boolean }> = {
  connected: { message: "Zoho is connected.", ok: true },
  denied: { message: "Zoho access wasn't granted.", ok: false },
  invalid_state: { message: "That connection attempt expired or didn't start here. Try again.", ok: false },
  wrong_data_center: { message: "That Zoho account is in another data center. Update ZOHO_DATA_CENTER.", ok: false },
  failed: { message: "Could not connect to Zoho. Try again.", ok: false },
};

const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
const CATEGORIES = ["DESIGN", "FRONTEND", "BACKEND", "SECURITY"];

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-slate-200 bg-white p-4">
      <h2 className="text-lg font-semibold text-slate-900">{title}</h2>
      {children}
    </section>
  );
}

function Problem({ children }: { children: ReactNode }) {
  return <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{children}</p>;
}

function ZohoConnection({ status, onChanged }: { status: ZohoStatus; onChanged: () => Promise<void> }) {
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  async function connect() {
    setProblem(null);
    try {
      const { authorizationUrl } = await startZohoConnect();
      location.assign(authorizationUrl);
    } catch {
      setProblem("Could not start connecting to Zoho.");
    }
  }

  async function disconnect() {
    setProblem(null);
    try {
      await disconnectZoho();
      setConfirming(false);
      await onChanged();
    } catch {
      setProblem("Could not disconnect Zoho.");
    }
  }

  if (!status.configured) {
    return (
      <p className="text-sm text-slate-600">
        Zoho isn't set up yet. Set <code>ZOHO_CLIENT_ID</code> and <code>ZOHO_CLIENT_SECRET</code> on the server, then
        restart it. Until then, projects take typed Zoho IDs.
      </p>
    );
  }
  return (
    <>
      {problem && <Problem>{problem}</Problem>}
      {status.connected ? (
        <p className="text-sm text-slate-700">
          Connected by {status.connectedBy}
          {status.connectedAt ? ` on ${new Date(status.connectedAt).toLocaleDateString()}` : ""}. Data center:{" "}
          {status.dataCenter.toUpperCase()}.
        </p>
      ) : (
        <p className="text-sm text-slate-700">Not connected. Data center: {status.dataCenter.toUpperCase()}.</p>
      )}
      <p className="text-sm text-slate-600">
        {status.attachmentsEnabled
          ? "Connecting asks for WorkDrive access, which Zoho requires for attaching screenshots and reports to bugs."
          : "File attachments are turned off, so Zoho isn't asked for WorkDrive access."}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        {!status.connected && (
          <button
            type="button"
            onClick={() => void connect()}
            className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700"
          >
            Connect Zoho
          </button>
        )}
        {status.connected && !confirming && <SecondaryButton onClick={() => setConfirming(true)}>Disconnect</SecondaryButton>}
        {status.connected && confirming && (
          <>
            <span className="text-sm text-slate-700">Bugs can't be filed while Zoho is disconnected.</span>
            <button
              type="button"
              onClick={() => void disconnect()}
              className="rounded bg-red-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-800"
            >
              Yes, disconnect
            </button>
            <SecondaryButton onClick={() => setConfirming(false)}>Stay connected</SecondaryButton>
          </>
        )}
      </div>
    </>
  );
}

function FieldOptions({ portals }: { portals: ZohoPortal[] }) {
  const [portalId, setPortalId] = useState(portals[0]?.id ?? "");
  const [severity, setSeverity] = useState<Record<string, string>>({});
  const [classification, setClassification] = useState<Record<string, string>>({});
  const [outcome, setOutcome] = useState<{ ok: boolean; message: string } | null>(null);

  useEffect(() => {
    if (!portalId) {
      return;
    }
    let ignore = false;
    setOutcome(null);
    getZohoFieldOptions(portalId)
      .then((options) => {
        if (!ignore) {
          setSeverity(options.severity);
          setClassification(options.classification);
        }
      })
      .catch(() => {
        if (!ignore) {
          setOutcome({ ok: false, message: "Could not load the saved IDs." });
        }
      });
    return () => {
      ignore = true;
    };
  }, [portalId]);

  const withoutBlanks = (values: Record<string, string>) =>
    Object.fromEntries(Object.entries(values).filter(([, value]) => value.trim()).map(([key, value]) => [key, value.trim()]));

  async function save(event: FormEvent) {
    event.preventDefault();
    setOutcome(null);
    try {
      await saveZohoFieldOptions(portalId, {
        severity: withoutBlanks(severity),
        classification: withoutBlanks(classification),
      });
      setOutcome({ ok: true, message: "Saved." });
    } catch (error) {
      const field = error instanceof ApiError ? error.fieldErrors[0] : undefined;
      setOutcome({ ok: false, message: field ? `${field.field} ${field.message}.` : "Could not save the IDs." });
    }
  }

  return (
    <form onSubmit={(event) => void save(event)} noValidate className="flex flex-col gap-3">
      <p className="text-sm text-slate-600">
        Zoho doesn't offer a way to list these IDs, so enter them here. You can see them in an issue's API response. A
        blank value leaves that field unset on the bug, and the value is written in its description.
      </p>
      <SelectField
        label="Portal"
        value={portalId}
        options={portals.map((portal) => ({ value: portal.id, label: portal.name }))}
        onChange={setPortalId}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-slate-700">Severity IDs</legend>
          {SEVERITIES.map((key) => (
            <TextField
              key={key}
              label={key}
              value={severity[key] ?? ""}
              onChange={(value) => setSeverity((current) => ({ ...current, [key]: value }))}
            />
          ))}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium text-slate-700">Classification IDs</legend>
          {CATEGORIES.map((key) => (
            <TextField
              key={key}
              label={key}
              value={classification[key] ?? ""}
              onChange={(value) => setClassification((current) => ({ ...current, [key]: value }))}
            />
          ))}
        </fieldset>
      </div>
      {outcome && (outcome.ok ? <p role="status" className="text-sm text-slate-700">{outcome.message}</p> : <Problem>{outcome.message}</Problem>)}
      <div>
        <button type="submit" className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700">
          Save IDs
        </button>
      </div>
    </form>
  );
}

function UserMappings() {
  const [mappings, setMappings] = useState<UserMapping[] | null>(null);
  const [githubUsername, setGithubUsername] = useState("");
  const [zohoUserId, setZohoUserId] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  async function reload() {
    setMappings(await listUserMappings());
  }

  useEffect(() => {
    reload().catch(() => setProblem("Could not load the user mappings."));
  }, []);

  async function add(event: FormEvent) {
    event.preventDefault();
    setProblem(null);
    try {
      await createUserMapping(githubUsername.trim(), zohoUserId.trim());
      setGithubUsername("");
      setZohoUserId("");
      await reload();
    } catch (error) {
      const field = error instanceof ApiError ? error.fieldErrors[0] : undefined;
      setProblem(field ? `${field.field} ${field.message}.` : error instanceof ApiError ? error.message : "Could not add the mapping.");
    }
  }

  async function remove(id: string) {
    setProblem(null);
    try {
      await deleteUserMapping(id);
      await reload();
    } catch {
      setProblem("Could not remove the mapping.");
    }
  }

  return (
    <>
      <p className="text-sm text-slate-600">
        Bugs are assigned using CODEOWNERS and git blame, which name GitHub users. This says who they are in Zoho. Use the
        person's Zoho <code>zpuid</code>.
      </p>
      {problem && <Problem>{problem}</Problem>}
      {mappings === null && !problem && <p role="status" className="text-sm text-slate-600">Loading…</p>}
      {mappings?.length === 0 && <p className="text-sm text-slate-600">No mappings yet.</p>}
      {mappings && mappings.length > 0 && (
        <ul className="flex flex-col gap-1 text-sm text-slate-800">
          {mappings.map((mapping) => (
            <li key={mapping.id} className="flex items-center justify-between gap-2">
              <span className="break-all">
                <span className="font-medium">{mapping.githubUsername}</span> → {mapping.zohoUserId}
              </span>
              <SecondaryButton label={`Remove ${mapping.githubUsername}`} onClick={() => void remove(mapping.id)}>
                Remove
              </SecondaryButton>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={(event) => void add(event)} noValidate className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
        <TextField label="GitHub username" value={githubUsername} onChange={setGithubUsername} />
        <TextField label="Zoho user ID" value={zohoUserId} onChange={setZohoUserId} />
        <button
          type="submit"
          disabled={!githubUsername.trim() || !zohoUserId.trim()}
          className="rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
        >
          Add mapping
        </button>
      </form>
    </>
  );
}

export function SettingsPage() {
  const [params] = useSearchParams();
  const outcome = CONNECT_OUTCOMES[params.get("zoho") ?? ""];
  const [status, setStatus] = useState<ZohoStatus | null>(null);
  const [portals, setPortals] = useState<ZohoPortal[] | null>(null);
  const [failed, setFailed] = useState(false);

  async function load() {
    const loaded = await getZohoStatus();
    setStatus(loaded);
    setPortals(loaded.connected ? await listZohoPortals() : null);
  }

  useEffect(() => {
    load().catch(() => setFailed(true));
  }, []);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold text-slate-900">Settings</h1>
      {outcome &&
        (outcome.ok ? <p role="status" className="rounded bg-green-50 p-3 text-sm text-green-900">{outcome.message}</p> : <Problem>{outcome.message}</Problem>)}
      <Section title="Zoho connection">
        {failed && <Problem>Could not load the Zoho connection.</Problem>}
        {!status && !failed && <p role="status" className="text-sm text-slate-600">Loading…</p>}
        {status && <ZohoConnection status={status} onChanged={load} />}
      </Section>
      {portals && portals.length > 0 && (
        <Section title="Zoho severity and classification">
          <FieldOptions portals={portals} />
        </Section>
      )}
      <Section title="GitHub to Zoho users">
        <UserMappings />
      </Section>
    </div>
  );
}
