import { useTranslation } from "react-i18next";
import { LogOut, UserRound } from "lucide-react";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@eddi/app-kit/components/ui/dropdown-menu";
import { userDisplayName, userInitials, userSecondaryEmail } from "@eddi/app-kit/lib/user-display";

/**
 * Who is signed in, and the way out.
 *
 * Workforce is the app a shared-device end user opens, and it had no sign-out,
 * no menu and no identity: the only logout lived in the Manager's sidebar, so
 * the session could not be ended from here. The same shared session code the
 * Manager uses, so signing out lands back in Workforce and clears this person's
 * client storage (pins, hand-offs, thread pointers).
 *
 * Nothing is rendered with authentication off (no session to end, no one to
 * name): `method === "none"`, or the guest context.
 */
export function WorkforceUserMenu() {
  const { t } = useTranslation();
  const { method, user, logout } = useAuth();
  if (method === "none") return null;

  const displayName = user ? userDisplayName(user) : "";
  const initials = user ? userInitials(user) : "";
  const secondaryEmail = user ? userSecondaryEmail(user) : "";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          data-testid="user-menu-trigger"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground transition-opacity hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card"
          title={displayName || t("auth.signedIn", "Signed in")}
          aria-label={t("auth.userMenu", "User menu")}
        >
          {initials ? (
            <span data-testid="user-menu-initials">{initials}</span>
          ) : (
            <UserRound className="h-4 w-4" aria-hidden="true" data-testid="user-menu-avatar-icon" />
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-56 bg-card"
        data-testid="user-menu-dropdown"
        aria-label={t("auth.userMenu", "User menu")}
      >
        <div className="border-b border-border px-3 py-2.5">
          <p className="truncate text-sm font-medium text-foreground" title={displayName || undefined}>
            {displayName || t("auth.signedIn", "Signed in")}
          </p>
          {secondaryEmail && (
            <p className="truncate text-xs text-muted-foreground" title={secondaryEmail}>
              {secondaryEmail}
            </p>
          )}
        </div>
        <div className="py-1">
          <DropdownMenuItem
            onSelect={() => logout()}
            data-testid="user-menu-logout"
            className="px-3 py-2 text-foreground"
          >
            <LogOut className="h-4 w-4" aria-hidden="true" />
            {t("auth.logout", "Logout")}
          </DropdownMenuItem>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
