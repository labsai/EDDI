import { levelIncludes, type AccessLevel } from "@/lib/api/sharing";
import { parseResourceUri } from "@/lib/api/agents";

/**
 * What the signed-in user may do with one listed resource.
 *
 * <h3>Why this is not the same question as "are workspaces on"</h3> The
 * enforcement flag says whether anything is restricted at all; `callerLevel`
 * says what *this* row permits. A listing can contain both an agent you own and
 * one a colleague shared with you at `USE`, and before the backend reported the
 * level there was no way to tell them apart — the grant list is disclosed at
 * `OWN` only. So the page offered every action on every row and the user
 * discovered the truth from a 403, which reads as the product being broken
 * rather than as the resource not being theirs.
 *
 * <h3>Absent means unrestricted, deliberately</h3> The field is omitted when
 * enforcement is off and on any backend that predates it. Treating that as "no
 * access" would empty the UI on every existing deployment, so absence is read
 * as the state before this feature existed: everything allowed. That is the
 * same direction the rest of the workspace UI degrades in, and it is safe
 * because the *server* enforces regardless of what is drawn here — this only
 * decides which controls are worth offering.
 */
export interface ResourceAccess {
  /** Talk to it. The most common share, and the least revealing. */
  canUse: boolean;
  /** Read its configuration, open its detail page, export a copy. */
  canView: boolean;
  /** Change and deploy it — but not delete it or pass it on. */
  canEdit: boolean;
  /** Delete it, and decide who else may reach it. */
  canOwn: boolean;
  /** Whether the backend actually told us; false means "not restricted". */
  known: boolean;
}

/** Everything permitted — the shape for an unrestricted or pre-workspaces backend. */
const UNRESTRICTED: ResourceAccess = {
  canUse: true,
  canView: true,
  canEdit: true,
  canOwn: true,
  known: false,
};

/**
 * Reads the level the backend stamped on a descriptor.
 *
 * An unrecognised value is treated as unrestricted rather than as no access: a
 * backend that grows a fifth level should not blank out the UI of an older
 * Manager, and the server still refuses anything the caller may not do.
 */
export function accessFor(callerLevel?: string | null): ResourceAccess {
  if (!callerLevel) return UNRESTRICTED;

  const level = callerLevel as AccessLevel;
  if (!levelIncludes(level, "USE")) return UNRESTRICTED;

  return {
    canUse: true,
    canView: levelIncludes(level, "VIEW"),
    canEdit: levelIncludes(level, "EDIT"),
    canOwn: levelIncludes(level, "OWN"),
    known: true,
  };
}

/**
 * The access a detail page may offer, read from the version descriptors it
 * already loads.
 *
 * <h3>Only this resource's descriptors count</h3> The descriptor `filter=` is a
 * text match, so a different resource whose id merely contains this one comes
 * back too — and its `callerLevel` says nothing about this one. Only a
 * descriptor whose resource URI resolves to `id` is consulted.
 *
 * <h3>Nothing is offered before the answer is in</h3> While the descriptors are
 * still loading (`undefined`), every capability is false rather than
 * unrestricted: otherwise an `EDIT` grantee sees Delete flash up and vanish, and
 * can click it in between. Once loaded, the ordinary `accessFor` rules apply —
 * including "absent means unrestricted" for backends without workspaces.
 */
export function accessForDetail(
  descriptors: readonly { resource: string; callerLevel?: string | null }[] | undefined,
  id: string | undefined,
): ResourceAccess {
  if (!descriptors) return PENDING;
  const own = descriptors.find((d) => d.resource && parseResourceUri(d.resource).id === id);
  return accessFor(own?.callerLevel);
}

/** Nothing offered yet — the shape while a detail page's descriptors load. */
const PENDING: ResourceAccess = {
  canUse: false,
  canView: false,
  canEdit: false,
  canOwn: false,
  known: false,
};
