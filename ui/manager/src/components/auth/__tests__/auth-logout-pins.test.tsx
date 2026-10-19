import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth-provider";
import { useAuth } from "@/hooks/use-auth";

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

function SignOut() {
  const { logout } = useAuth();
  return <button onClick={logout}>sign out</button>;
}

describe("AuthProvider — sign-out clears pinned Workforce responses", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    kc.init.mockResolvedValue(true);
    kc.updateToken.mockResolvedValue(true);
  });

  it("removes every workforce-pins key, and only those, before leaving", async () => {
    localStorage.setItem("workforce-pins-alice:board1", "[]");
    localStorage.setItem("workforce-pins-board1", "[]"); // pre-namespacing shape
    localStorage.setItem("eddi-theme", "dark");
    render(
      <AuthProvider configOverride={config}>
        <SignOut />
      </AuthProvider>,
    );

    await userEvent.click(await screen.findByRole("button", { name: "sign out" }));

    expect(kc.logout).toHaveBeenCalled();
    expect(localStorage.getItem("workforce-pins-alice:board1")).toBeNull();
    expect(localStorage.getItem("workforce-pins-board1")).toBeNull();
    expect(localStorage.getItem("eddi-theme")).toBe("dark");
  });
});
