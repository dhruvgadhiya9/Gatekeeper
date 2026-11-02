import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ProjectDetail } from "../../api/projects";
import App from "../../App";
import { AuthProvider } from "../../auth/AuthContext";
import { admin, fakeBackend, signedInAs, viewer } from "../../test/fakeBackend";

const SECRET = "typed-secret-value";

const project: ProjectDetail = {
  id: "p1",
  name: "Shop",
  stagingUrl: "https://staging.shop.test",
  createdBy: "admin@corp.test",
  createdAt: "2026-01-02T00:00:00Z",
  zohoPortalId: "portal",
  zohoProjectId: "project",
  design: { type: "UPLOAD", figmaFileKey: null, tokenStored: false, uploads: [] },
  repositories: [{ id: "r1", type: "BACKEND", repoFullName: "org/api", defaultBranch: "main" }],
  testAccounts: [{ id: "a1", role: "admin", username: "qa@shop.test", loginUrl: "https://staging.shop.test/login" }],
  features: [{ id: "f1", title: "Checkout", description: "Pay", acceptanceCriteria: "Works", priority: "HIGH" }],
  departmentMappings: [
    { category: "SECURITY", defaultZohoUserId: "u1", fallbackZohoUserId: "u2", securityLeadZohoUserId: "u3" },
  ],
};

const emptyPage = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });

const clickNext = () => fireEvent.click(screen.getByRole("button", { name: "Next" }));

async function expectStep(title: string) {
  expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
}

/** Completes every step with valid values, ending on the Departments step. */
async function fillWizardToLastStep() {
  await expectStep("Project");
  type("Project name", "Shop");
  type("Zoho portal ID", "portal");
  type("Zoho project ID", "project");
  clickNext();

  await expectStep("Design");
  clickNext();

  await expectStep("Repositories");
  fireEvent.click(screen.getByRole("button", { name: "Remove repository 2" }));
  type("Repository", "org/web");
  clickNext();

  await expectStep("Staging");
  type("Staging URL", "https://staging.shop.test");
  clickNext();

  await expectStep("Test accounts");
  fireEvent.click(screen.getByRole("button", { name: "Add test account" }));
  expect(screen.getByLabelText("Login page URL")).toHaveValue("https://staging.shop.test");
  type("Role", "admin");
  type("Username", "qa@shop.test");
  type("Password", SECRET);
  clickNext();

  await expectStep("Features");
  type("Title", "Checkout");
  clickNext();

  await expectStep("Departments");
  for (const name of ["Design", "Frontend", "Backend", "Security"]) {
    type(`${name} assignee (Zoho user ID)`, "u1");
    type(`${name} fallback (Zoho user ID)`, "u2");
  }
  type("Security lead (Zoho user ID)", "u3");
}

const createButton = () => screen.getByRole("button", { name: "Create project" });

