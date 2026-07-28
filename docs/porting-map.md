# ELLA-MA → lumella porting map (P3)

Source: `TUTOR/ELLA/app/src/main/java/com/woolab/ella/**` (read-only).
Target: `TUTOR/lumella/glasses/app/src/main/java/com/woolab/lumella/**` (all
ported files land in `:app`, package `com.woolab.lumella.*`, mirroring the
source sub-package). The `TutorBrain` contract types themselves (interface +
data/sealed types) live in `:tutor-contract`
(`com.woolab.lumella.contract`), not in this list — see
`tutor-contract/src/main/kotlin/com/woolab/lumella/contract/`.

## 15 ported main files

| # | Source (`com.woolab.ella.*`) | Target (`com.woolab.lumella.*`) | Adaptation note |
|---|---|---|---|
| 1 | `state.LearnerStateStore` | `state.LearnerStateStore` | Verbatim rename (package only). |
| 2 | `state.LearnerState` | `state.LearnerState` | Verbatim rename (package only). |
| 3 | `orchestration.StateGraphOrchestrator` | `orchestration.StateGraphOrchestrator` | Verbatim rename (package only). |
| 4 | `orchestration.StalenessGuard` | `orchestration.StalenessGuard` | Verbatim rename (package only). |
| 5 | `pedagogy.SteeringComposer` | `pedagogy.SteeringComposer` | Verbatim rename (package only). |
| 6 | `agents.SlowPathDispatcher` | `agents.SlowPathDispatcher` | Verbatim rename (package only). |
| 7 | `agents.SlowPathCoalescer` | `agents.SlowPathCoalescer` | Verbatim rename (package only). |
| 8 | `agents.PedagogyAgents` | `agents.PedagogyAgents` | Verbatim rename (package only). |
| 9 | `agents.PedagogyAgentClient` | `agents.PedagogyAgentClient` | Interface + exception types port verbatim; the concrete `EndpointPedagogyAgentClient` direct-HTTP impl is dropped (forbidden direct HTTP from `:app`). Delegation to the brain now goes through `TutorBrainPedagogyClient` (new, additive — implements `PedagogyAgentClient` by delegating to `TutorBrain`; does not replace a ported slot). |
| 10 | `slowpath.SlowPathQueue` | `slowpath.SlowPathQueue` | Verbatim rename (package only). |
| 11 | `slowpath.PendingTurnBinder` | `slowpath.PendingTurnBinder` | Verbatim rename (package only). |
| 12 | `config.EllaMaConfig` | `config.EllaMaConfig` | Verbatim rename (package only). |
| 13 | `util.MiniJson` | `util.MiniJson` | Verbatim rename (package only). |
| 14 | `RealtimeProtocol` | `root.RealtimeProtocol` | Verbatim rename (package only). |
| 15 | `RealtimeCredentialProvider` | `root.TokenServiceCredentialProvider` | **Not verbatim.** Re-targets the local `token-service` (`:8788`) instead of the legacy realtime credential endpoint: sends `X-Lumella-Local-Token`, caches the issued token with a TTL, and re-mints on expiry. |

Out of scope for the 15 (explicitly dropped, not a gap): `pedagogy.CleanTutorPersona`,
`agents.BlockingPedagogyAgentClient`, `agents.CachingPedagogyAgentClient`,
`ConversationRuntimeState`, `NetworkErrorMessageMapper`, `EllaApp`,
`MainActivity` (the last two are legacy app scaffolding; `:app` has its own).
`agents.BlockingPedagogyAgentClient`/`agents.CachingPedagogyAgentClient` are unported because they are B-FREEZE eval-only decorators (legacy AblationStudy offline determinism), out of P3 runtime scope.

## New (not ported, additive)

- `VoiceFastPath` + `RealtimeTransport` (`:app`) — fast-path voice loop and
  realtime transport; no upstream equivalent in the 15.
- `TutorBrainPedagogyClient` (`:app`) — delegates `PedagogyAgentClient` calls
  to `TutorBrain` (see file 9 above).

## Contract location

The `TutorBrain` contract (interface, `BrainCredentialsProvider`,
`BrainCredentials`, `BrainConnectionState`, `BrainCapabilities`,
`ResumableSession`, `BrainConnection`, `SessionPolicy`, `BrainSession`,
`TurnEvidence`, `SteeringCorrection`, `SteeringEvidence`, `SteeringResult`,
`UnavailableReason`, `ImageContext`) lives in `:tutor-contract`
(`com.woolab.lumella.contract`), pure Kotlin, blocking (no coroutines). It is
consumed by `:app` (via `TutorBrainPedagogyClient` et al.) and implemented by
`:luma-adapter` against the luma engine.

## 11 ported test files

All land under `:app`'s test tree (`com.woolab.lumella.*`, mirroring the
target package of the file under test):

1. `state.LearnerStateStoreTest`
2. `orchestration.StateGraphOrchestratorTest`
3. `orchestration.StalenessGuardTest`
4. `pedagogy.SteeringComposerTest`
5. `agents.SlowPathDispatcherTest`
6. `agents.PedagogyAgentsTest`
7. `agents.PedagogyAgentClientTest`
8. `slowpath.SlowPathQueueTest`
9. `slowpath.PendingTurnBinderTest`
10. `config.EllaMaConfigTest`
11. `root.RealtimeProtocolTest`

Plus new `:app` tests with no upstream equivalent: `VoiceFastPath`/
`RealtimeTransport` tests, D-4 (steering-is-evidence-only) tests, resilience
tests, and `TokenServiceCredentialProvider` TTL-cache tests. Contract-level
tests (`SteeringResult` exhaustiveness, `BrainConnection` state sanity, etc.)
live in `:tutor-contract`; the classpath/dependency-rule guard
(`DependencyRuleGuardTest`) lives in `:contract-tests`.

### Not a gap: files without dedicated upstream tests

Four of the 15 ported main files have no dedicated test file upstream in
`TUTOR/ELLA` — this is the pre-existing upstream state, not something
introduced by the port:

- `state.LearnerState` (only `LearnerStateStoreTest` exists upstream, and it
  covers a different type).
- `agents.SlowPathCoalescer`.
- `util.MiniJson`.
- `RealtimeCredentialProvider` (superseded by `TokenServiceCredentialProvider`,
  which does get new TTL/resilience tests in `:app` — see above).
