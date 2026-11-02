import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, setUnauthorizedHandler } from "./client";
import { fakeBackend } from "../test/fakeBackend";

describe("api", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setUnauthorizedHandler(() => {});
    document.cookie = "XSRF-TOKEN=; max-age=0";
  });

  function sentHeaders(fetchMock: ReturnType<typeof fakeBackend>) {
    return fetchMock.mock.calls[0]?.[1]?.headers as Headers;
  }

  it("sends the CSRF cookie back as a header on state changes", async () => {
    document.cookie = "XSRF-TOKEN=token%20value";
    const fetchMock = fakeBackend({ "PUT /x": () => ({ status: 204 }) });

    await api("/x", { method: "PUT" });

    expect(sentHeaders(fetchMock).get("X-XSRF-TOKEN")).toBe("token value");
  });

  it("does not send the CSRF header on reads", async () => {
    document.cookie = "XSRF-TOKEN=abc";
    const fetchMock = fakeBackend({ "GET /x": () => ({ status: 200, body: {} }) });

    await api("/x");

    expect(sentHeaders(fetchMock).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("returns the parsed body", async () => {
    fakeBackend({ "GET /x": () => ({ status: 200, body: { value: 1 } }) });

    await expect(api("/x")).resolves.toEqual({ value: 1 });
  });

  it("throws the problem detail on errors", async () => {
    fakeBackend({ "GET /x": () => ({ status: 409, body: { detail: "Conflict here" } }) });

    const error = await api("/x").catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, message: "Conflict here" });
  });

  it("reports an expired session", async () => {
    fakeBackend({ "GET /x": () => ({ status: 401 }) });
    const handler = vi.fn();
    setUnauthorizedHandler(handler);

    await expect(api("/x")).rejects.toBeInstanceOf(ApiError);

    expect(handler).toHaveBeenCalledOnce();
  });
});
