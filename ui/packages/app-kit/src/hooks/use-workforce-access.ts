import { useMayUse } from "./use-auth";
import { ROLE_ADMIN, ROLE_EDITOR } from "../lib/roles";

/**
 * What the signed-in person may do in Workforce.
 *
 * - `canAuthor` (`eddi-editor` or `eddi-admin`): build and change teams — the
 *   wizard, team settings, analytics — and read the authoring stores
 *   (`/groupstore`, `/agentstore`), which refuse everyone else.
 * - `isAdmin` (`eddi-admin`): additionally sees the link to the Platform
 *   Operator in the Manager.
 *
 * Everyone else (`eddi-user`) gets the end-user Workforce: what the catalog
 * lists for them (`/catalog/agents`, `/catalog/groups`), their discussions and
 * chats, and nothing that would answer 403.
 *
 * Both are true with auth disabled, and when the token carries no EDDI role at
 * all (roles mapped from another claim — see `rolesAreKnown`): the Manager then
 * offers everything and the backend decides, as everywhere else.
 */
export function useWorkforceAccess(): { canAuthor: boolean; isAdmin: boolean } {
  const may = useMayUse();
  return { canAuthor: may([ROLE_ADMIN, ROLE_EDITOR]), isAdmin: may([ROLE_ADMIN]) };
}
