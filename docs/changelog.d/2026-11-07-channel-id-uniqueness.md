## 🐛 fix(channels): the channelId uniqueness check fails closed like the name check (2026-11-07)

**Repo:** EDDI (`fix/channel-id-uniqueness`)

### What changed and why

Two channel integrations must not bind the same `channelType:channelId`: the
router keys its routing map on it, so a second one silently takes over the
first one's channel. `validateUniqueChannelId` enforced that, but it swallowed
every failure: an entry it could not read was skipped at debug level, and a scan
that could not run at all logged "allowing save". One store timeout while
reading the integration that held `C12345` was enough to store a second
integration bound to `C12345`. The name check right below it already failed
closed (503) and skipped only a missing or undeserialisable document.

The three near-identical descriptor scans (channelId check, name check, and the
copy-name collector of `duplicateChannel`) are now one private walker,
`forEachOtherIntegration`, with the name check's error policy:

- a descriptor whose integration no longer exists is skipped;
- a stored document that no longer deserialises is skipped, so one corrupt
  entry cannot block every channel write;
- anything else (descriptors cannot be listed, an entry cannot be read right
  now) refuses the save with **503**, "Could not verify that the channel
  integration channelId is unique. Please retry."

### Behaviour changes

- Creating or updating an integration with a `channelId` answers 503 instead of
  saving when the uniqueness scan hits a transient store failure. Retrying
  succeeds once the store answers.
- Duplicating an integration answers 503 one step earlier (while picking the
  copy's name) on the same failure; the name check that followed already
  answered 503 for it.

### Known limit

The scan and its lock serialise saves on one node only; two nodes saving the
same `channelId` at the same moment can still both pass. Recorded in the
walker's Javadoc.

**Files:** [`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java),
[`RestChannelIntegrationStoreCrudTest.java`](../../src/test/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStoreCrudTest.java),
[`slack-integration.md`](../slack-integration.md)
