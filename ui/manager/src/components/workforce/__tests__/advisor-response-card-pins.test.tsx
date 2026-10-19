import { beforeEach, describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { AdvisorResponseCard } from "../advisor-response-card";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@/components/auth/auth-context";

/**
 * Pinned responses are agent output stored in localStorage, which survives
 * sign-out and is shared by everyone using the browser profile. The key now names
 * the signed-in principal, so a second user does not read the first one's pins.
 */

function asUser(username: string): AuthContextValue {
  return {
    ...GUEST_CONTEXT,
    method: "keycloak",
    user: { username, firstName: "", lastName: "", email: "", fullName: "" },
  };
}

function card(auth: AuthContextValue) {
  return renderWithProviders(
    <AuthContext.Provider value={auth}>
      <AdvisorResponseCard
        displayName="Ana"
        agentId="a1"
        content="Ship it on Friday."
        boardId="board1"
      />
    </AuthContext.Provider>,
  );
}

describe("AdvisorResponseCard — pins are per principal", () => {
  beforeEach(() => localStorage.clear());

  it("stores a pin under a key that names the signed-in user", async () => {
    const user = userEvent.setup();
    card(asUser("alice"));

    await user.click(screen.getByRole("button", { name: "Pin response" }));

    expect(localStorage.getItem("workforce-pins-alice:board1")).toContain("Ship it on Friday.");
    // Never the shared, principal-less key it used before.
    expect(localStorage.getItem("workforce-pins-board1")).toBeNull();
  });

  it("does not show one user's pin to the next user on the same browser", async () => {
    const user = userEvent.setup();
    const first = card(asUser("alice"));
    await user.click(screen.getByRole("button", { name: "Pin response" }));
    expect(screen.getByRole("button", { name: "Unpin response" })).toBeInTheDocument();
    first.unmount();

    card(asUser("bob"));

    expect(screen.getByRole("button", { name: "Pin response" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Unpin response" })).not.toBeInTheDocument();
  });

  it("ignores a pin saved under the old, principal-less key", () => {
    localStorage.setItem(
      "workforce-pins-board1",
      JSON.stringify([{ speakerName: "Ana", content: "Ship it on Friday.", timestamp: 1, boardId: "board1", sessionId: "" }]),
    );
    card(asUser("alice"));
    expect(screen.getByRole("button", { name: "Pin response" })).toBeInTheDocument();
  });
});
