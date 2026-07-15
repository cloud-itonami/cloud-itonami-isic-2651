# ADR-0001: MeasCtrlAdvisor ⊣ Measuring Control Equipment Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2651` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2651` publishes an OSS blueprint for measuring/
testing/navigating/control-equipment **plant operations coordination**
(production-batch instrument-class/calibration-accuracy/quantity/
defect-rate data logging, calibration-bench/assembly-line/test-bench-
equipment maintenance scheduling, safety-concern flagging, and
outbound product shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest domain analogs are `cloud-itonami-isic-2670` (Manufacture
of optical instruments and photographic equipment) and
`cloud-itonami-isic-3250` (Manufacture of medical and dental
instruments and supplies): all three are back-office coordination
actors for a fixed processing PLANT with precision manufacturing
equipment and a real worker-safety dimension, and all three share the
same four-op shape (`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure (equipment for maintenance
scheduling, batch for shipment coordination). This build mirrors
`cloud-itonami-isic-2670`'s architecture closely (itself informed by
`cloud-itonami-isic-3250` and `cloud-itonami-isic-2652`) but adapts
the hazard profile and equipment/product vocabulary to the measuring/
testing/navigating/control-equipment plant: this vertical's central
physical hazard is calibration-bench/assembly-line/test-bench-
equipment operation and calibration-drift/precision-defect/
electrical-safety risk (worker-safety, distinct from 2670's laser-
alignment-hazard risk and 3250's sterility-assurance/patient-safety
risk); its permanent equipment-actuation block guards calibration-
bench/assembly-line/test-bench EQUIPMENT (`:actuate-equipment?`), the
same field shape as every sibling actor's own equipment-actuation
guard; its production-batch record declares an `:instrument-class`
(closed set spanning the core ISIC 2651 product categories --
process-control instruments, laboratory analytical instruments, test-
and-inspection equipment, navigational instruments, meters, radiation-
detection instruments, surveying instruments, meteorological
instruments -- excluding optical instruments, watches/clocks and
medical/dental instruments, which fall outside this class by
definition) and a `:calibration-accuracy-ppm` (a deviation-from-
certified-reference-standard reading in parts per million, plausibility-
checked against real-world calibration-tolerance classes) in addition
to a `:defect-rate-percent`, rather than 2670's
`:resolution-test-line-pairs-per-mm` or 3250's `:sterility-assurance-
level`/`:nonconformance-rate-percent`; and its shipment quantity is
tracked in finished-instrument UNITS (`:units`/`:quantity-units`/
`:shipped-units`), the same counted-not-weighed shape both siblings
use for finished precision instruments.

This vertical additionally has a DOMAIN-SPECIFIC permanent block
mirroring 2670's IEC 60825-1 laser-safety-classification block and
3250's FDA 510(k)/CE-mark block but for a different regulatory regime:
manufacture of measuring/testing/navigating/control equipment
routinely involves calibration against certified reference standards,
and any calibration certificate claiming traceability to a national
metrology institute (e.g. NIST) is issued by an accredited calibration
laboratory (per ISO/IEC 17025), never by the equipment manufacturer's
own plant-operations coordinator. This actor is never the metrology-
certification authority -- any proposal (regardless of op) that
declares `:issue-nist-traceable-calibration-certificate? true` is a
HARD, PERMANENT, unconditional block
(`measctrlmfg.governor/metrology-certification-authority-blocked-
violations`), the same "no phase, no human override" posture as the
equipment-actuation block.

This vertical has NO pre-existing `kotoba-lang/measctrlmfg`-style
capability library to wrap (verified: no such repo exists, and no
`metrology`/`measctrl`-named repo exists in `kotoba-lang` either, via
GitHub code/repo search). This build therefore uses self-contained
domain logic -- pure functions in `measctrlmfg.registry` (equipment/
batch verification, shipment-quantity recompute, instrument-class
validation, calibration-accuracy plausibility validation, defect-rate
plausibility validation) are re-verified independently by the
governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly
`cloud-itonami-isic-2670`'s `opticalmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:measuring-control-equipment-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"measuring-control-equipment-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external measuring/testing/navigating/control-equipment-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
measuring/testing/navigating/control-equipment vertical has NO
pre-existing capability library to wrap. The equipment/batch-
verification / shipment-quantity / instrument-class / calibration-
accuracy / defect-rate validation functions live as pure functions in
`measctrlmfg.registry` and are re-verified independently by
`measctrlmfg.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2670`'s `opticalmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of measuring/
testing/navigating/control-equipment plant operations. It does NOT:
- Control calibration-bench, assembly-line, or test-bench equipment directly
- Make plant-safety or metrology-certification decisions (exclusive to the human plant supervisor / accredited calibration laboratory)
- Actuate calibration-bench/assembly-line/test-bench equipment
- Self-issue a NIST-traceable calibration certificate

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the accredited calibration laboratory's authority -- it is a
proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: measuring/testing/navigating/control-
equipment manufacturing is a safety-critical domain (precision-defect
risk, calibration-drift hazard, electrical-safety hazard, direct
worker-safety consequence). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (calibration-drift concern, precision-defect
concern, electrical-safety-hazard concern) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" -- it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2670` and `cloud-itonami-isic-3250`, this
vertical has TWO entity kinds each gating a different op:
`:schedule-maintenance` independently verifies the referenced
**equipment** unit's own `:verified?`/`:registered?` fields;
`:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity -- never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into thirteen concrete
checks in `measctrlmfg.governor`, mirroring `cloud-itonami-isic-2670`'s
own elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct calibration/assembly-line-equipment control, equipment actuation, or self-issued NIST-traceable calibration certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Measuring/testing/navigating/control-equipment plant operations
back-office now has a documented, governed, auditable coordination
layer that funnels all decisions through independent validation before
human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no NIST-traceable calibration certificate can
ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into thirteen concrete governor checks) protect against scope creep
into unauthorized equipment operation, equipment actuation, or
metrology-certification self-issuance. Safety concerns are a
circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and metrology
certification issuance remain human-/institution-controlled via
external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, calibration-laboratory
APIs) -- this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2651`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked, metrology-
  certification-authority-blocked, already-scheduled, invalid-
  instrument-class, invalid-calibration-accuracy-ppm, invalid-defect-
  rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