describe("project intake", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("won't leave a step with missing required fields", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin) });
    renderAt("/projects/new");
    await expectStep("Project");

    clickNext();

    expect(await screen.findAllByText("This field is required.")).toHaveLength(3);
    expect(screen.getByRole("heading", { name: "Project" })).toBeInTheDocument();
  });

  it("removes a required-field error as soon as the field is filled in", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin) });
    renderAt("/projects/new");
    await expectStep("Project");
    clickNext();
    await screen.findAllByText("This field is required.");

    type("Project name", "Shop");

    expect(screen.getAllByText("This field is required.")).toHaveLength(2);
  });

  it("sends everything from all seven steps and opens the new project", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "POST /api/projects": () => ({ status: 201, body: project }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/new");

    await fillWizardToLastStep();
    fireEvent.click(createButton());

    expect(await screen.findByRole("heading", { name: "Shop" })).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([path, init]) => path === "/api/projects" && init?.method === "POST");
    const body = JSON.parse(call?.[1]?.body as string);
    expect(body).toMatchObject({
      name: "Shop",
      design: null,
      stagingUrl: "https://staging.shop.test",
      repositories: [{ type: "FRONTEND", repoFullName: "org/web", defaultBranch: "main" }],
      testAccounts: [{ role: "admin", username: "qa@shop.test", password: SECRET }],
      features: [{ title: "Checkout", priority: "MEDIUM" }],
    });
    expect(body.departmentMappings).toHaveLength(4);
    expect(body.departmentMappings[0].securityLeadZohoUserId).toBeNull();
    expect(body.departmentMappings[3].securityLeadZohoUserId).toBe("u3");
  });

  it("keeps the draft when moving back, including passwords", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin) });
    renderAt("/projects/new");
    await fillWizardToLastStep();

    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await expectStep("Features");
    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await expectStep("Test accounts");

    expect(screen.getByLabelText("Password")).toHaveValue(SECRET);
  });

  it("never writes the draft to browser storage", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    fakeBackend({ "GET /api/me": signedInAs(admin) });
    renderAt("/projects/new");

    await fillWizardToLastStep();

    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it("returns to the step a server error belongs to and shows it on the field", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "POST /api/projects": () => ({
        status: 400,
        body: {
          detail: "Request validation failed.",
          errors: [{ field: "stagingUrl", message: "is a production address, and production can never be tested" }],
        },
      }),
    });
    renderAt("/projects/new");
    await fillWizardToLastStep();

    fireEvent.click(createButton());

    await expectStep("Staging");
    expect(screen.getByText("This field is a production address, and production can never be tested.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Nothing has been saved");
    expect(screen.getByLabelText("Staging URL")).toHaveValue("https://staging.shop.test");
  });

  it("stays on the step and shows a message when the server can't be reached", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin), "POST /api/projects": () => ({ status: 500 }) });
    renderAt("/projects/new");
    await fillWizardToLastStep();

    fireEvent.click(createButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("The request failed.");
    expect(createButton()).toBeEnabled();
  });

  it("imports features from CSV, replaces the blank row and lists skipped rows", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "POST /api/projects/feature-csv": () => ({
        status: 200,
        body: {
          features: [{ title: "From CSV", description: "", acceptanceCriteria: "", priority: "LOW" }],
          errors: [{ row: 3, message: "title must not be blank" }],
        },
      }),
    });
    renderAt("/projects/new");
    await expectStep("Project");
    type("Project name", "Shop");
    type("Zoho portal ID", "p");
    type("Zoho project ID", "p");
    clickNext();
    await expectStep("Design");
    clickNext();
    await expectStep("Repositories");
    fireEvent.click(screen.getByRole("button", { name: "Remove repository 2" }));
    type("Repository", "org/web");
    clickNext();
    await expectStep("Staging");
    type("Staging URL", "https://s.test");
    clickNext();
    await expectStep("Test accounts");
    clickNext();
    await expectStep("Features");

    const file = new File(["title\nFrom CSV\n"], "f.csv", { type: "text/csv" });
    fireEvent.change(screen.getByLabelText("Import from CSV"), { target: { files: [file] } });

    expect(await screen.findByText("Imported 1 features.")).toBeInTheDocument();
    expect(screen.getByText("Row 3: title must not be blank")).toBeInTheDocument();
    expect(screen.getByLabelText("Title")).toHaveValue("From CSV");
    expect(screen.getAllByLabelText("Title")).toHaveLength(1);
  });

  it("uploads chosen images after creating the project", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      "POST /api/projects": () => ({ status: 201, body: project }),
      "POST /api/projects/p1/design-uploads": () => ({ status: 201, body: [] }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/new");
    await expectStep("Project");
    type("Project name", "Shop");
    type("Zoho portal ID", "p");
    type("Zoho project ID", "p");
    clickNext();
    await expectStep("Design");
    fireEvent.change(screen.getByLabelText("Design source"), { target: { value: "UPLOAD" } });

    clickNext();
    expect(await screen.findByText("Choose at least one image.")).toBeInTheDocument();

    const image = new File(["x"], "home.png", { type: "image/png" });
    const notImage = new File(["x"], "notes.txt", { type: "text/plain" });
    fireEvent.change(screen.getByLabelText("Design images"), { target: { files: [image, notImage] } });
    expect(screen.getByText(/Some files were skipped/)).toBeInTheDocument();
    expect(screen.getByText("home.png")).toBeInTheDocument();
    clickNext();

    await expectStep("Repositories");
    fireEvent.click(screen.getByRole("button", { name: "Remove repository 2" }));
    type("Repository", "org/web");
    clickNext();
    await expectStep("Staging");
    type("Staging URL", "https://s.test");
    clickNext();
    await expectStep("Test accounts");
    clickNext();
    await expectStep("Features");
    type("Title", "F");
    clickNext();
    await expectStep("Departments");
    for (const name of ["Design", "Frontend", "Backend", "Security"]) {
      type(`${name} assignee (Zoho user ID)`, "u1");
      type(`${name} fallback (Zoho user ID)`, "u2");
    }
    type("Security lead (Zoho user ID)", "u3");
    fireEvent.click(createButton());

    expect(await screen.findByRole("heading", { name: "Shop" })).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([path]) => path === "/api/projects/p1/design-uploads");
    expect((call?.[1]?.body as FormData).getAll("files")).toEqual([image]);
    expect(JSON.parse(fetchMock.mock.calls.find(([path, init]) => path === "/api/projects" && init?.method === "POST")?.[1]?.body as string).design).toEqual({ type: "UPLOAD" });
  });

  it("says so when images fail to upload after the project was created", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "POST /api/projects": () => ({ status: 201, body: project }),
      "POST /api/projects/p1/design-uploads": () => ({ status: 500 }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
    });
    renderAt("/projects/new");
    await expectStep("Project");
    type("Project name", "Shop");
    type("Zoho portal ID", "p");
    type("Zoho project ID", "p");
    clickNext();
    await expectStep("Design");
    fireEvent.change(screen.getByLabelText("Design source"), { target: { value: "UPLOAD" } });
    fireEvent.change(screen.getByLabelText("Design images"), {
      target: { files: [new File(["x"], "home.png", { type: "image/png" })] },
    });
    clickNext();
    await expectStep("Repositories");
    fireEvent.click(screen.getByRole("button", { name: "Remove repository 2" }));
    type("Repository", "org/web");
    clickNext();
    await expectStep("Staging");
    type("Staging URL", "https://s.test");
    clickNext();
    await expectStep("Test accounts");
    clickNext();
    await expectStep("Features");
    type("Title", "F");
    clickNext();
    await expectStep("Departments");
    for (const name of ["Design", "Frontend", "Backend", "Security"]) {
      type(`${name} assignee (Zoho user ID)`, "u1");
      type(`${name} fallback (Zoho user ID)`, "u2");
    }
    type("Security lead (Zoho user ID)", "u3");
    fireEvent.click(createButton());

    expect(await screen.findByRole("alert")).toHaveTextContent("didn't upload");
  });

  it("blocks viewers from the wizard and hides New project", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(viewer),
      "GET /api/projects?page=0&size=20": () => ({ status: 200, body: emptyPage }),
    });
    renderAt("/projects");

    expect(await screen.findByText(/A QA lead or admin can create one/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "New project" })).not.toBeInTheDocument();
  });

  it("doesn't show the wizard to a viewer who opens it directly", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer) });
    renderAt("/projects/new");

    expect(await screen.findByText("You don't have access to this page.")).toBeInTheDocument();
  });

  it("lists projects and moves between pages", async () => {
    const summary = { id: "p1", name: "Shop", stagingUrl: "https://s.test", createdBy: "a", createdAt: "2026-01-02T00:00:00Z" };
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      "GET /api/projects?page=0&size=20": () => ({
        status: 200,
        body: { items: [summary], page: 0, size: 20, totalItems: 21, totalPages: 2 },
      }),
      "GET /api/projects?page=1&size=20": () => ({
        status: 200,
        body: { items: [{ ...summary, id: "p2", name: "Second" }], page: 1, size: 20, totalItems: 21, totalPages: 2 },
      }),
    });
    renderAt("/projects");

    expect(await screen.findByRole("link", { name: "Shop" })).toHaveAttribute("href", "/projects/p1");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByRole("link", { name: "Second" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("shows project details and says passwords are hidden", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer), "GET /api/projects/p1": () => ({ status: 200, body: project }) });
    renderAt("/projects/p1");

    expect(await screen.findByRole("heading", { name: "Shop" })).toBeInTheDocument();
    expect(screen.getByText(/qa@shop.test/)).toBeInTheDocument();
    expect(screen.getByText(/never shown/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Add images")).not.toBeInTheDocument();
  });

  it("explains when a project doesn't exist", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin) });
    renderAt("/projects/missing");

    expect(await screen.findByText("This project doesn't exist.")).toBeInTheDocument();
  });

  it("lets a QA lead add images from the project page", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs({ ...admin, role: "QA_LEAD" }),
      "GET /api/projects/p1": () => ({ status: 200, body: project }),
      "POST /api/projects/p1/design-uploads": () => ({ status: 201, body: [] }),
    });
    renderAt("/projects/p1");

    const input = await screen.findByLabelText("Add images");
    fireEvent.change(input, { target: { files: [new File(["x"], "a.png", { type: "image/png" })] } });

    await waitFor(() =>
      expect(fetchMock.mock.calls.filter(([path]) => path === "/api/projects/p1").length).toBe(2),
    );
  });
});
