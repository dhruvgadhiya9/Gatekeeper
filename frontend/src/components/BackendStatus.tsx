import { useEffect, useState } from "react";
import { isBackendHealthy } from "../api/health";

type Status = "checking" | "up" | "down";

const presentation: Record<Status, { label: string; dotClass: string }> = {
  checking: { label: "Checking backend…", dotClass: "bg-slate-400" },
  up: { label: "Backend is running", dotClass: "bg-green-600" },
  down: { label: "Backend is not reachable", dotClass: "bg-red-600" },
};

export function BackendStatus() {
  const [status, setStatus] = useState<Status>("checking");

  useEffect(() => {
    let ignore = false;
    isBackendHealthy().then((healthy) => {
      if (!ignore) {
        setStatus(healthy ? "up" : "down");
      }
    });
    return () => {
      ignore = true;
    };
  }, []);

  const { label, dotClass } = presentation[status];
  return (
    <p role="status" className="flex items-center gap-2 text-sm text-slate-700">
      <span aria-hidden="true" className={`size-2 rounded-full ${dotClass}`} />
      {label}
    </p>
  );
}
