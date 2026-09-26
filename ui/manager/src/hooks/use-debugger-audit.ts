import { useEffect, useRef } from "react";
import { useQuery, useQueryClient, type Query } from "@tanstack/react-query";
import { useDebugStore } from "@/hooks/use-debug-events";
import {
  getWholeAuditTrail,
  refreshAuditTrail,
  type AuditTrailResult,
} from "@/lib/audit-pages";

/**
 * The audit ledger is written asynchronously (AuditLedgerService flushes every
 * few seconds), so a read made the instant a turn ends usually misses it. The
 * debugger reads once at the end of a turn and again after this delay.
 */
export const AUDIT_FLUSH_DELAY_MS = 4_000;

export const debuggerAuditKey = (conversationId: string | null) =>
  ["audit", "debugger-trail", conversationId] as const;

/**
 * One audit trail per conversation, shared by the pipeline trace and the cost
 * dashboard.
 *
 * Each of them used to walk the whole trail on its own, and the pipeline trace
 * did it again after every turn. The first read now walks the trail once; later
 * reads fetch only the newest page and merge it (see refreshAuditTrail).
 */
export function useDebuggerAudit(
  conversationId: string | null,
  refetchInterval?:
    | number
    | false
    | ((query: Query<AuditTrailResult, Error, AuditTrailResult, readonly unknown[]>) => number | false | undefined)
) {
  const qc = useQueryClient();
  const key = debuggerAuditKey(conversationId);
  const turnsCount = useDebugStore((s) => s.turns.length);

  const query = useQuery({
    queryKey: key,
    queryFn: () => {
      const previous = qc.getQueryData<AuditTrailResult>(key);
      return previous
        ? refreshAuditTrail(conversationId!, previous)
        : getWholeAuditTrail(conversationId!);
    },
    enabled: !!conversationId,
    staleTime: 10_000,
    refetchInterval,
  });

  // A finished turn wrote new entries: read now, and again once the ledger
  // has flushed them.
  const lastTurns = useRef(turnsCount);
  useEffect(() => {
    if (lastTurns.current === turnsCount || !conversationId) {
      lastTurns.current = turnsCount;
      return;
    }
    lastTurns.current = turnsCount;
    const k = debuggerAuditKey(conversationId);
    void qc.invalidateQueries({ queryKey: k });
    const timer = setTimeout(
      () => void qc.invalidateQueries({ queryKey: k }),
      AUDIT_FLUSH_DELAY_MS
    );
    return () => clearTimeout(timer);
  }, [turnsCount, conversationId, qc]);

  return query;
}
