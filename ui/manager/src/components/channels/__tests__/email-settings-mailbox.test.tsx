import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
import { EmailPlatformFields } from "@/components/channels/email-settings";

/**
 * Email settings on a dedicated-mailbox (MAILBOX) connection: an administrator
 * sees whether the integration's own address is linked; anybody else is never
 * sent to the admin-only grant list.
 */

const ID = "68a1b2c3d4e5f60718293a4b";
const MAILBOX_CONNECTION = {
  name: "gws",
  authType: "OAUTH2_AUTHORIZATION_CODE",
  binding: "MAILBOX",
  allowUnverifiedPrincipal: false,
  oauth: { accountCheck: "google-gmail-profile" },
  baseUrlAllowlist: ["imaps://imap.gmail.com", "https://gmail.googleapis.com"],
};

function mailboxConnection(grants: object[] | null, onList?: () => void, onRead?: () => void) {
  server.use(
    http.get("*/connectionstore/connections/descriptors", () =>
      HttpResponse.json([
        { resource: `eddi://ai.labs.connection/connectionstore/connections/${ID}?version=1`, name: "gws", description: "" },
      ]),
    ),
    http.get(`*/connectionstore/connections/${ID}`, () => {
      onRead?.();
      return HttpResponse.json(MAILBOX_CONNECTION);
    }),
    http.get("*/connections/gws/mailbox-grants", () => {
      onList?.();
      return grants === null ? new HttpResponse(null, { status: 403 }) : HttpResponse.json(grants);
    }),
  );
}

const PC = { mode: "trigger", connection: "gws", host: "imap.gmail.com", address: "Eddi@Eddi.Solutions" };

describe("EmailPlatformFields — dedicated mailbox state", () => {
  it("says the integration's address is linked, matching it case-insensitively", async () => {
    mailboxConnection([{ address: "eddi@eddi.solutions", status: "ACTIVE", usable: true }]);
    renderWithProviders(<EmailPlatformFields platformConfig={PC} onChange={() => {}} />);

    expect(await screen.findByTestId("email-mailbox-linked")).toBeInTheDocument();
  });

  it("says it is not linked when the grant is unusable or missing", async () => {
    mailboxConnection([{ address: "eddi@eddi.solutions", status: "REFRESH_FAILED", usable: false }]);
    renderWithProviders(<EmailPlatformFields platformConfig={PC} onChange={() => {}} />);

    expect(await screen.findByTestId("email-mailbox-not-linked")).toBeInTheDocument();
  });

  it("never asks the admin-only grant list for a non-admin", async () => {
    let listed = false;
    let read = false;
    mailboxConnection(
      [],
      () => {
        listed = true;
      },
      () => {
        read = true;
      },
    );
    renderWithProviders(
      <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-admin-not", "eddi-editor"] }}>
        <EmailPlatformFields platformConfig={PC} onChange={() => {}} />
      </AuthContext.Provider>,
    );

    // The connection was read (so the MAILBOX binding was known) before judging.
    await waitFor(() => expect(read).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(listed).toBe(false);
    expect(screen.queryByTestId("email-mailbox-linked")).not.toBeInTheDocument();
    expect(screen.queryByTestId("email-mailbox-not-linked")).not.toBeInTheDocument();
  });
});
