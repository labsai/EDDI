import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api } from "@/lib/api-client";
import { AuthProvider } from "../auth-provider";

const kc = vi.hoisted(() => ({
  init: vi.fn(),
  logout: vi.fn(),
  login: vi.fn(),
  updateToken: vi.fn(),
}));

vi.mock("keycloak-js", () => ({
  default: class MockKeycloak {
    token = "tok";
    idToken = "id";
    tokenParsed = {};
    onTokenExpired: (() => void) | null = null;
    init = kc.init;
    login = kc.login;
    logout = kc.logout;
    updateToken = kc.updateToken;
    loadUserInfo() {
      return Promise.resolve({});
    }
  },
}));

const config = {
  method: "keycloak" as const,
  url: "http://localhost:8180",
  realm: "eddi",
  clientId: "eddi-manager",
};

function renderProvider() {
  return render(
    <AuthProvider configOverride={config}>
      <div data-testid="app">app</div>
    </AuthProvider>,
  );
}

describe("AuthProvider — failure handling", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    kc.init.mockResolvedValue(true);
    kc.updateToken.mockResolvedValue(true);
  });

  it("replaces the app with a sign-in prompt when Keycloak init fails", async () => {
    kc.init.mockRejectedValue(new Error("network down"));
    renderProvider();

    expect(await screen.findByTestId("auth-error")).toBeInTheDocument();
    expect(screen.queryByTestId("app")).not.toBeInTheDocument();

    screen.getByRole("button", { name: "Sign in again" }).click();
    expect(kc.login).toHaveBeenCalled();
  });

  it("keeps the app mounted and offers re-login when the session expires, instead of logging out", async () => {
    const url = `${window.location.origin}/agentstore/agents`;
    server.use(http.get(url, () => new HttpResponse(null, { status: 401 })));
    kc.updateToken.mockImplementation(async (minValidity: number) => {
      if (minValidity === -1) throw new Error("refresh token expired");
      return true;
    });
    renderProvider();
    await screen.findByTestId("app");

    await expect(api.get("/agentstore/agents")).rejects.toMatchObject({ status: 401 });

    const banner = await screen.findByTestId("session-expired-banner");
    expect(banner).toHaveTextContent("Your session has expired.");
    expect(screen.getByTestId("app")).toBeInTheDocument();
    expect(kc.logout).not.toHaveBeenCalled();

    await waitFor(() => expect(screen.getByRole("button", { name: "Sign in again" })).toBeEnabled());
  });

  it("takes the banner down once a refresh succeeds again", async () => {
    const url = `${window.location.origin}/agentstore/agents`;
    let fail = true;
    server.use(
      http.get(url, () =>
        fail ? new HttpResponse(null, { status: 401 }) : HttpResponse.json({}),
      ),
    );
    let refreshOk = false;
    kc.updateToken.mockImplementation(async (minValidity: number) => {
      if (minValidity === -1 && !refreshOk) throw new Error("transient");
      return true;
    });
    renderProvider();
    await screen.findByTestId("app");
    await expect(api.get("/agentstore/agents")).rejects.toMatchObject({ status: 401 });
    await screen.findByTestId("session-expired-banner");

    fail = false;
    refreshOk = true;
    await api.get("/agentstore/agents");
    await api.get("/agentstore/agents");
    // a successful pre-flight refresh (updateToken(30)) clears it
    await waitFor(() =>
      expect(screen.queryByTestId("session-expired-banner")).not.toBeInTheDocument(),
    );
  });
});
