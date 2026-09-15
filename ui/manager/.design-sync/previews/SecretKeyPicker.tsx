import { SecretKeyPicker } from "eddi-manager";

export const DirectValue = () => (
  <div style={{ padding: 16, width: 340 }}>
    <SecretKeyPicker value="" onChange={() => {}} />
  </div>
);

export const VaultReference = () => (
  <div style={{ padding: 16, width: 340 }}>
    <SecretKeyPicker value="${vault:openaiKey}" onChange={() => {}} />
  </div>
);

/**
 * `referenceOnly` with nothing filled in yet: no password mask and no reveal
 * toggle, because a pointer is the only admissible value and there is nothing
 * in it to hide.
 */
export const ReferenceOnlyEmpty = () => (
  <div style={{ padding: 16, width: 340 }}>
    <SecretKeyPicker value="" onChange={() => {}} referenceOnly />
  </div>
);

/**
 * The state worth reviewing against the tokens: a literal in a field that takes
 * only a reference draws the destructive border and the explanatory line.
 */
export const ReferenceOnlyRejectsLiteral = () => (
  <div style={{ padding: 16, width: 340 }}>
    <SecretKeyPicker value="sk-live-abcdef" onChange={() => {}} referenceOnly />
  </div>
);

/** A `${vars:…}` reference keeps its scheme in the chip; a vault key does not. */
export const VariableReference = () => (
  <div style={{ padding: 16, width: 340 }}>
    <SecretKeyPicker value="${vars:tenant-api-key}" onChange={() => {}} referenceOnly />
  </div>
);
