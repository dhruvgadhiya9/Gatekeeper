import { api } from "./client";

export const ROLES = ["ADMIN", "QA_LEAD", "VIEWER"] as const;
export type Role = (typeof ROLES)[number];

export interface User {
  id: string;
  email: string;
  displayName: string | null;
  role: Role;
  enabled: boolean;
}

export const getCurrentUser = () => api<User>("/api/me");

export const listUsers = () => api<User[]>("/api/users");

export const updateUser = (id: string, changes: Pick<User, "role" | "enabled">) =>
  api<User>(`/api/users/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(changes),
  });

export const logout = () => api<void>("/api/auth/logout", { method: "POST" });
