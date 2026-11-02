const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: FieldError[];

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

let onUnauthorized: () => void = () => {};

/** Lets the auth layer react when a session expires during any API call. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

function readCookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const method = (init.method ?? "GET").toUpperCase();
  if (!SAFE_METHODS.has(method)) {
    const csrfToken = readCookie(CSRF_COOKIE);
    if (csrfToken) {
      headers.set(CSRF_HEADER, decodeURIComponent(csrfToken));
    }
  }

  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });

  if (response.status === 401) {
    onUnauthorized();
  }
  if (!response.ok) {
    const problem: { detail?: string; errors?: FieldError[] } = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem.detail ?? "The request failed.", problem.errors ?? []);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

export function postJson<T>(path: string, body: unknown): Promise<T> {
  return api<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}
