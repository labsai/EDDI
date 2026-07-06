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
