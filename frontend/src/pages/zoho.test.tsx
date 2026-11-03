import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import { AuthProvider } from "../auth/AuthContext";
import { admin, fakeBackend, signedInAs, viewer } from "../test/fakeBackend";

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const status = (overrides = {}) => ({
  status: 200,
  body: {
    configured: true,
    connected: true,
    connectedBy: "admin@corp.test",
    connectedAt: "2026-01-02T00:00:00Z",
    dataCenter: "in",
    attachmentsEnabled: true,
    ...overrides,
  },
});

const BASE = "/api/integrations/zoho";
const connectedZoho = {
  [`GET ${BASE}/status`]: () => status(),
  [`GET ${BASE}/portals`]: () => ({ status: 200, body: [{ id: "1", name: "Acme" }] }),
  [`GET ${BASE}/portals/1/projects`]: () => ({ status: 200, body: [{ id: "2", name: "Shop" }] }),
  [`GET ${BASE}/portals/1/projects/2/users`]: () => ({
    status: 200,
    body: [
      { id: "57", zpuid: "238", name: "Nolan", email: "n@corp.test" },
      { id: "58", zpuid: "239", name: "Rae", email: null },
    ],
  }),
};

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const clickNext = () => fireEvent.click(screen.getByRole("button", { name: "Next" }));

async function expectStep(title: string) {
  expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
}

describe("Zoho in the project wizard", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses typed IDs until Zoho is connected", async () => {
    fakeBackend({ "GET /api/me": signedInAs(admin), [`GET ${BASE}/status`]: () => status({ connected: false }) });
    renderAt("/projects/new");
    await expectStep("Project");

    expect(await screen.findByLabelText("Zoho portal ID")).toBeInTheDocument();
    expect(screen.queryByLabelText("Zoho portal")).not.toBeInTheDocument();
  });

  it("offers portals, then projects, then people, and sends zpuid for each person", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      ...connectedZoho,
      "POST /api/projects": () => ({ status: 201, body: { id: "p1" } }),
      "GET /api/projects/p1": () => ({ status: 404 }),
    });
    renderAt("/projects/new");
    await expectStep("Project");

    type("Project name", "Shop");
    fireEvent.change(await screen.findByLabelText("Zoho portal"), { target: { value: "1" } });
    await screen.findByRole("option", { name: "Shop" });
    type("Zoho project", "2");
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
    type("Title", "F");
    clickNext();
    await expectStep("Departments");
    await screen.findAllByRole("option", { name: "Nolan (n@corp.test)" });
    for (const name of ["Design", "Frontend", "Backend", "Security"]) {
      type(`${name} assignee`, "238");
      type(`${name} fallback`, "239");
    }
    type("Security lead", "238");
    fireEvent.click(screen.getByRole("button", { name: "Create project" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "POST")).toBe(true));
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
    const body = JSON.parse(post?.[1]?.body as string);
    expect(body.zohoPortalId).toBe("1");
    expect(body.zohoProjectId).toBe("2");
    expect(body.departmentMappings[3]).toEqual({
      category: "SECURITY",
      defaultZohoUserId: "238",
      fallbackZohoUserId: "239",
      securityLeadZohoUserId: "238",
    });
  });

  it("clears the project when the portal changes", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      ...connectedZoho,
      [`GET ${BASE}/portals`]: () => ({ status: 200, body: [{ id: "1", name: "Acme" }, { id: "9", name: "Other" }] }),
      [`GET ${BASE}/portals/9/projects`]: () => ({ status: 200, body: [] }),
    });
    renderAt("/projects/new");
    await expectStep("Project");
    fireEvent.change(await screen.findByLabelText("Zoho portal"), { target: { value: "1" } });
    await screen.findByRole("option", { name: "Shop" });
    type("Zoho project", "2");

    type("Zoho portal", "9");

    expect(screen.getByLabelText("Zoho project")).toHaveValue("");
  });

  it("explains when portals can't be loaded", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      [`GET ${BASE}/status`]: () => status(),
      [`GET ${BASE}/portals`]: () => ({ status: 502 }),
    });
    renderAt("/projects/new");
    await expectStep("Project");

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load your Zoho portals");
  });
});

