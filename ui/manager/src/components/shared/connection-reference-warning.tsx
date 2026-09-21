import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";
import {
  containsConnectionReference,
  wrapsConnectionReference,
} from "@/lib/secret-reference";

interface ConnectionReferenceWarningProps {
  value: string | null | undefined;
  /**
   * A placement the backend never resolves a connection in. `"request"` is an
   * httpcall path, query parameter or body; `"model"` is a language-model,
   * embedding or vector-store parameter. Each gets the sentence that names
   * the fix for that placement.
   */
  refused?: "request" | "model";
  /**
   * The header name the referenced connection requires, when it differs from
   * the header the value sits under. The caller computes it (see
   * `expectedHeaderFor`), because only it knows the header name.
   */
  expectedHeaderName?: string | null;
  testId?: string;
}

/**
 * The placement and shape rules for `${connection:…}`, said next to the field.
 *
 * Every one of these is refused by the backend at build time with a message
 * of its own; the point of repeating it here is that the message then arrives
 * while the value is still on screen, instead of as a deploy failure naming a
 * field in a config the author closed an hour ago. Nothing renders when the
 * value is fine, so a caller can place this under any field unconditionally.
 */
export function ConnectionReferenceWarning({
  value,
  refused,
  expectedHeaderName,
  testId = "connection-ref-warning",
}: ConnectionReferenceWarningProps) {
  const { t } = useTranslation();
  const text = value ?? "";

  let message: string | null = null;
  if (refused) {
    if (containsConnectionReference(text)) {
      message =
        refused === "request"
          ? t(
              "connectionRef.refusedInRequest",
              "${connection:…} is refused in a path, query parameter or body — a credential in a URL lands in every proxy log, and one in a body is not read. Reference the connection from a header instead.",
            )
          : t(
              "connectionRef.refusedInModel",
              "Language models, embeddings and vector stores do not accept ${connection:…}: a connection resolves to a whole header, and these need a bare credential. Use ${vault:…} here.",
            );
    }
  } else if (wrapsConnectionReference(text)) {
    message = t(
      "connectionRef.wrapped",
      "A connection supplies the whole header value, scheme included. Use ${connection:name} on its own and put “Bearer ” in the connection's header value instead.",
    );
  } else if (expectedHeaderName) {
    message = t("connectionRef.headerMismatch", {
      expected: expectedHeaderName,
      defaultValue:
        "This connection sends its credential as “{{expected}}”, so the header must be named that — otherwise the config shows one name and the request carries another.",
    });
  }

  if (!message) return null;
  return (
    <p
      role="alert"
      className="mt-1 flex items-start gap-1 text-[11px] text-destructive"
      data-testid={testId}
    >
      <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
      <span>{message}</span>
    </p>
  );
}
