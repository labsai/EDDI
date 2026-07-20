import { useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Settings2,
  Users,
  ArrowRight,
  Sparkles,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useTheme } from "@/components/layout/theme-provider";

// ─── Constants ───────────────────────────────────────────────────

const LANDING_PREF_KEY = "eddi-landing-preference";

function getStoredPreference(): string | null {
  try {
    return localStorage.getItem(LANDING_PREF_KEY);
  } catch {
    return null;
  }
}

function storePreference(choice: string) {
  try {
    localStorage.setItem(LANDING_PREF_KEY, choice);
  } catch {
    // localStorage may be unavailable
  }
}

// ─── Mode Card ───────────────────────────────────────────────────

interface ModeCardProps {
  to: string;
  icon: React.ReactNode;
  title: string;
  description: string;
  accent: string;
  accentBg: string;
  features: string[];
  onNavigate: () => void;
}

function ModeCard({
  to,
  icon,
  title,
  description,
  accent,
  accentBg,
  features,
  onNavigate,
}: ModeCardProps) {
  return (
    <Link
      to={to}
      onClick={onNavigate}
      className={cn(
        "group relative flex flex-col rounded-2xl border border-border bg-card overflow-hidden",
        "transition-all duration-200",
        "hover:shadow-xl hover:-translate-y-1",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        "w-full max-w-sm",
      )}
    >
      {/* Top accent */}
      <div className={cn("h-1", accent)} />

      <div className="p-7 flex flex-col flex-1">
        {/* Icon */}
        <div
          className={cn(
            "flex h-12 w-12 items-center justify-center rounded-xl mb-5",
            accentBg,
          )}
        >
          {icon}
        </div>

        {/* Title */}
        <h2 className="text-xl font-bold text-foreground mb-2 group-hover:text-primary transition-colors">
          {title}
        </h2>

        {/* Description */}
        <p className="text-sm text-muted-foreground leading-relaxed mb-5">
          {description}
        </p>

        {/* Features */}
        <ul className="space-y-2 mb-6 flex-1">
          {features.map((f) => (
            <li
              key={f}
              className="flex items-start gap-2 text-sm text-foreground/80"
            >
              <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-primary/50" />
              {f}
            </li>
          ))}
        </ul>

        {/* CTA */}
        <div className="flex items-center gap-1 text-sm font-medium text-primary opacity-70 group-hover:opacity-100 transition-opacity">
          {title}
          <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-1" />
        </div>
      </div>
    </Link>
  );
}

// ─── Landing Page ────────────────────────────────────────────────

export function LandingPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { resolvedTheme } = useTheme();

  // Auto-redirect if user has a stored preference
  useEffect(() => {
    const pref = getStoredPreference();
    if (pref === "manage" || pref === "workforce") {
      navigate(`/${pref}`, { replace: true });
    }
  }, [navigate]);

  return (
    <div
      className={cn(
        "flex min-h-screen flex-col items-center justify-center p-6",
        resolvedTheme === "dark" ? "bg-background" : "bg-muted/30",
      )}
    >
      <div className="w-full max-w-3xl space-y-10">
        {/* ── Header ──────────────────────────────────────────── */}
        <header className="text-center space-y-3">
          <div className="flex items-center justify-center">
            <div className="relative">
              <img
                src="/eddi-icon.svg"
                alt="EDDI"
                className="h-14 w-14 rounded-xl"
              />
              <Sparkles className="absolute -top-1 -end-1 h-4 w-4 text-primary/50" />
            </div>
          </div>

          <h1 className="text-2xl md:text-3xl font-bold text-foreground tracking-tight">
            {t("landing.title", "Welcome to EDDI")}
          </h1>
          <p className="text-muted-foreground text-base max-w-md ms-auto me-auto leading-relaxed">
            {t(
              "landing.subtitle",
              "Choose your workspace to get started.",
            )}
          </p>
        </header>

        {/* ── Cards ───────────────────────────────────────────── */}
        <div className="flex flex-col sm:flex-row items-center sm:items-stretch justify-center gap-5">
          <ModeCard
            to="/manage"
            icon={<Settings2 className="h-6 w-6 text-foreground/70" />}
            title={t("landing.manager.title", "Manager")}
            description={t(
              "landing.manager.description",
              "Build, configure, and deploy your AI agents. Manage workflows, resources, and integrations.",
            )}
            accent="bg-gradient-to-r from-foreground/20 via-foreground/30 to-foreground/20"
            accentBg="bg-muted"
            features={[
              t("landing.manager.feature1", "Create & deploy agents"),
              t("landing.manager.feature2", "Configure workflows & pipelines"),
              t("landing.manager.feature3", "Manage resources & extensions"),
            ]}
            onNavigate={() => storePreference("manage")}
          />

          <ModeCard
            to="/workforce"
            icon={<Users className="h-6 w-6 text-primary" />}
            title={t("landing.workforce.title", "Workforce")}
            description={t(
              "landing.workforce.description",
              "Assemble AI teams that debate, analyze, and solve complex challenges together.",
            )}
            accent="bg-gradient-to-r from-primary/40 via-primary to-primary/40"
            accentBg="bg-primary/10"
            features={[
              t("landing.workforce.feature1", "Assemble agent task forces"),
              t("landing.workforce.feature2", "Run collaborative discussions"),
              t("landing.workforce.feature3", "Get synthesized answers"),
            ]}
            onNavigate={() => storePreference("workforce")}
          />
        </div>

        {/* ── Footer hint ─────────────────────────────────────── */}
        <p className="text-center text-xs text-muted-foreground/60">
          {t(
            "landing.hint",
            "We'll remember your choice and take you there automatically next time.",
          )}
        </p>
      </div>
    </div>
  );
}
