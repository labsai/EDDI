/* ──────────────────────────────────────────────
   SSE payload helpers added for the 2026-09-25 UI review
   ────────────────────────────────────────────── */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it, expect } from "vitest";
import {
  UNCONSUMED_STREAM_ERROR_CODES,
  parseErrorEvent,
  findInputField,
  extractOutputImages,
  extractOutputTexts,
  isSafeUri,
} from "./sse-events";

// Vitest runs from ui/chat (where its config lives); the Java is two levels up.
const JAVA_ROOT = resolve(process.cwd(), "../../src/main/java/ai/labs/eddi/engine");
const STREAMING_JAVA = resolve(JAVA_ROOT, "internal/RestAgentEngineStreaming.java");
const INPUT_TOO_LARGE_JAVA = resolve(JAVA_ROOT, "exception/InputTooLargeExceptionMapper.java");

describe("UNCONSUMED_STREAM_ERROR_CODES", () => {
  it("matches every code the backend puts on a pre-turn refusal", () => {
    // A code the backend adds and this set lacks would leave that refusal's
    // unsent message in the transcript; one this set has and the backend
    // dropped would be dead. Read the codes straight from the Java.
    const java = readFileSync(STREAMING_JAVA, "utf8");
    const start = java.indexOf("private String buildKnownConditionOrOpaqueErrorEvent");
    expect(start).toBeGreaterThan(0);
    const body = java.slice(start, java.indexOf("logAndBuildOpaqueErrorEvent(context, e)", start));
    const literal = [...body.matchAll(/code = "([a-z_]+)";/g)].map((m) => m[1]);

    const mapper = readFileSync(INPUT_TOO_LARGE_JAVA, "utf8");
    const mapperCode = /ERROR_CODE = "([a-z_]+)"/.exec(mapper)?.[1];
    expect(body).toContain("InputTooLargeExceptionMapper.ERROR_CODE");
    expect(mapperCode).toBeTruthy();

    expect([...UNCONSUMED_STREAM_ERROR_CODES].sort()).toEqual(
      [...literal, mapperCode!].sort(),
    );
  });
});

describe("parseErrorEvent", () => {
  it("reads the message and the code", () => {
    expect(parseErrorEvent('{"message":"Waiting","code":"awaiting_approval"}')).toEqual({
      message: "Waiting",
      code: "awaiting_approval",
    });
  });

  it("has no code for a mid-turn failure or a bare payload", () => {
    expect(parseErrorEvent('{"message":"boom"}').code).toBeNull();
    expect(parseErrorEvent("plain text").code).toBeNull();
  });
});

describe("findInputField", () => {
  it("finds the requested field and defaults its subType to password", () => {
    expect(
      findInputField([{ type: "text", text: "Key?" }, { type: "inputField", label: "Key" }]),
    ).toEqual({ subType: "password", label: "Key", placeholder: undefined, defaultValue: undefined });
  });

  it("is null when none is requested", () => {
    expect(findInputField([{ type: "text", text: "hi" }, "bare"])).toBeNull();
    expect(findInputField(undefined)).toBeNull();
  });
});

describe("structured output items", () => {
  it("renders an applicationLink as a link and a button as its label", () => {
    expect(
      extractOutputTexts([
        { type: "applicationLink", label: "Open docs", path: "https://eddi.labs.ai" },
        { type: "button", label: "Confirm", onPress: { action: "x" } },
      ]),
    ).toEqual(["[Open docs](https://eddi.labs.ai)", "**Confirm**"]);
  });

  it("does not turn an unsafe link path into a link", () => {
    expect(
      extractOutputTexts([{ type: "applicationLink", label: "Click", path: "javascript:alert(1)" }]),
    ).toEqual(["Click"]);
  });

  it("returns image items separately, dropping unsafe URIs", () => {
    const output = [
      { type: "image", uri: "https://cdn.example/a.png", alt: "A" },
      { type: "image", uri: "/img/b.png" },
      { type: "image", uri: "javascript:alert(1)" },
      { type: "image", uri: "//evil.example/c.png" },
      { type: "text", text: "caption" },
    ];
    expect(extractOutputImages(output)).toEqual([
      { uri: "https://cdn.example/a.png", alt: "A" },
      { uri: "/img/b.png", alt: undefined },
    ]);
    expect(extractOutputTexts(output)).toEqual(["caption"]);
  });

  it("accepts only http(s) and same-origin paths", () => {
    expect(isSafeUri("https://x.example")).toBe(true);
    expect(isSafeUri("http://x.example")).toBe(true);
    expect(isSafeUri("/path")).toBe(true);
    expect(isSafeUri("data:image/png;base64,AAAA")).toBe(false);
    expect(isSafeUri("//protocol-relative.example")).toBe(false);
    expect(isSafeUri("")).toBe(false);
  });
});
