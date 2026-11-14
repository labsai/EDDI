import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { useSearchParams } from "react-router-dom";
import { server } from "@/test/mocks/server";
import { renderPage, userEvent } from "@/test/test-utils";
import { ChannelDetailPage } from "@/pages/channel-detail";

function renderDetail() {
  return renderPage("/manage/channels/ch1?version=1", <ChannelDetailPage />, "/manage/channels/:id");
}

function beforeUnload() {
  const ev = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(ev);
  return ev.defaultPrevented;
}

describe("ChannelDetailPage - unsaved changes and labels", () => {
  it("guards against leaving only once the draft differs from the stored channel", async () => {
    renderDetail();
    const name = await screen.findByTestId("channel-name-input");
    // Freshly loaded: nothing to lose.
    expect(beforeUnload()).toBe(false);

    await userEvent.setup().type(name, " edited");
    await waitFor(() => expect(beforeUnload()).toBe(true));
  });

  it("is clean again when an edit is reverted by hand", async () => {
    renderDetail();
    const name = (await screen.findByTestId("channel-name-input")) as HTMLInputElement;
    const original = name.value;
    fireEvent.change(name, { target: { value: original + "x" } });
    await waitFor(() => expect(beforeUnload()).toBe(true));
    fireEvent.change(name, { target: { value: original } });
    await waitFor(() => expect(beforeUnload()).toBe(false));
  });

  it("ties the General and Platform labels to their inputs", async () => {
    renderDetail();
    await screen.findByTestId("channel-name-input");
    expect(screen.getByLabelText("Channel Type")).toBe(screen.getByTestId("channel-type-select"));
    expect(screen.getByLabelText("Slack Channel ID")).toBe(screen.getByTestId("channel-id-input"));
    expect(screen.getByLabelText("Approval Channel ID")).toBe(
      screen.getByTestId("hitl-approval-channel-input"),
    );
    expect(screen.getByLabelText("Approver User IDs")).toBe(
      screen.getByTestId("hitl-approver-ids-input"),
    );
    // "Name" appears for the channel and for each target card.
    expect(screen.getAllByLabelText("Name").length).toBeGreaterThanOrEqual(2);
  });
});

function WithVersionJump() {
  const [, setSearchParams] = useSearchParams();
  return (
    <>
      <button type="button" data-testid="go-v3" onClick={() => setSearchParams({ version: "3" })} />
      <ChannelDetailPage />
    </>
  );
}

describe("ChannelDetailPage - edits made while a save is in flight", () => {
  /** A channel store that keeps what was PUT and lets the test decide when the PUT answers. */
  function storeWithGatedPut() {
    const stored: Record<string, Record<string, unknown>> = {};
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.get("*/channelstore/channels/:id", ({ request }) => {
        const body = stored[new URL(request.url).searchParams.get("version") ?? "1"];
        // Version 1 falls through to the default handler's ch1 fixture.
        return body ? HttpResponse.json(body) : undefined;
      }),
      http.put("*/channelstore/channels/:id", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        stored["2"] = sent;
        await gate;
        return HttpResponse.json({ location: "/channelstore/channels/ch1?version=2" });
      }),
    );
    return { release: () => release(), sent: () => sent };
  }

  it("keeps what was typed during the PUT and stays dirty", async () => {
    const store = storeWithGatedPut();
    renderDetail();
    const user = userEvent.setup();
    const name = await screen.findByTestId("channel-name-input");
    await user.type(name, " one");
    await user.click(screen.getByTestId("save-channel-btn"));
    await waitFor(() => expect(store.sent()).not.toBeNull());
    expect(store.sent()?.name).toBe("Engineering Slack one");

    await user.type(name, " two");
    store.release();

    await waitFor(() => expect(screen.getByTestId("save-channel-btn")).not.toBeDisabled());
    // Let the move to version 2 and its fetch settle.
    await new Promise((r) => setTimeout(r, 150));
    expect(screen.getByTestId("channel-name-input")).toHaveValue("Engineering Slack one two");
    expect(beforeUnload()).toBe(true);
  });

  it("is clean and shows the saved values when nothing was typed during the PUT", async () => {
    const store = storeWithGatedPut();
    renderDetail();
    const user = userEvent.setup();
    const name = await screen.findByTestId("channel-name-input");
    await user.type(name, " one");
    await user.click(screen.getByTestId("save-channel-btn"));
    await waitFor(() => expect(store.sent()).not.toBeNull());
    store.release();

    await waitFor(() => expect(screen.getByTestId("save-channel-btn")).not.toBeDisabled());
    await new Promise((r) => setTimeout(r, 150));
    expect(screen.getByTestId("channel-name-input")).toHaveValue("Engineering Slack one");
    expect(beforeUnload()).toBe(false);
  });

  it("still seeds from the stored channel when the route moves to another version", async () => {
    server.use(
      http.get("*/channelstore/channels/:id", ({ request }) => {
        if (new URL(request.url).searchParams.get("version") !== "3") return undefined;
        return HttpResponse.json({
          name: "Version Three",
          channelType: "slack",
          platformConfig: { channelId: "C333" },
          targets: [{ name: "default", type: "AGENT", targetId: "agent1", triggers: [], observeMode: false, observeConfig: null }],
        });
      }),
    );
    renderPage("/manage/channels/ch1?version=1", <WithVersionJump />, "/manage/channels/:id");
    const user = userEvent.setup();
    await user.type(await screen.findByTestId("channel-name-input"), " typed");
    expect(beforeUnload()).toBe(true);

    await user.click(screen.getByTestId("go-v3"));
    await waitFor(() => expect(screen.getByTestId("channel-name-input")).toHaveValue("Version Three"));
    expect(beforeUnload()).toBe(false);
  });
});
