import { completeHandoff } from "@eddi/app-kit/lib/workforce-handoffs";
import { useGroupStreamStore, whenStreamAccepted } from "@eddi/app-kit/hooks/use-group-discussion-stream";

/**
 * Link a hand-off to the team session its question started (T2.2), once the
 * stream names the conversation.
 *
 * Bound to the one start attempt it is called for: it waits for THAT start to
 * be accepted (a refused start links nothing), then reads the conversation id
 * from that group's slot of the stream store — not from whatever board is on
 * screen, so switching boards neither links the wrong session nor drops the
 * link. It runs outside React, so leaving the page does not lose it either.
 * Gives up when that stream ends without naming a conversation.
 */
export function linkTeamHandoffWhenNamed(
  principal: string | null | undefined,
  handoffId: string,
  groupId: string,
  accepted: Promise<boolean> = whenStreamAccepted(groupId),
): Promise<boolean> {
  return accepted.then(
    (ok) =>
      ok &&
      new Promise<boolean>((resolve) => {
        const check = (): boolean => {
          const stream = useGroupStreamStore.getState().streams[groupId];
          if (stream?.conversationId) {
            completeHandoff(principal, handoffId, stream.conversationId);
            resolve(true);
            return true;
          }
          if (!stream?.isStreaming) {
            resolve(false);
            return true;
          }
          return false;
        };
        if (check()) return;
        const unsubscribe = useGroupStreamStore.subscribe(() => {
          if (check()) unsubscribe();
        });
      }),
    () => false,
  );
}
