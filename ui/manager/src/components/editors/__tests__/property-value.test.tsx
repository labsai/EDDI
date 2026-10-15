import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import {
  convertValue,
  effectiveValueKind,
  numericProblem,
  setValueKinds,
  withValue,
  type TypedPropertyValue,
} from "../property-value-utils";
import { PropertySetterEditor, type PropertySetterConfig } from "../propertysetter-editor";
import { PropertyInstructionsEditor, type PropertyInstruction } from "../apicalls-editor";

describe("property-value-utils", () => {
  it("lets the last typed slot in engine order win, and text lose to any typed slot", () => {
    expect(effectiveValueKind({ valueString: "x" })).toBe("string");
    expect(effectiveValueKind({ valueString: "x", valueInt: 3 })).toBe("int");
    expect(effectiveValueKind({ valueInt: 3, valueDouble: 2.5 })).toBe("double");
    expect(effectiveValueKind({ valueLong: 1759400000000 })).toBe("long");
    expect(effectiveValueKind({ valueBoolean: false })).toBe("boolean");
    expect(effectiveValueKind({})).toBe("string");
    expect(effectiveValueKind({ valueString: "" })).toBe("string");
  });

  it("pins the engine's whole precedence: string < object < list < int < long < float < double < boolean", () => {
    // PropertyInstructionExecutor.authoredValue overwrites in exactly this order.
    expect(effectiveValueKind({ valueInt: 1, valueLong: 2 })).toBe("long");
    expect(effectiveValueKind({ valueLong: 2, valueFloat: 1.5 })).toBe("float");
    expect(effectiveValueKind({ valueFloat: 1.5, valueDouble: 2.5 })).toBe("double");
    expect(effectiveValueKind({ valueDouble: 2.5, valueBoolean: true })).toBe("boolean");
    expect(effectiveValueKind({ valueObject: {}, valueList: [] })).toBe("list");
    expect(effectiveValueKind({ valueList: [], valueInt: 1 })).toBe("int");
  });

  it("lists every slot that carries a value", () => {
    expect(setValueKinds({ valueString: "a", valueInt: 1 })).toEqual(["string", "int"]);
    expect(setValueKinds({ valueString: "" })).toEqual([]);
  });

  it("withValue leaves exactly one slot and removes the others", () => {
    const next = withValue({ name: "p", valueString: "x", valueInt: 3, scope: "step" } as TypedPropertyValue & { name: string; scope: string }, "long", 5);
    expect(next).toEqual({ name: "p", scope: "step", valueLong: 5 });
    expect("valueString" in next).toBe(false);
  });

  it("withValue for text without a value keeps an empty string; for a number it keeps no slot", () => {
    expect(withValue({ valueInt: 2 }, "string", undefined)).toEqual({ valueString: "" });
    expect(withValue({ valueInt: 2 }, "double", undefined)).toEqual({});
  });

  it("converts the current value when the type changes", () => {
    expect(convertValue({ valueString: " 42 " }, "int")).toBe(42);
    expect(convertValue({ valueString: "4.5" }, "int")).toBeUndefined();
    expect(convertValue({ valueString: "4.5" }, "double")).toBe(4.5);
    expect(convertValue({ valueString: "true" }, "boolean")).toBe(true);
    expect(convertValue({ valueInt: 7 }, "string")).toBe("7");
    expect(convertValue({ valueString: "hello" }, "long")).toBeUndefined();
  });

  it("carries over only decimal text — never hex, binary, octal or exponent text into a whole number", () => {
    for (const text of ["0x1F", "0b11", "0o17", "1e3", "Infinity", "", "  ", "12abc", "1_000"]) {
      expect(convertValue({ valueString: text }, "int")).toBeUndefined();
      expect(convertValue({ valueString: text }, "long")).toBeUndefined();
    }
    expect(convertValue({ valueString: " 12 " }, "int")).toBe(12);
    expect(convertValue({ valueString: "-7" }, "long")).toBe(-7);
    expect(convertValue({ valueString: "+7" }, "int")).toBe(7);
  });

  it("does not round a whole number past 2^53 while converting", () => {
    expect(convertValue({ valueString: "9007199254740991" }, "long")).toBe(9007199254740991);
    expect(convertValue({ valueString: "9007199254740993" }, "long")).toBeUndefined();
  });

  it("carries decimals with an optional exponent, but no hex, binary or non-finite text", () => {
    expect(convertValue({ valueString: "19.99" }, "double")).toBe(19.99);
    expect(convertValue({ valueString: ".5" }, "double")).toBe(0.5);
    expect(convertValue({ valueString: "2.5e-4" }, "double")).toBe(0.00025);
    for (const text of ["0x1F", "0b11", "Infinity", "NaN", "1.2.3", ""]) {
      expect(convertValue({ valueString: text }, "double")).toBeUndefined();
      expect(convertValue({ valueString: text }, "float")).toBeUndefined();
    }
  });

  it("flags an int out of range and a long the browser cannot hold", () => {
    expect(numericProblem("int", 2147483647)).toBeNull();
    expect(numericProblem("int", 2147483648)).toBe("intRange");
    expect(numericProblem("long", 2147483648)).toBeNull();
    expect(numericProblem("long", 2 ** 53 + 2)).toBe("unsafeLong");
    expect(numericProblem("double", 2 ** 60)).toBeNull();
  });
});

