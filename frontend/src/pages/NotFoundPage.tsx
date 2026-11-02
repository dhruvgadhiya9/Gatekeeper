import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <div className="flex flex-col gap-2">
      <h1 className="text-2xl font-semibold text-slate-900">Page not found</h1>
      <Link to="/" className="text-sm text-slate-700 underline">
        Back to home
      </Link>
    </div>
  );
}
