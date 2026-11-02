import { Outlet } from "react-router";
import type { Role } from "../api/users";
import { useAuth } from "./AuthContext";

/**
 * Hides pages from roles that can't use them. This is only for a clean UI: the backend enforces
 * every permission on its own.
 */
export function RequireRole({ roles }: { roles: readonly Role[] }) {
  const { state } = useAuth();

  if (state.status !== "authenticated" || !roles.includes(state.user.role)) {
    return <p role="alert" className="text-slate-700">You don't have access to this page.</p>;
  }
  return <Outlet />;
}
