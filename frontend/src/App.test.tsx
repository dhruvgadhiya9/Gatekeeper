import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { admin, fakeBackend, signedInAs, signedOut, viewer } from "./test/fakeBackend";

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends visitors who are not signed in to the login page", async () => {
    fakeBackend({ "GET /api/me": signedOut });
    renderAt("/users");

    const signIn = await screen.findByRole("link", { name: "Sign in" });
    expect(signIn).toHaveAttribute("href", "/oauth2/authorization/oidc");
  });

  it("shows a retry instead of the login page when the server is down", async () => {
    fakeBackend({ "GET /api/me": () => ({ status: 503 }) });
    renderAt("/");

    expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("shows known login errors", async () => {
    fakeBackend({ "GET /api/me": signedOut });
    renderAt("/login?error=account_disabled");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your account has been disabled");
  });

  it("never displays text from the error parameter", async () => {
    fakeBackend({ "GET /api/me": signedOut });
    renderAt("/login?error=Call%20this%20number");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Sign-in failed");
    expect(alert).not.toHaveTextContent("Call this number");
  });

  it("redirects signed-in users away from the login page", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer) });
    renderAt("/login");

    expect(await screen.findByRole("heading", { name: "Home" })).toBeInTheDocument();
  });

  it("shows the user and hides Users from non-admins", async () => {
    fakeBackend({ "GET /api/me": signedInAs(viewer) });
    renderAt("/");

    expect(await screen.findByText(/Vic Viewer · Viewer/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
  });

  it("blocks non-admins who open the Users page directly", async () => {
    const fetchMock = fakeBackend({ "GET /api/me": signedInAs(viewer) });
    renderAt("/users");

    expect(await screen.findByText("You don't have access to this page.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalledWith("/api/users", expect.anything());
  });

  it("signs out and returns to the login page", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(viewer),
      "POST /api/auth/logout": () => ({ status: 204 }),
    });
    renderAt("/");

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("link", { name: "Sign in" })).toBeInTheDocument();
  });

  it("stays signed in and says so when sign-out fails", async () => {
    fakeBackend({
      "GET /api/me": signedInAs(viewer),
      "POST /api/auth/logout": () => ({ status: 500 }),
    });
    renderAt("/");

    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(await screen.findByText("Could not sign out.")).toBeInTheDocument();
  });

  describe("Users page", () => {
    const usersRoute = { "GET /api/users": () => ({ status: 200, body: [admin, viewer] }) };

    it("lets an admin change another user's role", async () => {
      const fetchMock = fakeBackend({
        "GET /api/me": signedInAs(admin),
        ...usersRoute,
        [`PUT /api/users/${viewer.id}`]: () => ({ status: 200, body: { ...viewer, role: "QA_LEAD" } }),
      });
      renderAt("/users");

      const select = await screen.findByRole("combobox", { name: `Role for ${viewer.email}` });
      fireEvent.change(select, { target: { value: "QA_LEAD" } });

      await waitFor(() => expect(select).toHaveValue("QA_LEAD"));
      const put = fetchMock.mock.calls.find(([path]) => path === `/api/users/${viewer.id}`);
      expect(JSON.parse(put?.[1]?.body as string)).toEqual({ role: "QA_LEAD", enabled: true });
    });

    it("disables the controls for the admin's own row", async () => {
      fakeBackend({ "GET /api/me": signedInAs(admin), ...usersRoute });
      renderAt("/users");

      const row = (await screen.findByText(admin.email)).closest("tr") as HTMLElement;
      expect(within(row).getByRole("combobox")).toBeDisabled();
      expect(within(row).getByRole("checkbox")).toBeDisabled();
    });

    it("shows the error and keeps the old value when saving fails", async () => {
      fakeBackend({
        "GET /api/me": signedInAs(admin),
        ...usersRoute,
        [`PUT /api/users/${viewer.id}`]: () => ({ status: 409, body: { detail: "Not allowed." } }),
      });
      renderAt("/users");

      const select = await screen.findByRole("combobox", { name: `Role for ${viewer.email}` });
      fireEvent.change(select, { target: { value: "ADMIN" } });

      expect(await screen.findByRole("alert")).toHaveTextContent("Not allowed.");
      expect(select).toHaveValue("VIEWER");
    });
  });
});
