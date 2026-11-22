import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, CheckCircle2, CircleHelp, Loader2, MailCheck, XCircle } from "lucide-react";
import { Button } from "@eddi/ui";
import { getErrorMessage, isApiError } from "@eddi/app-kit/lib/api-client";
import {
  getEmailSelfTest,
  startEmailSelfTest,
  type EmailSelfTestFinding,
  type EmailSelfTestResult,
} from "@/lib/api/channels";
import { selfTestFindingTitle } from "@/lib/email-channel-messages";

/** How often a waiting test is asked again. */
export const SELF_TEST_POLL_MS = 10_000;

const LEVEL_STYLE: Record<EmailSelfTestFinding["level"], { icon: typeof CheckCircle2; className: string }> = {
  ok: { icon: CheckCircle2, className: "text-emerald-600 dark:text-emerald-400" },
  warning: { icon: AlertTriangle, className: "text-warning" },
  problem: { icon: XCircle, className: "text-destructive" },
  inconclusive: { icon: CircleHelp, className: "text-muted-foreground" },
};

/**
 * The sending-health self-test of a saved email integration: one mail from the
 * mailbox to itself through its SMTP relay, read back by the next poll, and
 * what it showed — DKIM signing and alignment, the S/MIME signature, DMARC.
 * The test mail never starts an agent turn. Five tests per hour at most.
 *
 * Runs against the SAVED integration, so unsaved edits are pointed out rather
 * than silently ignored.
 */
export function EmailSelfTestPanel({
  integrationId,
  hasSmtp,
  dirty = false,
}: {
  integrationId: string;
  /** Whether the saved integration has an SMTP relay at all. */
  hasSmtp: boolean;
  /** Whether the form holds unsaved changes. */
  dirty?: boolean;
}) {
  const { t } = useTranslation();
  const [result, setResult] = useState<EmailSelfTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const tokenRef = useRef<string | null>(null);
  /** The newest poll request; an older response must not overwrite a newer result. */
  const pollSeq = useRef({ latest: 0 });

  const start = useCallback(async () => {
    setStarting(true);
    setError(null);
    setResult(null);
    tokenRef.current = null;
    try {
      const started = await startEmailSelfTest(integrationId);
      tokenRef.current = started.token ?? null;
      setResult(started);
    } catch (e) {
      setError(
        isApiError(e) && e.status === 429
          ? t("emailChannel.selfTest.rateLimited", "At most 5 self-tests per hour. Try again later.")
          : getErrorMessage(e),
      );
    } finally {
      setStarting(false);
    }
  }, [integrationId, t]);

  // Ask again while the test mail has not come back, until it closes.
  const waiting = result?.status === "waiting";
  useEffect(() => {
    if (!waiting || !tokenRef.current) return;
    const token = tokenRef.current;
    const seq = pollSeq.current;
    // Polling goes on until the server says the test is over: past its expiry the
    // server answers "expired", which is what ends the wait. A stopped timer would
    // leave the panel waiting, and the button disabled, for good.
    const timer = setInterval(() => {
      const mine = ++seq.latest;
      getEmailSelfTest(integrationId, token)
        .then((next) => {
          if (mine === seq.latest) setResult((prev) => ({ ...next, token: prev?.token }));
        })
        .catch((e: unknown) => {
          if (mine === seq.latest) setError(getErrorMessage(e));
        });
    }, SELF_TEST_POLL_MS);
    return () => {
      clearInterval(timer);
      seq.latest++; // a response still in flight belongs to a poll that no longer exists
    };
  }, [waiting, integrationId]);

  return (
    <div className="space-y-3" data-testid="email-self-test">
      <p className="text-xs text-muted-foreground">
        {t(
          "emailChannel.selfTest.hint",
          "Sends one short mail from the mailbox to itself through its SMTP relay, reads it back on the next poll and says how your provider signs it (DKIM, DMARC) and whether the S/MIME signature verifies. It never starts an agent conversation.",
        )}
      </p>
      {!hasSmtp && (
        <p className="text-xs text-warning" data-testid="email-self-test-no-smtp">
          {t("emailChannel.selfTest.noSmtp", "Set an SMTP host and save first: the test is sent through it.")}
        </p>
      )}
      {dirty && (
        <p className="text-xs text-warning" data-testid="email-self-test-dirty">
          {t("emailChannel.selfTest.unsaved", "The test uses the saved settings; save your changes first to test them.")}
        </p>
      )}
      <Button
        variant="outline"
        size="sm"
        onClick={() => void start()}
        disabled={starting || waiting || !hasSmtp}
        data-testid="email-self-test-start"
      >
        {starting || waiting ? (
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        ) : (
          <MailCheck className="h-4 w-4" aria-hidden="true" />
        )}
        {t("emailChannel.selfTest.run", "Run self-test")}
      </Button>
      {error && (
        <p className="text-xs text-destructive" role="alert" data-testid="email-self-test-error">
          {error}
        </p>
      )}
      {result && (
        <div className="space-y-2" aria-live="polite" data-testid="email-self-test-result" data-status={result.status}>
          <p className="text-xs font-medium" data-testid="email-self-test-status">
            {result.status === "waiting"
              ? t("emailChannel.selfTest.waiting", "Test mail sent. Waiting for the next mailbox poll to read it back…")
              : result.status === "done"
                ? t("emailChannel.selfTest.done", "The test mail came back.")
                : result.status === "expired"
                  ? t("emailChannel.selfTest.expired", "The test mail did not come back within 15 minutes.")
                  : t("emailChannel.selfTest.sendFailed", "The test mail could not be sent.")}
            {result.signed !== undefined && result.status !== "send_failed" && (
              <span className="ms-2 text-muted-foreground" data-testid="email-self-test-signed">
                {result.signed
                  ? t("emailChannel.selfTest.sentSigned", "(sent S/MIME-signed)")
                  : t("emailChannel.selfTest.sentUnsigned", "(sent unsigned)")}
              </span>
            )}
          </p>
          {(result.findings ?? []).length > 0 && (
            <ul className="space-y-2">
              {(result.findings ?? []).map((finding) => {
                const style = LEVEL_STYLE[finding.level] ?? LEVEL_STYLE.inconclusive;
                const Icon = style.icon;
                const title = selfTestFindingTitle(finding.code, t);
                return (
                  <li
                    key={finding.code}
                    className="flex items-start gap-2 text-xs"
                    data-testid={`email-self-test-finding-${finding.code}`}
                    data-level={finding.level}
                  >
                    <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${style.className}`} aria-hidden="true" />
                    <span>
                      {title && <span className="block font-medium">{title}</span>}
                      <span className="block text-muted-foreground">{finding.text}</span>
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
