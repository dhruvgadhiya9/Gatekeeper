import { Navigate, useSearchParams } from "react-router";
import { useAuth } from "../auth/AuthContext";

/** Only these fixed messages are shown, so a crafted link can't put its own text on this page. */
const ERROR_MESSAGES: Record<string, string> = {
  account_disabled: "Your account has been disabled. Ask an administrator for help.",
  domain_not_allowed: "This email domain isn't allowed to use Gatekeeper.",
  missing_email: "Your account has no email address. Ask an administrator for help.",
};
const DEFAULT_ERROR = "Sign-in failed. Please try again.";

export function LoginPage() {
  const { state } = useAuth();
  const [params] = useSearchParams();
  const errorCode = params.get("error");

  if (state.status === "authenticated") {
    return <Navigate to="/" replace />;
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <div className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-6">
        <h1 className="text-2xl font-semibold text-slate-900">Gatekeeper</h1>
        <p className="mt-1 text-sm text-slate-600">Sign in with your company account.</p>
        {errorCode !== null && (
          <p role="alert" className="mt-4 rounded bg-red-50 p-3 text-sm text-red-800">
            {ERROR_MESSAGES[errorCode] ?? DEFAULT_ERROR}
          </p>
        )}
        {/* A full page navigation, because the backend redirects to the identity provider. */}
        <a
          href="/oauth2/authorization/oidc"
          className="mt-5 block rounded bg-slate-900 px-4 py-2 text-center text-sm font-medium text-white hover:bg-slate-700"
        >
          Sign in
        </a>
      </div>
    </main>
  );
}