function SetterHarness({ initial, spy }: { initial: PropertySetterConfig; spy?: (c: PropertySetterConfig) => void }) {
  const [data, setData] = useState(initial);
  return (
    <PropertySetterEditor
      data={data}
      onChange={(d) => {
        setData(d);
        spy?.(d);
      }}
    />
  );
}

const setter = (prop: Record<string, unknown>): PropertySetterConfig => ({
  setOnActions: [{ actions: ["a"], setProperties: [{ name: "p", scope: "conversation", ...prop }] }],
});

describe("PropertySetterEditor — typed values", () => {
  it("shows a stored valueLong as a long integer with its value (it used to show an empty text field)", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueLong: 1759400000000 })} />);
    expect(screen.getByTestId("property-value-type")).toHaveValue("long");
    expect(screen.getByTestId("property-value-number")).toHaveValue(1759400000000);
  });

  it("shows a stored valueDouble as a decimal", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueDouble: 48.2081743 })} />);
    expect(screen.getByTestId("property-value-type")).toHaveValue("double");
    expect(screen.getByTestId("property-value-number")).toHaveValue(48.2081743);
  });

  it("switching text to Decimal stores valueDouble and drops valueString", () => {
    const spy = vi.fn();
    renderWithProviders(<SetterHarness initial={setter({ valueString: "19.99" })} spy={spy} />);
    fireEvent.change(screen.getByTestId("property-value-type"), { target: { value: "double" } });
    const prop = spy.mock.lastCall![0].setOnActions[0].setProperties[0];
    expect(prop.valueDouble).toBe(19.99);
    expect(prop).not.toHaveProperty("valueString");
  });

  it("typing a long writes valueLong", () => {
    const spy = vi.fn();
    renderWithProviders(<SetterHarness initial={setter({ valueLong: 1 })} spy={spy} />);
    fireEvent.change(screen.getByTestId("property-value-number"), { target: { value: "9000000000" } });
    expect(spy.mock.lastCall![0].setOnActions[0].setProperties[0]).toMatchObject({ valueLong: 9000000000 });
  });

  it("keeps the chosen numeric type while the field is emptied for retyping", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueInt: 5 })} />);
    fireEvent.change(screen.getByTestId("property-value-number"), { target: { value: "" } });
    expect(screen.getByTestId("property-value-type")).toHaveValue("int");
  });

  it("warns about an integer outside the int range", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueInt: 3000000000 })} />);
    expect(screen.getByTestId("property-value-int-range")).toBeInTheDocument();
  });

  it("says which value runs when a row carries several", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueString: "x", valueInt: 3 })} />);
    expect(screen.getByTestId("property-value-type")).toHaveValue("int");
    expect(screen.getByTestId("property-value-conflict")).toHaveTextContent(/valueString, valueInt/);
  });

  it("shows an object value as set in the JSON tab, without an editable field", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueObject: { a: 1 } })} />);
    expect(screen.getByTestId("property-value-json")).toHaveTextContent('{"a":1}');
  });

  it("warns that a typed value under scope secret is stored in plain text (valueLong included)", () => {
    renderWithProviders(<SetterHarness initial={setter({ valueLong: 5, scope: "secret" })} />);
    expect(screen.getByTestId("property-secret-from-path-warning")).toBeInTheDocument();
  });
});

describe("PropertyInstructionsEditor (httpcalls / MCP / LLM postResponse) — typed values", () => {
  function Harness({ initial, spy }: { initial: PropertyInstruction[]; spy?: (l: PropertyInstruction[]) => void }) {
    const [list, setList] = useState(initial);
    return (
      <PropertyInstructionsEditor
        instructions={list}
        onChange={(l) => {
          setList(l);
          spy?.(l);
        }}
      />
    );
  }

  it("shows and edits a valueDouble instruction", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={[{ name: "price", valueDouble: 19.99, scope: "conversation" }]} spy={spy} />);
    const row = screen.getByTestId("property-instruction-row");
    expect(within(row).getByTestId("instruction-value-type")).toHaveValue("double");
    fireEvent.change(within(row).getByTestId("instruction-value-number"), { target: { value: "12500000.5" } });
    expect(spy.mock.lastCall![0][0]).toMatchObject({ valueDouble: 12500000.5 });
  });

  it("a plain text instruction keeps its template field", () => {
    renderWithProviders(<Harness initial={[{ name: "n", valueString: "{response.x}", scope: "step" }]} />);
    expect(screen.getByTestId("instruction-value-type")).toHaveValue("string");
    expect(screen.getByDisplayValue("{response.x}")).toBeInTheDocument();
  });
});
