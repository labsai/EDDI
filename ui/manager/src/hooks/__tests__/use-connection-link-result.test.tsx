import { describe, it, expect, vi, beforeEach } from "vitest";
import { waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { useConnectionLinkResult } from "@/hooks/use-connection-link-result";

/**
 * `?mailboxLinked=<connection>` is a plain URL parameter anyone can hand out, so
 * the success toast is claimed only when the refreshed grant list backs it.
 */

const success = vi.spyOn(toast, "success");

function Probe() {
  useConnectionLinkResult();
  return null;
}

function grants(body: object[] | null) {
  server.use(
    http.get("*/connections/gws/mailbox-grants", () =>
      body === null ? new HttpResponse(null, { status: 403 }) : HttpResponse.json(body),
    ),
  );
}

beforeEach(() => {
  success.mockClear();
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
});
