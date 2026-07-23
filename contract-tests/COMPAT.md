# COMPAT — glasses ↔ luma compatibility pin

glasses knows luma only through the `/v1` HTTP contract (`luma-adapter`
module, behind the `tutor-contract` `TutorBrain` port). This file is the
source of truth for what luma version glasses is built/tested against and
what capabilities it can and cannot rely on yet.

## Last-known-good pin

| Field | Value |
|---|---|
| luma commit | `d61ad3d` (`10kH/luma` main) |
| Verified | 2026-07-23 — key-free fixture suite 7/7 live pass against this SHA (post PR#2 merge) |
| Schema rev | `v1-coach` |
| Coach capability (`response_mode=coach`) | **PRESENT** — W-1 landed (`dd7c19e`). Live-verified: assistantText suppression + `coachEvidence` on free_chat/scenario; prose routes emit empty corrections (no fabrication). `fetchSteering` degrade path (`COACH_UNSUPPORTED`) remains the fallback for older luma. |
| `/v1/capabilities` | **PRESENT** — W-2 landed (`dd7c19e`). Public, no auth; `{schemaRev, coach:true, routes[]}` live-verified. |

### Pin history

| Pin | Verified | Note |
|---|---|---|
| `d61ad3d` | 2026-07-23 | PR#2 merge: skill-metadata Epics 1–3 (snapshot/scrub/feedback) — additive; coach/capabilities contract unchanged |
| `dd7c19e` | 2026-07-23 | W-1 coach + W-2 capabilities (schema `v1-coach`) |
| `da111e5` | 2026-07-21 | Initial pin at migration (`v1-initial`, coach/capabilities absent) |

## Update rule

The pin advances **only** when the full key-free fixture suite passes
against the candidate luma SHA. No partial/manual sign-off substitutes for
the suite. Steps:

1. Point contract fixtures at the candidate `luma` SHA.
2. Run the key-free deterministic fixture suite (grade (i) per
   `docs/dev-loop.md`) end-to-end — no `OPENAI_API_KEY` required, no
   flakiness tolerated.
3. On full pass, update this table's `luma commit` / `Verified` / `Schema
   rev` fields in the same change that bumps any adapter code depending on
   the new surface.
4. On any failure, do not advance the pin. Fix or wait.

## Rollback

```bash
git -C ../luma checkout <pin>
```

Rolling back the pin is always safe as an isolated operation — luma is a
nested independent repo; glasses never has to change to roll luma back to a
previously-verified SHA.

## v2 deferred items

Explicitly out of scope for the current pin/contract; do not block on these:

- `/glasses/realtime` WebSocket reuse (session pooling across turns).
- LAN-open token mint hardening (mTLS, or signed/short-lived tokens instead
  of the current shared-secret `X-Lumella-Local-Token` header) for
  `token-service` when bound off loopback.
