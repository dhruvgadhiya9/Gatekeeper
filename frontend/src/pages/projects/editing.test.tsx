import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProjectDetail } from "../../api/projects";
import App from "../../App";
import { AuthProvider } from "../../auth/AuthContext";
import { admin, fakeBackend, signedInAs, viewer } from "../../test/fakeBackend";

const mapping = (category: "DESIGN" | "FRONTEND" | "BACKEND") => ({
  category,
  defaultZohoUserId: "u1",
  fallbackZohoUserId: "u2",
  securityLeadZohoUserId: null,
});

const project: ProjectDetail = {
  id: "p1",
  version: 7,
  name: "Shop",
  stagingUrl: "https://staging.shop.test",
  createdBy: "admin@corp.test",
  createdAt: "2026-01-02T00:00:00Z",
  zohoPortalId: "portal",
  zohoProjectId: "project",
  design: { type: "FIGMA", figmaFileKey: "AbC123", tokenStored: true, uploads: [] },
  repositories: [{ id: "r1", type: "BACKEND", repoFullName: "org/api", defaultBranch: "main", installationId: null }],
  testAccounts: [{ id: "a1", role: "admin", username: "qa@shop.test", loginUrl: "https://staging.shop.test/login" }],
  features: [{ id: "f1", title: "Checkout", description: "Pay", acceptanceCriteria: "Works", priority: "HIGH" }],
  departmentMappings: [
    mapping("DESIGN"),
    mapping("FRONTEND"),
    mapping("BACKEND"),
    { category: "SECURITY", defaultZohoUserId: "u1", fallbackZohoUserId: "u2", securityLeadZohoUserId: "u3" },
  ],
};

const uploadProject: ProjectDetail = {
  ...project,
  design: {
    type: "UPLOAD",
    figmaFileKey: null,
    tokenStored: false,
    uploads: [{ id: "i1", fileName: "home.png", contentType: "image/png", sizeBytes: 1 }],
  },
};

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const clickNext = () => fireEvent.click(screen.getByRole("button", { name: "Next" }));

async function expectStep(title: string) {
  expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
}

async function nextUntil(title: string) {
  const steps = ["Project", "Design", "Repositories", "Staging", "Test accounts", "Features", "Departments"];
  const current = steps.indexOf(screen.getByRole("heading", { level: 1 }).textContent ?? "");
  for (const step of steps.slice(current + 1, steps.indexOf(title) + 1)) {
    clickNext();
    await expectStep(step);
  }
}

describe("editing a project", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("starts from saved values and leaves stored credentials out of the request", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "PUT /api/projects/p1": () => ({ status: 200, body: { ...project, name: "Renamed" } }),
    });
    renderAt("/projects/p1/edit");

    await expectStep("Project");
    expect(screen.getByLabelText("Project name")).toHaveValue("Shop");
    fireEvent.change(screen.getByLabelText("Project name"), { target: { value: "Renamed" } });
    await nextUntil("Design");
    expect(screen.getByLabelText("Figma access token")).toHaveValue("");
    expect(screen.getByText("Leave empty to keep the stored token.")).toBeInTheDocument();
    await nextUntil("Test accounts");
    expect(screen.getByLabelText("Password")).toHaveValue("");
    await nextUntil("Departments");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await screen.findByRole("link", { name: "Edit" });
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
    const body = JSON.parse(put?.[1]?.body as string);
    expect(body.version).toBe(7);
    expect(body.name).toBe("Renamed");
    expect(body.design).toEqual({ type: "FIGMA", figmaUrl: "https://www.figma.com/design/AbC123" });
    expect(body.testAccounts).toEqual([
      { id: "a1", role: "admin", username: "qa@shop.test", loginUrl: "https://staging.shop.test/login" },
    ]);
  });

  it("sends a new password only when one is typed", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "PUT /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/p1/edit");
    await expectStep("Project");
    await nextUntil("Test accounts");
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "new-secret" } });
    await nextUntil("Departments");
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await screen.findByRole("heading", { name: "Shop" });
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(JSON.parse(put?.[1]?.body as string).testAccounts[0].password).toBe("new-secret");
  });

  it("explains when someone else changed the project first", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "PUT /api/projects/p1": () => ({
        status: 409,
        body: { detail: "Someone else changed this project. Reload it and try again." },
      }),
    });
    renderAt("/projects/p1/edit");
    await expectStep("Project");
    await nextUntil("Departments");

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Someone else changed this project");
  });

  it("warns that changing the design type removes current design data", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin), "GET /api/projects/p1": () => ({ status: 200, body: project }) });
    renderAt("/projects/p1/edit");
    await expectStep("Project");
    await nextUntil("Design");

    fireEvent.change(screen.getByLabelText("Design source"), { target: { value: "UPLOAD" } });

    expect(screen.getByText(/removes the project's current design data/)).toBeInTheDocument();
    clickNext();
    expect(await screen.findByText("Choose at least one image.")).toBeInTheDocument();
  });

  it("doesn't require new images when the project already has some", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin), "GET /api/projects/p1": () => ({ status: 200, body: uploadProject }) });
    renderAt("/projects/p1/edit");
    await expectStep("Project");

    await nextUntil("Repositories");
  });

  it("hides Edit and Delete from viewers", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer), "GET /api/projects/p1": () => ({ status: 200, body: project }) });
    renderAt("/projects/p1");

    await screen.findByRole("heading", { name: "Shop" });
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
  });

  it("lets QA leads edit but not delete", async () => {
    fakeBackend({
      "GET /api/me": signedInAs({ ...admin, role: "QA_LEAD" }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/p1");

    expect(await screen.findByRole("link", { name: "Edit" })).toHaveAttribute("href", "/projects/p1/edit");
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
  });

  it("asks before deleting, and can be cancelled", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep project" }));

    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
  });

  it("deletes and returns to the list", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "DELETE /api/projects/p1": () => ({ status: 204 }),
      "GET /api/projects?page=0&size=20": () => ({
        status: 200,
        body: { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 },
      }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    expect(await screen.findByRole("heading", { name: "Projects" })).toBeInTheDocument();
  });

  it("shows why a project couldn't be deleted", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "DELETE /api/projects/p1": () => ({
        status: 409,
        body: { detail: "This project has test runs, so it can't be deleted." },
      }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Yes, delete" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("has test runs");
  });

  it("removes a single image", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: uploadProject }),
      "DELETE /api/projects/p1/design-uploads/i1": () => ({ status: 204 }),
    });
    renderAt("/projects/p1");

    const item = (await screen.findByText("home.png")).closest("li") as HTMLElement;
    fireEvent.click(within(item).getByRole("button", { name: "Remove home.png" }));

    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.filter(([path]) => path === "/api/projects/p1").length).toBe(2),
    );
  });
});
