import { api, postJson } from "./client";

export interface ZohoStatus {
  configured: boolean;
  connected: boolean;
  connectedBy: string | null;
  connectedAt: string | null;
  dataCenter: string;
  attachmentsEnabled: boolean;
}

export interface ZohoPortal {
  id: string;
  name: string;
}

export interface ZohoProject {
  id: string;
  name: string;
}

export interface ZohoUser {
  id: string;
  zpuid: string;
  name: string;
  email: string | null;
}

export interface ZohoFieldOptions {
  severity: Record<string, string>;
  classification: Record<string, string>;
}

export interface UserMapping {
  id: string;
  githubUsername: string;
  zohoUserId: string;
}

const BASE = "/api/integrations/zoho";

export const getZohoStatus = () => api<ZohoStatus>(`${BASE}/status`);

export const startZohoConnect = () => api<{ authorizationUrl: string }>(`${BASE}/connect`, { method: "POST" });

export const disconnectZoho = () => api<void>(`${BASE}/connection`, { method: "DELETE" });

export const listZohoPortals = () => api<ZohoPortal[]>(`${BASE}/portals`);

export const listZohoProjects = (portalId: string) => api<ZohoProject[]>(`${BASE}/portals/${portalId}/projects`);

export const listZohoUsers = (portalId: string, projectId: string) =>
  api<ZohoUser[]>(`${BASE}/portals/${portalId}/projects/${projectId}/users`);

export const getZohoFieldOptions = (portalId: string) =>
  api<ZohoFieldOptions>(`${BASE}/portals/${portalId}/field-options`);

export const saveZohoFieldOptions = (portalId: string, options: ZohoFieldOptions) =>
  api<ZohoFieldOptions>(`${BASE}/portals/${portalId}/field-options`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(options),
  });

export const listUserMappings = () => api<UserMapping[]>("/api/user-mappings");

export const createUserMapping = (githubUsername: string, zohoUserId: string) =>
  postJson<UserMapping>("/api/user-mappings", { githubUsername, zohoUserId });

export const deleteUserMapping = (id: string) => api<void>(`/api/user-mappings/${id}`, { method: "DELETE" });
