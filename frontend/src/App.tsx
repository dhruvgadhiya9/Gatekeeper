import { BackendStatus } from "./components/BackendStatus";

export default function App() {
  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-4 px-4">
      <h1 className="text-3xl font-semibold text-slate-900">Gatekeeper</h1>
      <p className="text-slate-600">
        Internal QA platform. Register a project, let AI agents test it, and get confirmed bugs
        filed in Zoho Projects.
      </p>
      <BackendStatus />
    </main>
  );
}
