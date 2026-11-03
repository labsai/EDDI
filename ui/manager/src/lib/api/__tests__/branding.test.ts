import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { brandingErrorInfo, getBranding, updateBranding, uploadBrandAsset } from "@/lib/api/branding";
import { EMPTY_COLORS } from "@/lib/brand-css";

describe("branding API", () => {
  it("uploads the file's bytes as the raw body, not as JSON", async () => {
    let received: { bytes: number[]; type: string | null } | null = null;
    server.use(
      http.put("*/administration/branding/assets/:slot", async ({ request }) => {
        received = {
          bytes: [...new Uint8Array(await request.arrayBuffer())],
          type: request.headers.get("Content-Type"),
        };
        return HttpResponse.json({ effective: { revision: 1 }, locked: false, source: "database" });
      }),
    );
    await uploadBrandAsset("logo", new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: "image/png" }));
    expect(received).toEqual({ bytes: [0x89, 0x50, 0x4e, 0x47], type: "image/png" });
  });

  it("keeps a refused write's violations and the conflict's current revision", async () => {
    server.use(
      http.put("*/administration/branding", () =>
        HttpResponse.json({ error: "stale", currentRevision: 9 }, { status: 409 }),
      ),
    );
    const update = {
      revision: 3,
      productName: null,
      shortName: null,
      tagline: null,
      colors: EMPTY_COLORS,
      poweredBy: null,
      showUpdateCheck: null,
    };
    const error = await updateBranding(update).catch((e: unknown) => e);
    expect(brandingErrorInfo(error)).toEqual({ status: 409, message: "stale", violations: [], currentRevision: 9 });

    server.use(
      http.put("*/administration/branding", () =>
        HttpResponse.json(
          { error: "invalid", violations: [{ field: "colors.primary", message: "bad" }, { junk: true }] },
          { status: 400 },
        ),
      ),
    );
    const invalid = await updateBranding(update).catch((e: unknown) => e);
    expect(brandingErrorInfo(invalid)?.violations).toEqual([{ field: "colors.primary", message: "bad" }]);
    expect(brandingErrorInfo(new Error("not an API error"))).toBeNull();
  });

  it("reads the mocked view", async () => {
    const view = await getBranding();
    expect(view).toMatchObject({ locked: false, source: "default", effective: { productName: "EDDI", revision: 0 } });
  });
});
