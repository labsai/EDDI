import type { Bet } from "@eddi/app-kit/lib/api/groups";

/** Whether a bet is due: its date has arrived and nobody has given a verdict (mirrors backend `Bet.dueAt`). */
export function isBetDue(bet: Bet, now: number = Date.now()): boolean {
  return !bet.reviewedAt && new Date(bet.reviewDate).getTime() <= now;
}
