import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  OPERATOR_REVISION,
  OPERATOR_REVISION_FINGERPRINT,
  assessOperatorUpgrade,
  buildUpgradeRequest,
  instructionsState,
  logOutdatedOnce,
  operatorProvisioningSource,
  resetOutdatedLogForTests,
  upgradeBlocker,
} from "../operator-revision";
import { endpointsForScope, READ_ENDPOINTS } from "../tool-scopes";
import { defaultOperatorPromptBody } from "../system-prompt";
import { defaultOperatorConfig, type OperatorConfig } from "@/lib/api/operator";

function fingerprint(): string {
  return createHash("sha256").update(operatorProvisioningSource()).digest("hex").slice(0, 16);
}

/** A live operator provisioned at the CURRENT revision, with the current grant. */
function currentOperator(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  const base = defaultOperatorConfig();
  return {
    ...base,
    enabled: true,
    agentId: "6a1b2c3d4e5f60718293a4b5",
    version: 1,
    credentialKey: "anthropic-key",
    provisionedRevision: OPERATOR_REVISION,
    provisionedEndpoints: [...endpointsForScope(base.scope)],
    promptBodyIsDefault: true,
    ...overrides,
  };
}

describe("operator revision", () => {
  it("is bumped whenever the prompt, allow-list or gate changes", () => {
    // THE guard of this whole mechanism. Every existing operator is a snapshot;
    // the only way a deployment learns that its snapshot is stale is this number
    // going up. So a change to what provisioning builds, without a bump, would
    // reach no existing operator — silently, which is the bug this replaced.
    const actual = fingerprint();
    expect(
      actual,
      `The operator's provisioning changed (prompt, allow-list or gate). Bump "revision" in ` +
        `src/lib/operator/operator-revision.json to ${OPERATOR_REVISION + 1} and set "fingerprint" to "${actual}".`,
    ).toBe(OPERATOR_REVISION_FINGERPRINT);
  });

  it("is a positive integer, which the backend's startup check parses", () => {
    expect(Number.isInteger(OPERATOR_REVISION)).toBe(true);
    expect(OPERATOR_REVISION).toBeGreaterThanOrEqual(1);
  });

  it("fingerprints only what provisioning derives, not the admin's choices", () => {
    const source = operatorProvisioningSource();
    // The default model lives in the model catalogue section of the prompt, so
    // it IS covered — but the admin's credential, environment and base URL are
    // never part of what the Manager decides.
    expect(source).not.toContain("credentialKey");
    expect(source).toContain("requireApproval");
    for (const endpoint of READ_ENDPOINTS) expect(source).toContain(endpoint);
  });
});

describe("assessOperatorUpgrade", () => {
  it("has nothing to say about an inactive or paused operator", () => {
    expect(assessOperatorUpgrade(null)).toBeNull();
    expect(assessOperatorUpgrade(defaultOperatorConfig())).toBeNull();
    expect(assessOperatorUpgrade(currentOperator({ enabled: false, provisionedRevision: 0 }))).toBeNull();
  });

  it("reports a current operator as not needing an upgrade", () => {
    const assessment = assessOperatorUpgrade(currentOperator());
    expect(assessment?.needed).toBe(false);
    expect(assessment?.addedEndpoints).toEqual([]);
    expect(assessment?.removedEndpoints).toEqual([]);
  });

  it("flags a config written before revisions existed, without inventing a tool diff", () => {
    const legacy = currentOperator({
      provisionedRevision: undefined,
      provisionedEndpoints: undefined,
      promptBodyIsDefault: undefined,
    });
    const assessment = assessOperatorUpgrade(legacy);
    expect(assessment?.needed).toBe(true);
    expect(assessment?.provisionedRevision).toBe(0);
    expect(assessment?.currentRevision).toBe(OPERATOR_REVISION);
    // Unknown, not "nothing changed": the old grant was never recorded.
    expect(assessment?.addedEndpoints).toBeNull();
    expect(assessment?.removedEndpoints).toBeNull();
  });

  it("names the tools an upgrade adds and removes", () => {
    const current = endpointsForScope("read_write");
    const previous = [...current.filter((e) => !e.includes("/sources/")), "GET /retired/endpoint"];
    const assessment = assessOperatorUpgrade(
      currentOperator({ provisionedRevision: OPERATOR_REVISION - 1, provisionedEndpoints: previous }),
    );
    expect(assessment?.needed).toBe(true);
    expect(assessment?.addedEndpoints).toEqual(current.filter((e) => e.includes("/sources/")));
    expect(assessment?.removedEndpoints).toEqual(["GET /retired/endpoint"]);
  });
});

