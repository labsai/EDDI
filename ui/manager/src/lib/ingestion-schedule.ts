import type { TFunction } from "i18next";

/**
 * The schedules most ingestion sources want, so a cron has to be typed only for
 * the rest. Ingestion schedules run in UTC (`RagIngestionSchedules.ZONE`), so the
 * labels say so.
 */
export const CRON_PRESETS: { cron: string; labelKey: string; fallback: string }[] = [
  { cron: "", labelKey: "ragEditor.schedule.presetManual", fallback: "Only when run by hand" },
  { cron: "0 * * * *", labelKey: "ragEditor.schedule.presetHourly", fallback: "Every hour" },
  { cron: "0 2 * * *", labelKey: "ragEditor.schedule.presetDaily", fallback: "Every day at 02:00 UTC" },
  { cron: "0 2 * * 1", labelKey: "ragEditor.schedule.presetWeekly", fallback: "Every Monday at 02:00 UTC" },
  {
    cron: "0 2 1 * *",
    labelKey: "ragEditor.schedule.presetMonthly",
    fallback: "On the 1st of each month at 02:00 UTC",
  },
];

/** A cron in words when it is one of the presets, otherwise the expression itself. */
export function describeCron(t: TFunction, cron: string | undefined): string {
  const preset = CRON_PRESETS.find((p) => p.cron === (cron ?? "").trim());
  return preset ? t(preset.labelKey, preset.fallback) : (cron ?? "");
}
