// stat-card.tsx — KPI stat card for analytics dashboard

interface StatCardProps {
  label: string;
  value: string | number;
  subtitle?: string;
  icon: React.ComponentType<{ className?: string }>;
  delay?: number;
}

function StatCard({ label, value, subtitle, icon: Icon, delay = 0 }: StatCardProps) {

  return (
    <div
      className="rounded-xl border border-border bg-card p-5 br-card-premium br-card-enter"
      style={{ "--enter-delay": `${delay}ms` } as React.CSSProperties}
    >
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="text-xs text-muted-foreground uppercase tracking-wider">
            {label}
          </p>
          <p className="mt-1 text-2xl font-bold">{value}</p>
          {subtitle && (
            <p className="mt-1 text-sm text-muted-foreground">{subtitle}</p>
          )}
        </div>
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted">
          <Icon className="h-5 w-5 text-muted-foreground" />
        </div>
      </div>
    </div>
  );
}

export { StatCard };
export type { StatCardProps };
