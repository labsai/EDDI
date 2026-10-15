import { useEffect, useId, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { NumberInput } from "./number-input";
import {
  EDITABLE_KINDS,
  JSON_ONLY_KINDS,
  VALUE_FIELD,
  convertValue,
  effectiveValueKind,
  numericProblem,
  setValueKinds,
  withValue,
  type PropertyValueKind,
  type TypedPropertyValue,
} from "./property-value-utils";

const KIND_LABELS: Record<PropertyValueKind, [string, string]> = {
  string: ["propertyValue.kindString", "Text / template"],
  int: ["propertyValue.kindInt", "Integer"],
  long: ["propertyValue.kindLong", "Long integer"],
  double: ["propertyValue.kindDouble", "Decimal"],
  float: ["propertyValue.kindFloat", "Float (≈7 digits)"],
  boolean: ["propertyValue.kindBoolean", "Boolean"],
  object: ["propertyValue.kindObject", "Object (JSON)"],
  list: ["propertyValue.kindList", "List (JSON)"],
};

/**
 * The value of one property instruction, with its type.
 *
 * A type select in front of the value: text (the template field the row already
 * had, passed in as `renderText`), integer, long, decimal, float or boolean.
 * Choosing a type leaves exactly ONE value slot on the instruction — the engine
 * lets a typed slot overwrite `valueString`, so a row carrying both used to run
 * a value the form did not show. Objects and lists are shown, not edited: the
 * JSON tab is where those are written.
 */
export function PropertyValueField<T extends TypedPropertyValue>({
  value,
  onChange,
  readOnly,
  renderText,
  testIdPrefix = "property-value",
}: {
  value: T;
  onChange: (next: T) => void;
  readOnly?: boolean;
  /** The row's own text/template input, rendered when the type is text. */
  renderText: () => ReactNode;
  testIdPrefix?: string;
}) {
  const { t } = useTranslation();
  const selectId = useId();
  const problemId = useId();
  const effective = effectiveValueKind(value);
  // The chosen type survives an emptied number field: with no value in any slot
  // the effective kind falls back to text, and the select must not jump there
  // while the author is retyping a number.
  const [kind, setKind] = useState<PropertyValueKind>(effective);
  useEffect(() => {
    if (effective !== "string") setKind(effective);
  }, [effective]);
  const conflicting = setValueKinds(value);
  // A typed slot that holds a value always decides. With none, a non-empty text
  // decides; only a row holding nothing at all shows the type last chosen here.
  const shownKind: PropertyValueKind =
    effective !== "string" ? effective : conflicting.length > 0 ? "string" : kind;
  const typedValue = value[VALUE_FIELD[shownKind]];
  const problem = numericProblem(shownKind, typedValue);
  const jsonOnly = JSON_ONLY_KINDS.includes(shownKind);

  const choose = (next: PropertyValueKind) => {
    setKind(next);
    onChange(withValue(value, next, convertValue(value, next)));
  };

  const inputCls =
    "h-7 flex-1 min-w-[90px] rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring aria-[invalid=true]:border-destructive";

  return (
    <div className="flex min-w-[160px] flex-1 flex-col gap-1">
      <div className="flex items-center gap-1">
        <label htmlFor={selectId} className="sr-only">
          {t("propertyValue.typeLabel", "Value type")}
        </label>
        <select
          id={selectId}
          value={shownKind}
          onChange={(e) => choose(e.target.value as PropertyValueKind)}
          disabled={readOnly}
          className="h-7 shrink-0 rounded border border-input bg-background px-1 text-[10px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid={`${testIdPrefix}-type`}
        >
          {EDITABLE_KINDS.map((k) => (
            <option key={k} value={k}>
              {t(KIND_LABELS[k][0], KIND_LABELS[k][1])}
            </option>
          ))}
          {jsonOnly && (
            <option value={shownKind} disabled>
              {t(KIND_LABELS[shownKind][0], KIND_LABELS[shownKind][1])}
            </option>
          )}
        </select>
        {shownKind === "string" ? (
          renderText()
        ) : jsonOnly ? (
          <span
            className="flex-1 truncate rounded border border-dashed border-input px-2 py-1 font-mono text-[10px] text-muted-foreground"
            title={JSON.stringify(typedValue)}
            data-testid={`${testIdPrefix}-json`}
          >
            {t("propertyValue.jsonOnly", "Set in the JSON tab")}: {JSON.stringify(typedValue)}
          </span>
        ) : shownKind === "boolean" ? (
          <select
            aria-label={t("propertyValue.valueLabel", "Value")}
            value={typedValue === true ? "true" : typedValue === false ? "false" : ""}
            onChange={(e) => onChange(withValue(value, "boolean", e.target.value === "true"))}
            disabled={readOnly}
            className="h-7 flex-1 rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
            data-testid={`${testIdPrefix}-boolean`}
          >
            {typedValue !== true && typedValue !== false && <option value="">—</option>}
            <option value="true">true</option>
            <option value="false">false</option>
          </select>
        ) : (
          <NumberInput
            aria-label={t("propertyValue.valueLabel", "Value")}
            integer={shownKind === "int" || shownKind === "long"}
            value={typeof typedValue === "number" ? typedValue : undefined}
            onChange={(v) => onChange(withValue(value, shownKind, v))}
            readOnly={readOnly}
            aria-describedby={problem ? problemId : undefined}
            className={inputCls}
            data-testid={`${testIdPrefix}-number`}
          />
        )}
      </div>
      {problem === "intRange" && (
        <p id={problemId} className="text-[10px] text-destructive" role="alert" data-testid={`${testIdPrefix}-int-range`}>
          {t(
            "propertyValue.intRange",
            "Outside the Integer range (±2,147,483,647). Choose Long integer for this value.",
          )}
        </p>
      )}
      {problem === "unsafeLong" && (
        <p id={problemId} className="text-[10px] text-amber-700 dark:text-amber-400" role="alert" data-testid={`${testIdPrefix}-unsafe-long`}>
          {t(
            "propertyValue.unsafeLong",
            "Beyond 9,007,199,254,740,991 the browser cannot hold this whole number exactly; edit it in the JSON tab.",
          )}
        </p>
      )}
      {(shownKind === "long" || shownKind === "double") && (
        <p className="text-[10px] text-muted-foreground" data-testid={`${testIdPrefix}-needs-66`}>
          {t("propertyValue.needs66", "Long integer and Decimal need EDDI 6.6 or later.")}
        </p>
      )}
      {conflicting.length > 1 && (
        <p className="text-[10px] text-amber-700 dark:text-amber-400" role="status" data-testid={`${testIdPrefix}-conflict`}>
          {t("propertyValue.conflict", {
            defaultValue:
              "This row sets {{fields}}. Only {{winner}} is used; choosing a type here keeps that one value and removes the rest.",
            fields: conflicting.map((k) => VALUE_FIELD[k]).join(", "),
            winner: VALUE_FIELD[effective],
          })}
        </p>
      )}
    </div>
  );
}
