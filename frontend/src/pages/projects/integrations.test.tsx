import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProjectDetail } from "../../api/projects";
import App from "../../App";
import { AuthProvider } from "../../auth/AuthContext";
import { admin, fakeBackend, signedInAs } from "../../test/fakeBackend";

const project: ProjectDetail = {
  id: "p1",
  version: 1,
  name: "Shop",
  stagingUrl: "https://s.test",
  createdBy: "a",
  createdAt: "2026-01-02T00:00:00Z",
  zohoPortalId: "portal",
  zohoProjectId: "project",
  design: { type: "FIGMA", figmaFileKey: "AbC", tokenStored: true, uploads: [] },
  repositories: [{ id: "r1", type: "BACKEND", repoFullName: "acme/api", defaultBranch: "dev", installationId: 7 }],
  testAccounts: [],
  features: [],
  departmentMappings: [],
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

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const clickNext = () => fireEvent.click(screen.getByRole("button", { name: "Next" }));

async function goToRepositoriesStep() {
  await screen.findByRole("heading", { name: "Project" });
  type("Project name", "Shop");
  type("Zoho portal ID", "p");
  type("Zoho project ID", "p");
  clickNext();
  await screen.findByRole("heading", { name: "Design" });
  clickNext();
  await screen.findByRole("heading", { name: "Repositories" });
}

const connected = {
  "GET /api/integrations/github/status": () => ({ status: 200, body: { configured: true } }),
  "GET /api/integrations/github/installations": () => ({
    status: 200,
    body: [{ id: 7, account: "acme", accountType: "Organization" }],
  }),
  "GET /api/integrations/github/installations/7/repositories": () => ({
    status: 200,
    body: [
      { fullName: "acme/web", defaultBranch: "trunk", isPrivate: true },
      { fullName: "acme/api", defaultBranch: "dev", isPrivate: true },
    ],
  }),
};

describe("GitHub repository picker", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("falls back to typed repositories until the App is connected", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/integrations/github/status": () => ({ status: 200, body: { configured: false } }),
    });
    renderAt("/projects/new");
    await goToRepositoriesStep();

    expect(await screen.findByText(/GitHub App isn't connected yet/)).toBeInTheDocument();
    expect(screen.getAllByPlaceholderText("owner/repository")).toHaveLength(2);
    expect(screen.queryByLabelText("GitHub account")).not.toBeInTheDocument();
  });

  it("offers an account then its repositories, filling in the default branch", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin), ...connected });
    renderAt("/projects/new");
    await goToRepositoriesStep();

    const [account] = await screen.findAllByLabelText("GitHub account");
    fireEvent.change(account!, { target: { value: "7" } });
    await screen.findByRole("option", { name: "acme/web" });
    const [repository] = screen.getAllByLabelText("Repository");
    fireEvent.change(repository!, { target: { value: "acme/web" } });

    await waitFor(() => expect(screen.getAllByLabelText("Default branch")[0]).toHaveValue("trunk"));
    expect(repository).toHaveValue("acme/web");
  });

  it("sends the installation with the repository", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      ...connected,
      "POST /api/projects": () => ({ status: 201, body: project }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/new");
    await goToRepositoriesStep();
    fireEvent.click(screen.getByRole("button", { name: "Remove repository 2" }));
    fireEvent.change(await screen.findByLabelText("GitHub account"), { target: { value: "7" } });
    await screen.findByRole("option", { name: "acme/web" });
    fireEvent.change(screen.getByLabelText("Repository"), { target: { value: "acme/web" } });
    clickNext();
    await screen.findByRole("heading", { name: "Staging" });
    type("Staging URL", "https://s.test");
    clickNext();
    await screen.findByRole("heading", { name: "Test accounts" });
    clickNext();
    await screen.findByRole("heading", { name: "Features" });
    type("Title", "F");
    clickNext();
    await screen.findByRole("heading", { name: "Departments" });
    for (const name of ["Design", "Frontend", "Backend", "Security"]) {
      type(`${name} assignee (Zoho user ID)`, "u1");
      type(`${name} fallback (Zoho user ID)`, "u2");
    }
    type("Security lead (Zoho user ID)", "u3");
    fireEvent.click(screen.getByRole("button", { name: "Create project" }));

    await screen.findByRole("heading", { name: "Shop" });
    const post = fetchMock.mock.calls.find(([path, init]) => path === "/api/projects" && init?.method === "POST");
    expect(JSON.parse(post?.[1]?.body as string).repositories).toEqual([
      { type: "FRONTEND", repoFullName: "acme/web", defaultBranch: "trunk", installationId: 7 },
    ]);
  });

  it("keeps the saved choice when editing", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      ...connected,
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/p1/edit");
    await screen.findByRole("heading", { name: "Project" });
    clickNext();
    await screen.findByRole("heading", { name: "Design" });
    clickNext();
    await screen.findByRole("heading", { name: "Repositories" });

    expect(await screen.findByLabelText("GitHub account")).toHaveValue("7");
    expect(screen.getByLabelText("Repository")).toHaveValue("acme/api");
    expect(screen.getByLabelText("Default branch")).toHaveValue("dev");
  });

  it("explains when the App has no installations", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/integrations/github/status": () => ({ status: 200, body: { configured: true } }),
      "GET /api/integrations/github/installations": () => ({ status: 200, body: [] }),
    });
    renderAt("/projects/new");
    await goToRepositoriesStep();

    expect(await screen.findByRole("alert")).toHaveTextContent("isn't installed on any account");
  });

  it("explains when GitHub can't be reached", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/integrations/github/status": () => ({ status: 200, body: { configured: true } }),
      "GET /api/integrations/github/installations": () => ({ status: 502 }),
    });
    renderAt("/projects/new");
    await goToRepositoriesStep();

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load your GitHub accounts");
  });
});

