import { useQuery } from "@tanstack/react-query";
import { listMailboxGrants } from "@eddi/app-kit/lib/api/connections";
import { MINE_KEY } from "@eddi/app-kit/hooks/use-connections";

/** The query key of one connection's mailbox grants. */
export const mailboxGrantsKey = (name: string) => [MINE_KEY[0], "mailbox-grants", name] as const;

/**
 * One `MAILBOX` connection's linked mailboxes. `eddi-admin` only: for anyone
 * else the backend answers 403, and the query reports an error rather than
 * retrying.
 */
export function useMailboxGrants(name: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: mailboxGrantsKey(name ?? ""),
    queryFn: () => listMailboxGrants(name!),
    enabled: !!name && enabled,
    retry: false,
  });
}
