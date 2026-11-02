import { vi } from "vitest";
import type { User } from "../api/users";

export const admin: User = {
  id: "admin-id",
  email: "admin@corp.test",
  displayName: "Ada Admin",
  role: "ADMIN",
  enabled: true,
};

export const viewer: User = {
  id: "viewer-id",
  email: "viewer@corp.test",
  displayName: "Vic Viewer",
  role: "VIEWER",
  enabled: true,
};

type Handler = (init: RequestInit) => { status: number; body?: unknown };

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}

/** Replaces fetch with handlers keyed by "METHOD /path". Unlisted requests return 404. */
export function fakeBackend(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (path: string, init: RequestInit = {}) => {
    const handler = routes[`${init.method ?? "GET"} ${path}`];
    if (!handler) {
      return json(404, { detail: "Not found" });
    }
    const { status, body } = handler(init);
    return json(status, body);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

export const signedInAs = (user: User): Handler => () => ({ status: 200, body: user });
export const signedOut: Handler = () => ({ status: 401, body: { detail: "Sign in to continue." } });
