import { useEffect, useState } from "react";
import { ApiError } from "../api/client";
import { listUsers, ROLES, updateUser, type Role, type User } from "../api/users";
import { useAuth } from "../auth/AuthContext";

export function UsersPage() {
  const { state } = useAuth();
  const currentUserId = state.status === "authenticated" ? state.user.id : null;
  const [users, setUsers] = useState<User[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    listUsers()
      .then((loaded) => {
        if (!ignore) {
          setUsers(loaded);
        }
      })
      .catch(() => {
        if (!ignore) {
          setError("Could not load users.");
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  async function save(user: User, changes: { role: Role; enabled: boolean }) {
    setError(null);
    try {
      const updated = await updateUser(user.id, changes);
      setUsers((current) => current?.map((item) => (item.id === updated.id ? updated : item)) ?? null);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Could not save the change.");
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold text-slate-900">Users</h1>
      <p className="text-sm text-slate-600">
        People appear here after their first sign-in, starting as Viewer.
      </p>
      {error && <p role="alert" className="rounded bg-red-50 p-3 text-sm text-red-800">{error}</p>}
      {users === null && !error && <p role="status" className="text-slate-600">Loading…</p>}
      {users !== null && (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 text-slate-600">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">Name</th>
                <th scope="col" className="px-3 py-2 font-medium">Email</th>
                <th scope="col" className="px-3 py-2 font-medium">Role</th>
                <th scope="col" className="px-3 py-2 font-medium">Active</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => {
                const isSelf = user.id === currentUserId;
                return (
                  <tr key={user.id} className="border-b border-slate-100 last:border-0">
                    <td className="px-3 py-2 text-slate-900">
                      {user.displayName ?? "—"}
                      {isSelf && <span className="ml-1 text-slate-500">(you)</span>}
                    </td>
                    <td className="px-3 py-2 text-slate-700">{user.email}</td>
                    <td className="px-3 py-2">
                      <select
                        aria-label={`Role for ${user.email}`}
                        value={user.role}
                        disabled={isSelf}
                        onChange={(event) =>
                          void save(user, { role: event.target.value as Role, enabled: user.enabled })
                        }
                        className="rounded border border-slate-300 bg-white px-2 py-1 disabled:bg-slate-100"
                      >
                        {ROLES.map((role) => (
                          <option key={role} value={role}>{role}</option>
                        ))}
                      </select>
                    </td>
                    <td className="px-3 py-2">
                      <input
                        type="checkbox"
                        aria-label={`Active status for ${user.email}`}
                        checked={user.enabled}
                        disabled={isSelf}
                        onChange={(event) =>
                          void save(user, { role: user.role, enabled: event.target.checked })
                        }
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
