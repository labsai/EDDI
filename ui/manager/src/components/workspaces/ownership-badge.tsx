import { useTranslation } from "react-i18next";
import { Globe, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { useSpaces } from "@/hooks/use-spaces";
import { describeSpace } from "@/lib/spaces";

interface OwnershipBadgeProps {
  ownerId?: string;
  spaceId?: string;
  visibility?: "private" | "space" | "published";
  className?: string;
}

/**
 * Says, in one glance, why a resource is in a list that is otherwise "yours".
 *
 * <h3>It shows nothing far more often than it shows something</h3> The common
 * case — your own resource, in your own workspace — needs no badge, and adding
 * one to every row would turn the signal into wallpaper. So this renders only
 * for the two cases a reader would otherwise get wrong: something published to
 * the whole deployment, and something that reached them through someone else.
 *
 * It is presentation only. Access is decided by the backend, which scopes every
 * listing regardless of what is drawn here.
 */
export function OwnershipBadge({ ownerId, spaceId, visibility, className }: OwnershipBadgeProps) {
  const { t } = useTranslation();
  const { enabled, principal } = useSpaces();

  // A deployment that does not enforce workspaces has no ownership story to
  // tell: everyone already sees everything, so a "Shared" badge would label a
  // distinction that does not exist. Ownership is still *recorded* in that
  // state — deliberately, so an operator can let attribution accumulate before
  // switching enforcement on — which is exactly why the fields being present is
  // not enough on its own.
  if (!enabled) return null;

  // No workspace fields at all: data that predates ownership being recorded.
  // Saying "unowned" would invent a status the deployment does not use.
  if (!ownerId && !spaceId && !visibility) return null;

  if (visibility === "published") {
    return (
      <Badge variant="secondary" className={className} data-testid="ownership-badge-published">
        <Globe className="me-1 h-3 w-3" aria-hidden="true" />
        {t("workspaces.badge.published", "Published")}
      </Badge>
    );
  }

  // Compared against the principal the BACKEND reports, not a display name from
  // the token. It is the value stamped as ownerId, and the two are not
  // guaranteed to be the same string.
  const isMine = !!ownerId && !!principal && ownerId === principal;
  if (isMine) return null;

  if (!ownerId) return null;

  const space = describeSpace(spaceId);
  return (
    <Badge
      variant="outline"
      className={className}
      data-testid="ownership-badge-shared"
      title={
        space
          ? t("workspaces.badge.sharedTitle", "Owned by {{owner}} · in {{space}}", { owner: ownerId, space })
          : t("workspaces.badge.ownedBy", "Owned by {{owner}}", { owner: ownerId })
      }
    >
      <Users className="me-1 h-3 w-3" aria-hidden="true" />
      {t("workspaces.badge.shared", "Shared")}
    </Badge>
  );
}
