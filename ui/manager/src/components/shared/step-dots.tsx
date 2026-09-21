import { useTranslation } from "react-i18next";

interface StepDotsProps {
  /** How many steps the wizard has. */
  total: number;
  /** Zero-based index of the step being shown. */
  current: number;
  testId?: string;
}

/**
 * Wizard progress dots.
 *
 * Extracted because two dialogs had drawn the same eight lines of markup
 * independently, and neither told a screen reader anything at all — the dots
 * are decorative shapes with no text. The position now goes out as a `status`
 * with a real sentence, so "Step 2 of 3" is announced when the step changes,
 * and the dots themselves are hidden from the accessibility tree rather than
 * being read as stray empty elements.
 */
export function StepDots({ total, current, testId = "step-dots" }: StepDotsProps) {
  const { t } = useTranslation();

  return (
    <div
      className="mb-4 flex items-center justify-center gap-2 py-2"
      role="status"
      aria-live="polite"
      data-testid={testId}
    >
      <span className="sr-only">
        {t("common.stepOf", "Step {{current}} of {{total}}", {
          current: current + 1,
          total,
        })}
      </span>
      {Array.from({ length: total }).map((_, index) => (
        <div key={index} className="flex items-center gap-2" aria-hidden="true">
          <div
            className={`h-2 w-2 rounded-full transition-colors ${
              index <= current ? "bg-primary" : "bg-muted"
            }`}
          />
          {index < total - 1 && (
            <div
              className={`h-px w-8 transition-colors ${
                index < current ? "bg-primary" : "bg-muted"
              }`}
            />
          )}
        </div>
      ))}
    </div>
  );
}
