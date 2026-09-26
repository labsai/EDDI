import { describe, expect, it } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Toaster } from "sonner";
import { server } from "@/test/mocks/server";
import { renderPage, renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelDetailPage } from "@/pages/channel-detail";
import { CreateChannelDialog } from "@/components/channels/create-channel-dialog";
import { getEnrichedChannelDescriptors } from "@/lib/api/channels";
import { isEmptyOrReference, plaintextSecretFields } from "@/lib/channel-secrets";

/**
 * Channel credentials: accepted in plaintext, shown in clear, and a draft
 * that a background refetch could overwrite mid-edit.
 */

const CHANNEL = {
  name: "Legacy Slack",
  channelType: "slack",
  platformConfig: {
    channelId: "C0LEGACY",
    botToken: "xoxb-plaintext-legacy-token",
    signingSecret: "${vault:slack-signing-secret}",
  },
  targets: [
    { name: "default", type: "AGENT", targetId: "agent1", triggers: [], observeMode: false, observeConfig: null },
  ],
  defaultTargetName: "default",
};

function renderDetail() {
  return renderPage(
    "/manage/channels/ch-legacy?version=1",
    <>
      <Toaster />
      <ChannelDetailPage />
    </>,
    "/manage/channels/:id",
  );
}

describe("channel credentials are references only", () => {
  it("masks a plaintext token loaded from an older channel, and refuses to save it", async () => {
    const puts: unknown[] = [];
    server.use(
      http.get("*/channelstore/channels/ch-legacy", () => HttpResponse.json(CHANNEL)),
      http.put("*/channelstore/channels/:id", async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json({ location: "/channelstore/channels/ch-legacy?version=2" });
      }),
    );
    renderDetail();
    const user = userEvent.setup();

    const tokenInput = await screen.findByTestId("channel-bot-token-input");
    expect(tokenInput).toHaveAttribute("type", "password");

    await user.click(screen.getByTestId("save-channel-btn"));
    expect(await screen.findByText(/plaintext credentials are not saved/)).toBeInTheDocument();
    expect(puts).toEqual([]);
  });

  it("saves once the token is a vault reference", async () => {
    const puts: { platformConfig: Record<string, string> }[] = [];
    server.use(
      http.get("*/channelstore/channels/ch-legacy", () => HttpResponse.json(CHANNEL)),
      http.put("*/channelstore/channels/:id", async ({ request }) => {
        puts.push((await request.json()) as { platformConfig: Record<string, string> });
        return HttpResponse.json({ location: "/channelstore/channels/ch-legacy?version=2" });
      }),
    );
    renderDetail();
    const user = userEvent.setup();

    const tokenInput = await screen.findByTestId("channel-bot-token-input");
    await user.clear(tokenInput);
    // `{{` is userEvent's escape for a literal brace.
    await user.type(tokenInput, "${{vault:slack-bot-token}");
    await user.click(screen.getByTestId("save-channel-btn"));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]!.platformConfig.botToken).toBe("${vault:slack-bot-token}");
  });

  it("will not create a channel with a plaintext credential", async () => {
    renderWithProviders(<CreateChannelDialog open onOpenChange={() => {}} />);
    const user = userEvent.setup();
    await user.type(screen.getByTestId("create-channel-name"), "New channel");
    await user.click(screen.getByTestId("create-channel-next"));

    await user.type(await screen.findByTestId("create-channel-id"), "C0NEW");
    const next = screen.getByTestId("create-channel-next");
    expect(next).toBeEnabled();

    await user.type(screen.getByTestId("create-channel-bot-token-input"), "xoxb-pasted");
    expect(next).toBeDisabled();
    expect(screen.getByTestId("create-channel-bot-token-literal-warning")).toBeInTheDocument();
  });
});

describe("channel detail draft", () => {
  it("keeps an in-progress edit when the channel is refetched", async () => {
    let reads = 0;
    server.use(
      http.get("*/channelstore/channels/ch-legacy", () => {
        reads++;
        // A later read differs (a colleague touched the channel): TanStack's
        // structural sharing hands the page a new object only then, which is
        // when the old effect overwrote the draft.
        return HttpResponse.json({
          ...CHANNEL,
          name: reads > 1 ? "Changed elsewhere" : CHANNEL.name,
          platformConfig: { ...CHANNEL.platformConfig, botToken: "${vault:t}" },
        });
      }),
    );
    const { queryClient } = renderDetail();
    const user = userEvent.setup();

    const nameInput = await screen.findByTestId("channel-name-input");
    await user.clear(nameInput);
    await user.type(nameInput, "Renamed, not saved");

    const before = reads;
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ["channels"] });
    });
    await waitFor(() => expect(reads).toBeGreaterThan(before));
    expect(screen.getByTestId("channel-name-input")).toHaveValue("Renamed, not saved");
  });
});

describe("channel list", () => {
  it("caches no credential from the configs it reads for the list", async () => {
    // The list reads each channel's config for its type, target count and
    // channel id; only those projected fields may end up in the query cache.
    const rows = await getEnrichedChannelDescriptors();
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row).not.toHaveProperty("platformConfig");
      expect(JSON.stringify(row)).not.toMatch(/botToken|signingSecret|\$\{vault:/);
    }
  });
});

describe("isEmptyOrReference / plaintextSecretFields", () => {
  it("accepts only an empty value or a braced vault reference", () => {
    expect(isEmptyOrReference("")).toBe(true);
    expect(isEmptyOrReference("  ")).toBe(true);
    expect(isEmptyOrReference("${vault:k}")).toBe(true);
    expect(isEmptyOrReference("${vault:tenant/k}")).toBe(true);
    // The router resolves vault references only.
    expect(isEmptyOrReference("${vars:k}")).toBe(false);
    expect(isEmptyOrReference("vault:k")).toBe(false);
    expect(isEmptyOrReference("xoxb-123")).toBe(false);
    expect(plaintextSecretFields({ botToken: "xoxb", signingSecret: "${vault:s}", channelId: "C1" })).toEqual([
      "botToken",
    ]);
  });
});
