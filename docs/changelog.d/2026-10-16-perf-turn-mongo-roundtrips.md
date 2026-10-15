## perf(engine): fewer MongoDB round trips per conversation turn (2026-10-16)

**Repo:** EDDI (`perf/turn-mongo-roundtrips`)

A JProfiler profile of the virtual-thread build (labsai/EDDI#1045) counted about 17 MongoDB reads per turn, each with a named caller, and found the driver's connection pool to be the ceiling once turns run on virtual threads (418 operations queued for a connection at 500 in-flight turns). This branch removes the redundant ones, one commit each, in the order the profile suggested.

### What changed and why

- **One conversation-descriptor read per request.** A REST turn read the same descriptor three times before it started: `RestAgentEngine`'s ownership check, the same check again in `ConversationService.say`, and the soft-delete check right after it. New `ConversationDescriptorMemo` (`@RequestScoped`) remembers the resolved descriptor (live, archived or none) for the rest of the request; `ConversationAccessGuard` and `ConversationService.isSoftDeleted` both resolve through it. Only the descriptor is shared, never a decision: each check still compares the owner against its own identity. Store failures are not remembered, every write through `ConversationDescriptorStore` forgets the conversation, and outside a request (worker threads, schedules) every lookup reads the store as before. Saves 2 reads per turn on the request thread, where the profile saw most of the pool waits.

### Design decisions

- **A request-scoped memo, not new parameters.** The checks sit behind `IConversationService.say` / `sayStreaming`, which Slack, MCP, `/v1` and A2A also call; threading a resolved descriptor through all of them would have changed every signature for a saving that only the REST path needs twice.