describe("Settings", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const settingsBackend = {
    "GET /api/me": signedInAs(admin),
    "GET /api/user-mappings": () => ({ status: 200, body: [] }),
  };

  it("is for admins only", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer) });
    renderAt("/settings");

    expect(await screen.findByText("You don't have access to this page.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Settings" })).not.toBeInTheDocument();
  });

  it("explains what's missing before the OAuth client exists", async () => {
    fakeBackend({ ...settingsBackend, [`GET ${BASE}/status`]: () => status({ configured: false, connected: false }) });
    renderAt("/settings");

    expect(await screen.findByText(/Zoho isn't set up yet/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Connect Zoho" })).not.toBeInTheDocument();
  });

  it("starts the connection by going to Zoho, and says WorkDrive access is requested", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { assign });
    fakeBackend({
      ...settingsBackend,
      [`GET ${BASE}/status`]: () => status({ connected: false, connectedBy: null, connectedAt: null }),
      [`POST ${BASE}/connect`]: () => ({ status: 200, body: { authorizationUrl: "https://accounts.zoho.in/auth" } }),
    });
    renderAt("/settings");

    expect(await screen.findByText(/asks for WorkDrive access/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Connect Zoho" }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://accounts.zoho.in/auth"));
  });

  it("shows fixed messages for connect outcomes and never text from the link", async () => {
    fakeBackend({ ...settingsBackend, [`GET ${BASE}/status`]: () => status({ connected: false }) });
    renderAt("/settings?zoho=wrong_data_center");

    expect(await screen.findByRole("alert")).toHaveTextContent("another data center");
  });

  it("ignores unknown outcome values", async () => {
    fakeBackend({ ...settingsBackend, [`GET ${BASE}/status`]: () => status({ connected: false }) });
    renderAt("/settings?zoho=Call%20this%20number");

    await screen.findByRole("button", { name: "Connect Zoho" });
    expect(screen.queryByText(/Call this number/)).not.toBeInTheDocument();
  });

  it("asks before disconnecting", async () => {
    const fetchMock = fakeBackend({
      ...settingsBackend,
      ...connectedZoho,
      [`GET ${BASE}/portals/1/field-options`]: () => ({ status: 200, body: { severity: {}, classification: {} } }),
      [`DELETE ${BASE}/connection`]: () => ({ status: 204 }),
    });
    renderAt("/settings");

    fireEvent.click(await screen.findByRole("button", { name: "Disconnect" }));
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Yes, disconnect" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(true));
  });

  it("loads and saves severity and classification IDs, leaving out blanks", async () => {
    const fetchMock = fakeBackend({
      ...settingsBackend,
      ...connectedZoho,
      [`GET ${BASE}/portals/1/field-options`]: () => ({
        status: 200,
        body: { severity: { HIGH: "11" }, classification: {} },
      }),
      [`PUT ${BASE}/portals/1/field-options`]: () => ({ status: 200, body: { severity: {}, classification: {} } }),
    });
    renderAt("/settings");

    await waitFor(() => expect(screen.getAllByLabelText("HIGH")[0]).toHaveValue("11"));
    type("LOW", " 12 ");
    fireEvent.change(screen.getAllByLabelText("BACKEND")[0]!, { target: { value: "22" } });
    fireEvent.click(screen.getByRole("button", { name: "Save IDs" }));

    expect(await screen.findByText("Saved.")).toBeInTheDocument();
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(JSON.parse(put?.[1]?.body as string)).toEqual({
      severity: { HIGH: "11", LOW: "12" },
      classification: { BACKEND: "22" },
    });
  });

  it("adds and removes user mappings", async () => {
    const fetchMock = fakeBackend({
      "GET /api/me": signedInAs(admin),
      [`GET ${BASE}/status`]: () => status({ connected: false }),
      "GET /api/user-mappings": () => ({
        status: 200,
        body: [{ id: "m1", githubUsername: "alice", zohoUserId: "238" }],
      }),
      "POST /api/user-mappings": () => ({ status: 201, body: {} }),
      "DELETE /api/user-mappings/m1": () => ({ status: 204 }),
    });
    renderAt("/settings");

    expect(await screen.findByText("alice")).toBeInTheDocument();
    type("GitHub username", " bob ");
    type("Zoho user ID", "239");
    fireEvent.click(screen.getByRole("button", { name: "Add mapping" }));

    await waitFor(() => {
      const post = fetchMock.mock.calls.find(([path, init]) => path === "/api/user-mappings" && init?.method === "POST");
      expect(JSON.parse(post?.[1]?.body as string)).toEqual({ githubUsername: "bob", zohoUserId: "239" });
    });
    fireEvent.click(screen.getByRole("button", { name: "Remove alice" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === "DELETE")).toBe(true));
  });

  it("shows why a mapping was refused", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(admin),
      [`GET ${BASE}/status`]: () => status({ connected: false }),
      "GET /api/user-mappings": () => ({ status: 200, body: [] }),
      "POST /api/user-mappings": () => ({ status: 409, body: { detail: "That GitHub username is already mapped." } }),
    });
    renderAt("/settings");
    await screen.findByText("No mappings yet.");

    type("GitHub username", "bob");
    type("Zoho user ID", "239");
    fireEvent.click(screen.getByRole("button", { name: "Add mapping" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already mapped");
  });
});
