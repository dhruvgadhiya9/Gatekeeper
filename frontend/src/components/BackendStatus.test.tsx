import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendStatus } from "./BackendStatus";

function mockFetch(implementation: () => Promise<Partial<Response>>) {
  vi.stubGlobal("fetch", vi.fn(implementation));
}

describe("BackendStatus", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows running when health is UP", async () => {
    mockFetch(async () => ({ ok: true, json: async () => ({ status: "UP" }) }));
    render(<BackendStatus />);
    expect(await screen.findByText("Backend is running")).toBeInTheDocument();
  });

  it("shows unreachable when health is DOWN", async () => {
    mockFetch(async () => ({ ok: false }));
    render(<BackendStatus />);
    expect(await screen.findByText("Backend is not reachable")).toBeInTheDocument();
  });

  it("shows unreachable when the request fails", async () => {
    mockFetch(async () => {
      throw new Error("network");
    });
    render(<BackendStatus />);
    expect(await screen.findByText("Backend is not reachable")).toBeInTheDocument();
  });
});
