import { api, postJson } from "./client";

export const PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const;
export type Priority = (typeof PRIORITIES)[number];
export type RepositoryType = "FRONTEND" | "BACKEND" | "OTHER";
export type DepartmentCategory = "DESIGN" | "FRONTEND" | "BACKEND" | "SECURITY";

export interface FeatureInput {
  title: string;
  description: string;
  acceptanceCriteria: string;
  priority: Priority;
}

export interface RepositoryInput {
  type: RepositoryType;
  repoFullName: string;
  defaultBranch: string;
  installationId?: number | null;
}

/** When editing, leaving out a password or token keeps the stored one. */
export interface ProjectRequest {
  name: string;
  zohoPortalId: string;
  zohoProjectId: string;
  design: { type: "FIGMA"; figmaUrl: string; token?: string } | { type: "UPLOAD" } | null;
  repositories: RepositoryInput[];
  stagingUrl: string;
  testAccounts: { id?: string; role: string; username: string; password?: string; loginUrl: string }[];
  features: FeatureInput[];
  departmentMappings: {
    category: DepartmentCategory;
    defaultZohoUserId: string;
    fallbackZohoUserId: string;
    securityLeadZohoUserId: string | null;
  }[];
  version?: number;
}

export interface ProjectSummary {
  id: string;
  name: string;
  stagingUrl: string;
  createdBy: string;
  createdAt: string;
}

export interface DesignUpload {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
}

export interface ProjectDetail extends ProjectSummary {
  version: number;
  zohoPortalId: string;
  zohoProjectId: string;
  design: {
    type: "FIGMA" | "UPLOAD";
    figmaFileKey: string | null;
    tokenStored: boolean;
    uploads: DesignUpload[];
  } | null;
  repositories: (RepositoryInput & { id: string })[];
  testAccounts: { id: string; role: string; username: string; loginUrl: string }[];
  features: (FeatureInput & { id: string })[];
  departmentMappings: {
    category: DepartmentCategory;
    defaultZohoUserId: string;
    fallbackZohoUserId: string;
    securityLeadZohoUserId: string | null;
  }[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface FeatureCsvResult {
  features: FeatureInput[];
  errors: { row: number; message: string }[];
}

export const listProjects = (page: number) =>
  api<Page<ProjectSummary>>(`/api/projects?page=${page}&size=20`);

export const getProject = (id: string) => api<ProjectDetail>(`/api/projects/${id}`);

export const createProject = (request: ProjectRequest) => postJson<ProjectDetail>("/api/projects", request);

export const updateProject = (id: string, request: ProjectRequest) =>
  api<ProjectDetail>(`/api/projects/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });

export const deleteProject = (id: string) => api<void>(`/api/projects/${id}`, { method: "DELETE" });

function postFiles<T>(path: string, field: string, files: File[]): Promise<T> {
  const body = new FormData();
  files.forEach((file) => body.append(field, file));
  return api<T>(path, { method: "POST", body });
}

export const uploadDesigns = (projectId: string, files: File[]) =>
  postFiles<DesignUpload[]>(`/api/projects/${projectId}/design-uploads`, "files", files);

export const deleteDesignUpload = (projectId: string, uploadId: string) =>
  api<void>(`/api/projects/${projectId}/design-uploads/${uploadId}`, { method: "DELETE" });

export const parseFeatureCsv = (file: File) =>
  postFiles<FeatureCsvResult>("/api/projects/feature-csv", "file", [file]);
