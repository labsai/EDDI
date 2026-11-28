import { afterEach, describe, expect, it } from "vitest";
import { useGroupStreamStore } from "@eddi/app-kit/hooks/use-group-discussion-stream";
import { addHandoff, clearWorkforceHandoffs, readHandoffs } from "@eddi/app-kit/lib/workforce-handoffs";
import { linkTeamHandoffWhenNamed } from "@/lib/team-handoff-link";

/** T2.2: a team hand-off is linked from its own start's stream, not the board on screen. */
describe("linkTeamHandoffWhenNamed", () => {
  afterEach(() => {
    useGroupStreamStore.getState().resetStream("grp-a");
    useGroupStreamStore.getState().resetStream("grp-b");
    clearWorkforceHandoffs();
  });

  const record = () =>
    addHandoff(null, {
      from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1", afterStep: 1 },
      to: { kind: "team", id: "grp-a", name: "Team A", version: 1, conversationId: null },
      question: "Should we ship?",
    });

  it("waits for its own group's conversation id, ignoring another board's", async () => {
    const h = record();
    const update = useGroupStreamStore.getState().update;
    update("grp-a", (s) => ({ ...s, isStreaming: true }));
    const linked = linkTeamHandoffWhenNamed(null, h.id, "grp-a", Promise.resolve(true));
    await Promise.resolve();
    update("grp-b", (s) => ({ ...s, isStreaming: true, conversationId: "gc-b" }));
    expect(readHandoffs(null)[0]!.to.conversationId).toBeNull();
    update("grp-a", (s) => ({ ...s, conversationId: "gc-a" }));
    await expect(linked).resolves.toBe(true);
    expect(readHandoffs(null)[0]!.to.conversationId).toBe("gc-a");
  });

  it("links nothing for a refused start, or a stream that ended unnamed", async () => {
    const h = record();
    await expect(linkTeamHandoffWhenNamed(null, h.id, "grp-a", Promise.resolve(false))).resolves.toBe(false);
    const update = useGroupStreamStore.getState().update;
    update("grp-a", (s) => ({ ...s, isStreaming: true }));
    const linked = linkTeamHandoffWhenNamed(null, h.id, "grp-a", Promise.resolve(true));
    await Promise.resolve();
    update("grp-a", (s) => ({ ...s, isStreaming: false }));
    await expect(linked).resolves.toBe(false);
    expect(readHandoffs(null)[0]!.to.conversationId).toBeNull();
  });
});
