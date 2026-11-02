import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { ApiError, setUnauthorizedHandler } from "../api/client";
import { getCurrentUser, logout, type User } from "../api/users";

export type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "unavailable" }
  | { status: "authenticated"; user: User };

interface AuthContextValue {
  state: AuthState;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  const refresh = useCallback(async () => {
    try {
      setState({ status: "authenticated", user: await getCurrentUser() });
    } catch (error) {
      const signedOut = error instanceof ApiError && error.status === 401;
      setState({ status: signedOut ? "anonymous" : "unavailable" });
    }
  }, []);

  const signOut = useCallback(async () => {
    await logout();
    setState({ status: "anonymous" });
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => setState({ status: "anonymous" }));
    void refresh();
  }, [refresh]);

  const value = useMemo(() => ({ state, refresh, signOut }), [state, refresh, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
