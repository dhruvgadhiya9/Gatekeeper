import { useState } from "react";
import { Navigate, NavLink, Outlet } from "react-router";
import type { Role, User } from "../api/users";
import { useAuth } from "./AuthContext";

const ROLE_LABELS: Record<Role, string> = {
  ADMIN: "Admin",
  QA_LEAD: "QA lead",
  VIEWER: "Viewer",
};

function navLinkClass({ isActive }: { isActive: boolean }) {
  return `rounded px-2 py-1 text-sm ${isActive ? "bg-slate-200 font-medium text-slate-900" : "text-slate-600 hover:text-slate-900"}`;
}

function Header({ user }: { user: User }) {
  const { signOut } = useAuth();
  const [signOutFailed, setSignOutFailed] = useState(false);

  async function handleSignOut() {
    setSignOutFailed(false);
    try {
      await signOut();
    } catch {
      setSignOutFailed(true);
    }
  }

  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
        <span className="font-semibold text-slate-900">Gatekeeper</span>
        <nav aria-label="Main" className="flex gap-1">
          <NavLink to="/" end className={navLinkClass}>
            Home
          </NavLink>
          <NavLink to="/projects" className={navLinkClass}>
            Projects
          </NavLink>
          {user.role === "ADMIN" && (
            <>
              <NavLink to="/users" className={navLinkClass}>
                Users
              </NavLink>
              <NavLink to="/settings" className={navLinkClass}>
                Settings
              </NavLink>
            </>
          )}
        </nav>
        <div className="ml-auto flex flex-wrap items-center gap-3 text-sm text-slate-600">
          {signOutFailed && <span role="alert" className="text-red-700">Could not sign out.</span>}
          <span className="break-all">
            {user.displayName ?? user.email} · {ROLE_LABELS[user.role]}
          </span>
          <button
            type="button"
            onClick={() => void handleSignOut()}
            className="rounded border border-slate-300 px-2 py-1 text-slate-700 hover:bg-slate-100"
          >
            Sign out
          </button>
        </div>
      </div>
    </header>
  );
}

/** Wraps every signed-in page. Visitors who are not signed in go to the login page. */
export function ProtectedLayout() {
  const { state, refresh } = useAuth();

  if (state.status === "loading") {
    return <p role="status" className="p-6 text-slate-600">Loading…</p>;
  }
  if (state.status === "anonymous") {
    return <Navigate to="/login" replace />;
  }
  if (state.status === "unavailable") {
    return (
      <div role="alert" className="mx-auto max-w-md p-6 text-slate-700">
        <p>Gatekeeper can't reach the server right now.</p>
        <button
          type="button"
          onClick={() => void refresh()}
          className="mt-3 rounded border border-slate-300 px-3 py-1 hover:bg-slate-100"
        >
          Try again
        </button>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <Header user={state.user} />
      <main className="mx-auto max-w-5xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
