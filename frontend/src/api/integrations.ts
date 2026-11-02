import { api, postJson } from "./client";
import type { DesignUpload } from "./projects";

export interface GitHubInstallation {
  id: number;
  account: string;
  accountType: string;
}

export interface GitHubRepository {
  fullName: string;
  defaultBranch: string;
  isPrivate: boolean;
}

export interface FigmaFrame {
  id: string;
  name: string;
  page: string;
}

export interface FigmaExportResult {
  exported: DesignUpload[];
  failed: { id: string; name: string }[];
}

export const getGitHubStatus = () => api<{ configured: boolean }>("/api/integrations/github/status");

export const listGitHubInstallations = () => api<GitHubInstallation[]>("/api/integrations/github/installations");

export const listGitHubRepositories = (installationId: number) =>
  api<GitHubRepository[]>(`/api/integrations/github/installations/${installationId}/repositories`);

export const listFigmaFrames = (projectId: string) => api<FigmaFrame[]>(`/api/projects/${projectId}/figma/frames`);

export const exportFigmaFrames = (projectId: string, frameIds: string[]) =>
  postJson<FigmaExportResult>(`/api/projects/${projectId}/figma/exports`, { frameIds });
