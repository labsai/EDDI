import { useTranslation } from "react-i18next";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";

const SIZE_CLASSES = {
  xs: "h-6 w-6 text-[10px]",
  sm: "h-8 w-8 text-xs",
  md: "h-10 w-10 text-sm",
  lg: "h-12 w-12 text-base",
  xl: "h-16 w-16 text-lg",
} as const;

interface AdvisorAvatarProps {
  name: string;
  agentId: string;
  size?: "xs" | "sm" | "md" | "lg" | "xl";
  role?: string | null;
  showRole?: boolean;
  className?: string;
}

function AdvisorAvatar({
  name,
  agentId,
  size = "md",
  role,
  showRole = false,
  className,
}: AdvisorAvatarProps) {
  const { t } = useTranslation();

  const initials = getInitials(name);
  const bgColor = hashColor(agentId);

  return (
    <div className={cn("flex flex-col items-center gap-1", className)}>
      <div
        role="img"
        className={cn(
          "flex items-center justify-center rounded-full font-medium text-white",
          bgColor,
          SIZE_CLASSES[size],
        )}
        title={name}
        aria-label={t("Workforce.advisor.avatarLabel", "{{name}} avatar", {
          name,
        })}
      >
        {initials || "?"}
      </div>
      {showRole && role && (
        <Badge
          variant="secondary"
          className="max-w-20 truncate text-[10px] leading-tight"
        >
          {role}
        </Badge>
      )}
    </div>
  );
}

export { AdvisorAvatar };
export type { AdvisorAvatarProps };
