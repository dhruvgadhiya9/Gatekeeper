import { BackendStatus } from "../components/BackendStatus";

export function HomePage() {
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold text-slate-900">Home</h1>
      <p className="max-w-2xl text-slate-600">
        Register a project, let AI agents test it, and get confirmed bugs filed in Zoho Projects.
      </p>
      <BackendStatus />
    </div>
  );
}
