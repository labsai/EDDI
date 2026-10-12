import type { TFunction } from "i18next";
import { getErrorMessage } from "@/lib/api-client";
import { GroupMembersDeleteError } from "@/lib/api/groups";

/**
 * The reader-facing reason a "delete group + members" failed.
 *
 * A {@link GroupMembersDeleteError} says which members could not be deleted and
 * — the part that matters for what to do next — whether the group itself is
 * gone (soft path: deleted, recoverable) or kept (permanent path: the group is
 * only purged once every member is gone, so a retry finishes the job). Anything else is the backend's own message.
 */
export function describeGroupDeleteError(err: unknown, t: TFunction): string {
  if (err instanceof GroupMembersDeleteError) {
    const ids = err.failedAgentIds.join(", ");
    return err.groupDeleted
      ? t(
          "groups.deleteMembersFailedAfterGroup",
          "The group was deleted, but these member agents could not be: {{ids}}. Delete them from the Agents page.",
          { ids },
        )
      : t(
          "groups.deleteMembersFailedGroupKept",
          "The group was kept: these member agents could not be deleted: {{ids}}. Try again to finish.",
          { ids },
        );
  }
  return getErrorMessage(err);
}