describe("instructionsState", () => {
  it("trusts the recorded flag", () => {
    expect(instructionsState(currentOperator({ promptBodyIsDefault: true, promptBody: "stale default" }))).toBe("default");
    expect(instructionsState(currentOperator({ promptBodyIsDefault: false }))).toBe("customized");
  });

  it("reads a legacy body equal to today's default as the default", () => {
    const legacy = currentOperator({ promptBodyIsDefault: undefined });
    expect(legacy.promptBody).toBe(defaultOperatorPromptBody(legacy.scope));
    expect(instructionsState(legacy)).toBe("default");
  });

  it("cannot tell an old default from an edit on a legacy config", () => {
    expect(instructionsState(currentOperator({ promptBodyIsDefault: undefined, promptBody: "older text" }))).toBe(
      "unknown",
    );
  });
});

describe("upgradeBlocker", () => {
  it("needs nothing more for a vaulted key", () => {
    expect(upgradeBlocker(currentOperator())).toBeNull();
  });

  it("needs the form when the key was plaintext (never stored)", () => {
    expect(upgradeBlocker(currentOperator({ credentialKey: null }))).toBe("credential");
  });

  it("needs no key for a keyless provider", () => {
    expect(upgradeBlocker(currentOperator({ provider: "bedrock", credentialKey: null }))).toBeNull();
  });

  it("needs the model-server address for ollama unless it was recorded", () => {
    expect(upgradeBlocker(currentOperator({ provider: "ollama", credentialKey: null }))).toBe("llmBaseUrl");
    expect(
      upgradeBlocker(currentOperator({ provider: "ollama", credentialKey: null, llmBaseUrl: "http://ollama:11434" })),
    ).toBeNull();
  });

  it("needs the form when the provider can no longer be provisioned", () => {
    expect(upgradeBlocker(currentOperator({ provider: "gemini-vertex" }))).toBe("provider");
  });
});

describe("buildUpgradeRequest", () => {
  it("carries every admin setting and rebuilds only the instructions", () => {
    const config = currentOperator({
      provisionedRevision: 0,
      promptBody: "an old default",
      environment: "test",
      authMode: "caller-identity",
      apiBaseUrl: "http://eddi:7070",
      model: "claude-opus-5-5",
    });
    const request = buildUpgradeRequest(config, "use-new-default");
    expect(request).not.toBeNull();
    expect(request!.config).toEqual({ ...config, promptBody: defaultOperatorPromptBody(config.scope) });
    expect(request!.apiKey).toBe("${vault:anthropic-key}");
    expect(request!.baseUrl).toBeUndefined();
  });

  it("keeps edited instructions when asked to", () => {
    const config = currentOperator({ promptBody: "my own words", promptBodyIsDefault: false });
    expect(buildUpgradeRequest(config, "keep-current")!.config.promptBody).toBe("my own words");
  });

  it("passes the recorded model-server address through", () => {
    const config = currentOperator({ provider: "ollama", credentialKey: null, llmBaseUrl: " http://ollama:11434 " });
    const request = buildUpgradeRequest(config, "use-new-default");
    expect(request!.apiKey).toBe("");
    expect(request!.baseUrl).toBe("http://ollama:11434");
  });

  it("refuses when the stored config cannot reproduce the settings", () => {
    expect(buildUpgradeRequest(currentOperator({ credentialKey: null }), "use-new-default")).toBeNull();
  });
});

describe("logOutdatedOnce", () => {
  afterEach(() => {
    resetOutdatedLogForTests();
    vi.restoreAllMocks();
  });

  it("warns once per page load, and only when an upgrade is needed", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    logOutdatedOnce(assessOperatorUpgrade(currentOperator()));
    logOutdatedOnce(null);
    expect(warn).not.toHaveBeenCalled();

    const outdated = assessOperatorUpgrade(currentOperator({ provisionedRevision: 0 }));
    logOutdatedOnce(outdated);
    logOutdatedOnce(outdated);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0]![0])).toContain("/manage/operator");
  });
});
