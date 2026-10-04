import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
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
