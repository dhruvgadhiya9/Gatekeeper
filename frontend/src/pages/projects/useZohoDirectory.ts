import { useEffect, useState } from "react";
import {
  getZohoStatus,
  listZohoPortals,
  listZohoProjects,
  listZohoUsers,
  type ZohoPortal,
  type ZohoProject,
  type ZohoUser,
} from "../../api/zoho";

/**
 * Loads what the Zoho dropdowns offer. "typed" means Zoho isn't connected, or its status can't be
 * read, so IDs are entered by hand and the server decides what's acceptable.
 */
export type ZohoMode = "checking" | "typed" | "connected" | "failed";

export function useZohoDirectory(portalId: string, projectId: string) {
  const [mode, setMode] = useState<ZohoMode>("checking");
  const [portals, setPortals] = useState<ZohoPortal[]>([]);
  const [projects, setProjects] = useState<ZohoProject[]>([]);
  const [users, setUsers] = useState<ZohoUser[]>([]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let ignore = false;
    async function load() {
      let connected: boolean;
      try {
        connected = (await getZohoStatus()).connected;
      } catch {
        connected = false;
      }
      if (ignore) {
        return;
      }
      if (!connected) {
        setMode("typed");
        return;
      }
      try {
        const loaded = await listZohoPortals();
        if (!ignore) {
          setPortals(loaded);
          setMode("connected");
        }
      } catch {
        if (!ignore) {
          setMode("failed");
        }
      }
    }
    void load();
    return () => {
      ignore = true;
    };
  }, []);

  useEffect(() => {
    if (mode !== "connected" || !portalId) {
      setProjects([]);
      return;
    }
    let ignore = false;
    setFailed(false);
    listZohoProjects(portalId)
      .then((loaded) => {
        if (!ignore) {
          setProjects(loaded);
        }
      })
      .catch(() => {
        if (!ignore) {
          setFailed(true);
        }
      });
    return () => {
      ignore = true;
    };
  }, [mode, portalId]);

  useEffect(() => {
    if (mode !== "connected" || !portalId || !projectId) {
      setUsers([]);
      return;
    }
    let ignore = false;
    setFailed(false);
    listZohoUsers(portalId, projectId)
      .then((loaded) => {
        if (!ignore) {
          setUsers(loaded);
        }
      })
      .catch(() => {
        if (!ignore) {
          setFailed(true);
        }
      });
    return () => {
      ignore = true;
    };
  }, [mode, portalId, projectId]);

  return { mode, portals, projects, users, failed };
}

/** Options with a leading prompt, keeping a saved value visible even if it's not in the list. */
export function selectOptions(
  items: { value: string; label: string }[],
  current: string,
  prompt: string,
): { value: string; label: string }[] {
  const known = items.some((item) => item.value === current);
  return [
    { value: "", label: prompt },
    ...(current && !known ? [{ value: current, label: current }] : []),
    ...items,
  ];
}
