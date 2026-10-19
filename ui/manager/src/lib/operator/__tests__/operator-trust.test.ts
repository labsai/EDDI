import { describe, it, expect } from "vitest";
import { assessOperatorTrust, type TrustInputs } from "../operator-trust";

const ok = { status: "success", value: true } as const;
const verified = { status: "success", value: { verified: true } } as const;
const pending = { status: "pending" } as const;

function inputs(over: Partial<TrustInputs> = {}): TrustInputs {
  return { live: true, marker: ok, gate: verified, ...over };
}

describe("assessOperatorTrust", () => {
  it("is idle when there is no live operator", () => {
    expect(assessOperatorTrust(inputs({ live: false, marker: pending, gate: pending }))).toEqual({
      state: "idle",
      reasons: [],
    });
  });

  it("trusts an operator with the marker and a verified gate", () => {
    expect(assessOperatorTrust(inputs())).toEqual({ state: "trusted", reasons: [] });
  });

  it("is checking while either answer is outstanding", () => {
    expect(assessOperatorTrust(inputs({ marker: pending })).state).toBe("checking");
    expect(assessOperatorTrust(inputs({ gate: pending })).state).toBe("checking");
    expect(assessOperatorTrust(inputs({ marker: pending, gate: pending })).state).toBe("checking");
  });

  it("does not trust an agent without the marker, even with a verified gate", () => {
    const t = assessOperatorTrust(inputs({ marker: { status: "success", value: false } }));
    expect(t.state).toBe("untrusted");
    expect(t.reasons).toEqual(["marker"]);
  });

  it("does not trust an agent whose gate failed, and carries the verifier's reason", () => {
    const t = assessOperatorTrust(
      inputs({ gate: { status: "success", value: { verified: false, reason: "hitlConfig is absent" } } }),
    );
    expect(t.state).toBe("untrusted");
    expect(t.reasons).toEqual(["gate"]);
    expect(t.gateDetail).toBe("hitlConfig is absent");
  });

  it("reports both failures together", () => {
    const t = assessOperatorTrust(
      inputs({
        marker: { status: "success", value: false },
        gate: { status: "success", value: { verified: false } },
      }),
    );
    expect(t.reasons).toEqual(["marker", "gate"]);
  });

  it("says no the moment one check fails, without waiting for the other", () => {
    expect(
      assessOperatorTrust(inputs({ marker: { status: "success", value: false }, gate: pending })).state,
    ).toBe("untrusted");
    expect(
      assessOperatorTrust(inputs({ marker: pending, gate: { status: "success", value: { verified: false } } }))
        .state,
    ).toBe("untrusted");
  });

  it("fails closed when a check errors", () => {
    const t = assessOperatorTrust(inputs({ marker: { status: "error", error: new Error("HTTP 500") } }));
    expect(t.state).toBe("untrusted");
    expect(t.reasons).toEqual(["unreadable"]);
    expect(t.errorDetail).toBe("HTTP 500");

    const g = assessOperatorTrust(inputs({ gate: { status: "error", error: "offline" } }));
    expect(g.state).toBe("untrusted");
    expect(g.errorDetail).toBe("offline");
  });
});
