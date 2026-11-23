import { describe, it, expect, vi, beforeEach } from "vitest";
import { waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { useConnectionLinkResult } from "@/hooks/use-connection-link-result";
import { rememberPendingMailboxLink } from "@/lib/pending-mailbox-link";

/**
 * `?mailboxLinked=<connection>` is a plain URL parameter anyone can hand out, so
 * the success toast is claimed only when the refreshed grant list backs it.
 */

const success = vi.spyOn(toast, "success");

function Probe() {
  useConnectionLinkResult();
  return null;
}

type Grant = { address: string; status: string; usable: boolean };

function grants(body: Grant[] | null) {
  server.use(
    http.get("*/connections/gws/mailbox-grants", () =>
      body === null ? new HttpResponse(null, { status: 403 }) : HttpResponse.json(body),
    ),
  );
}

beforeEach(() => {
  success.mockClear();
  sessionStorage.clear();
});

describe("useConnectionLinkResult — mailbox links", () => {
  it("announces a link the refreshed list confirms", async () => {
    grants([{ address: "eddi@eddi.solutions", status: "ACTIVE", usable: true }]);
    renderWithProviders(<Probe />, { initialRoute: "/manage/connections/x?mailboxLinked=gws" });

    await waitFor(() => expect(success).toHaveBeenCalledTimes(1));
  });

  it("stays silent when the refreshed list shows no usable grant", async () => {
    let listed = false;
    server.use(
      http.get("*/connections/gws/mailbox-grants", () => {
        listed = true;
        return HttpResponse.json([{ address: "eddi@eddi.solutions", status: "REFRESH_FAILED", usable: false }]);
      }),
    );
    renderWithProviders(<Probe />, { initialRoute: "/manage/connections/x?mailboxLinked=gws" });

    await waitFor(() => expect(listed).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(success).not.toHaveBeenCalled();
  });

  it("checks the mailbox this tab linked, not any other usable grant", async () => {
    let listed = false;
    server.use(
      http.get("*/connections/gws/mailbox-grants", () => {
        listed = true;
        return HttpResponse.json([{ address: "other@eddi.solutions", status: "ACTIVE", usable: true }]);
      }),
    );
    rememberPendingMailboxLink("gws", "Eddi@Eddi.Solutions");
    renderWithProviders(<Probe />, { initialRoute: "/manage/connections/x?mailboxLinked=gws" });

    await waitFor(() => expect(listed).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(success).not.toHaveBeenCalled();
    expect(sessionStorage.getItem("eddi.mailboxLink.gws")).toBeNull();
  });

  it("announces the remembered mailbox once its grant is usable", async () => {
    grants([
      { address: "other@eddi.solutions", status: "REFRESH_FAILED", usable: false },
      { address: "eddi@eddi.solutions", status: "ACTIVE", usable: true },
    ]);
    rememberPendingMailboxLink("gws", "Eddi@Eddi.Solutions");
    renderWithProviders(<Probe />, { initialRoute: "/manage/connections/x?mailboxLinked=gws" });

    await waitFor(() => expect(success).toHaveBeenCalledTimes(1));
  });

  it("matches an internationalized domain in the backend's punycode form", async () => {
    grants([{ address: "info@xn--bcher-kva.de", status: "ACTIVE", usable: true }]);
    rememberPendingMailboxLink("gws", " Info@Bücher.de ");
    renderWithProviders(<Probe />, { initialRoute: "/manage/connections/x?mailboxLinked=gws" });

    await waitFor(() => expect(success).toHaveBeenCalledTimes(1));
  });
});
