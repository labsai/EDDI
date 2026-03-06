import { useTranslation } from "react-i18next";
import { LayoutDashboard } from "lucide-react";

export function DashboardPage() {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <LayoutDashboard className="h-8 w-8 text-primary" />
          {t("pages.dashboard.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.dashboard.subtitle")}
        </p>
      </div>

      {/* Stats cards placeholder */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[
          { label: "Active Bots", value: "—", color: "bg-primary/10 text-primary" },
          { label: "Conversations Today", value: "—", color: "bg-accent/10 text-accent" },
          { label: "Avg Response Time", value: "—", color: "bg-emerald-500/10 text-emerald-600" },
          { label: "Total Cost", value: "—", color: "bg-amber-500/10 text-amber-600" },
        ].map((stat) => (
          <div
            key={stat.label}
            className="rounded-xl border border-border bg-card p-6 shadow-sm transition-shadow hover:shadow-md"
          >
            <p className="text-sm font-medium text-muted-foreground">
              {stat.label}
            </p>
            <p className={`mt-2 text-3xl font-bold ${stat.color} inline-block rounded-lg px-2 py-1`}>
              {stat.value}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
