import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Toaster } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { EmailSenderRolesEditor } from "@/components/channels/email-sender-roles";
import { parseRolesDocument } from "@/lib/email-roles";

function render(onChanged = vi.fn(), disabled = false) {
  renderWithProviders(
    <>
      <Toaster />
      <EmailSenderRolesEditor integrationId="mail1" disabled={disabled} onChanged={onChanged} />
    </>,
  );
  return onChanged;
}

describe("EmailSenderRolesEditor", () => {
  it("summarises the roles and warns what their trust rests on", async () => {
    render();
    await waitFor(() => expect(screen.getByTestId("email-roles-summary")).toBeInTheDocument());
    expect(screen.getByTestId("email-roles-summary").textContent).toContain("customer");
    expect(screen.getByTestId("email-roles-summary").textContent).toContain("kb_search");
    expect(screen.getByTestId("email-roles-trust")).toBeInTheDocument();
  });

  it("replaces the roles with the version it read and the reason, and reports the new version", async () => {
    let sent: Record<string, unknown> | undefined;
    server.use(
      http.put("*/channelstore/channels/:id/email-roles", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ previousVersion: 1, version: 2, changed: true, senderRoles: [], rolePolicies: {}, changes: [], warnings: [] });
      }),
    );
    const onChanged = render();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("email-roles-json")).toBeInTheDocument());

    fireEvent.change(screen.getByTestId("email-roles-json"), {
      target: { value: JSON.stringify({ senderRoles: [{ role: "exec", senders: ["cfo@x.com"] }], rolePolicies: { exec: {} } }) },
    });
    await user.type(screen.getByTestId("email-roles-reason"), "CFO");
    await user.click(screen.getByTestId("email-roles-apply"));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(2));
    expect(sent).toMatchObject({ expectedVersion: 1, senderRoles: [{ role: "exec", senders: ["cfo@x.com"] }], reason: "CFO" });
  });

  it("does not apply text that is not a roles document", async () => {
    render();
    await waitFor(() => expect(screen.getByTestId("email-roles-json")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("email-roles-json"), { target: { value: "[1, 2" } });
    expect(screen.getByTestId("email-roles-invalid")).toBeInTheDocument();
    expect(screen.getByTestId("email-roles-apply")).toBeDisabled();
  });

  it("is not applied while the page has unsaved changes", async () => {
    render(vi.fn(), true);
    await waitFor(() => expect(screen.getByTestId("email-roles-disabled")).toBeInTheDocument());
    expect(screen.getByTestId("email-roles-json")).toBeDisabled();
  });

  it("parses only an object of roles and policies", () => {
    expect(parseRolesDocument("{}")).toEqual({ senderRoles: [], rolePolicies: {} });
    expect(parseRolesDocument("[]")).toBeNull();
    expect(parseRolesDocument('{"senderRoles": {}}')).toBeNull();
  });
});