describe("Figma frames", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const frames = [
    { id: "1:2", name: "Home", page: "Web" },
    { id: "1:3", name: "Cart", page: "Web" },
  ];

  it("exports chosen frames and refreshes the project", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "GET /api/projects/p1/figma/frames": () => ({ status: 200, body: frames }),
      "POST /api/projects/p1/figma/exports": () => ({
        status: 200,
        body: { exported: [{ id: "u1", fileName: "Web - Home.png", contentType: "image/png", sizeBytes: 1 }], failed: [] },
      }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Choose frames from Figma" }));
    fireEvent.click(await screen.findByRole("checkbox", { name: /Home/ }));
    fireEvent.click(screen.getByRole("button", { name: "Export 1 selected" }));

    expect(await screen.findByText("Exported 1 of 1 frames.")).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([path]) => path === "/api/projects/p1/figma/exports");
    expect(JSON.parse(post?.[1]?.body as string)).toEqual({ frameIds: ["1:2"] });
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/projects/p1")).toHaveLength(2);
  });

  it("names the frames that failed and keeps them selected for another try", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "GET /api/projects/p1/figma/frames": () => ({ status: 200, body: frames }),
      "POST /api/projects/p1/figma/exports": () => ({
        status: 200,
        body: { exported: [], failed: [{ id: "1:3", name: "Cart" }] },
      }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Choose frames from Figma" }));
    fireEvent.click(await screen.findByRole("checkbox", { name: /Cart/ }));
    fireEvent.click(screen.getByRole("button", { name: "Export 1 selected" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("These frames failed to export: Cart.");
    expect(screen.getByRole("checkbox", { name: /Cart/ })).toBeChecked();
  });

  it("shows Figma's reason when the token is rejected", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "GET /api/projects/p1/figma/frames": () => ({
        status: 409,
        body: { detail: "Figma rejected the stored token. Edit the project and enter a new one." },
      }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Choose frames from Figma" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Figma rejected the stored token");
  });

  it("needs a selection before exporting", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "GET /api/projects/p1/figma/frames": () => ({ status: 200, body: frames }),
    });
    renderAt("/projects/p1");

    fireEvent.click(await screen.findByRole("button", { name: "Choose frames from Figma" }));

    expect(await screen.findByRole("button", { name: "Export 0 selected" })).toBeDisabled();
  });
});
