import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Globe } from "lucide-react";
import { ChipInput } from "@/components/shared/chip-input";
import { ValidationMessage } from "@/components/connections/validation-message";
import { validateOrigin, type ValidationCode } from "@/lib/connection-validation";

interface OriginAllowlistFieldProps {
  value: string[];
  onChange: (origins: string[]) => void;
  /** Uncommitted text, owned by the form so a save can never drop it. */
  pending: string;
  onPendingChange: (pending: string) => void;
  readOnly?: boolean;
  /** The whole-field error from the form's validation pass, if any. */
  error?: ValidationCode;
  testId?: string;
}

/**
 * `baseUrlAllowlist` — the origins this connection's credential may be sent to.
 *
 * Required and non-empty, which is the point of the whole field: without it a
 * config edit could redirect a live Google token to somebody else's host. It is
 * a *list* because one provider's credential legitimately spans hosts
 * (`googleapis.com`, `drive.googleapis.com`, …).
 *
 * Entries are checked as they are added rather than on save. The rule —
 * `scheme://host[:port]`, nothing else — is the kind that looks satisfied when
 * it is not: `api.example.com` with no scheme parses as a path, matches nothing
 * at resolve time, and produces an allowlist that appears correct and blocks
 * every request.
 */
export function OriginAllowlistField({
  value,
  onChange,
  pending,
  onPendingChange,
  readOnly,
  error,
  testId = "origin-allowlist",
}: OriginAllowlistFieldProps) {
  const { t } = useTranslation();
  const [draftError, setDraftError] = useState<ValidationCode | undefined>();
  const errorId = useId();

  return (
    <div className="space-y-2">
      <ChipInput
        values={value}
        onChange={onChange}
        pending={pending}
        onPendingChange={onPendingChange}
        validate={(candidate) => validateOrigin(candidate)}
        onInvalid={(code) => setDraftError((code as ValidationCode) ?? undefined)}
        placeholder="https://api.example.com"
        inputLabel={t("connections.addOrigin", "Add an allowed origin")}
        itemIcon={Globe}
        readOnly={readOnly}
        invalid={draftError !== undefined || error !== undefined}
        errorId={errorId}
        testId={testId}
        ltr
      />
      {/* The draft's own problem takes precedence: it is about the text still in
          the box, which is what the user is looking at. */}
      <ValidationMessage
        code={draftError ?? error}
        id={errorId}
        testId={`${testId}-error`}
      />
    </div>
  );
}
