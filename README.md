# cloud-itonami-isic-2651: Manufacture of measuring, testing, navigating and control equipment

Open Business Blueprint for **ISIC 2651**: manufacture of measuring, testing, navigating and control equipment — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **measuring/testing/navigating/control-equipment plant operations**: production-batch data logging (instrument-class/calibration-accuracy/quantity/defect-rate), calibration-bench/assembly-line/test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for measuring/
testing/navigating/control-equipment plant operations: run by a
qualified operator so a plant keeps its own operating records instead
of renting a closed SaaS.

## Scope: plant operations coordination, not instrument-line control

ISIC 2651 covers the **manufacturing plant** that calibrates, assembles
and tests process-control instruments, laboratory analytical
instruments, test-and-inspection equipment, navigational instruments,
meters, radiation-detection instruments, surveying instruments and
meteorological instruments (excludes optical instruments — ISIC 2670,
watches/clocks — ISIC 2652, and medical/dental instruments — ISIC
3250). This actor coordinates the back-office record keeping around
that plant — it never touches the calibration-bench/assembly-line/
test-bench equipment directly, and it is never the accredited
calibration laboratory that issues NIST-traceable calibration
certificates.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — calibration/assembly batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — calibration/assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a calibration-drift/precision-defect/electrical-safety concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(calibration/assembly-line equipment, precision-defect and electrical-
safety hazard, metrology certification, direct worker-safety
consequence):

- Does NOT control calibration-bench, assembly-line, or test-bench equipment directly
- Does NOT make plant-safety or metrology-certification decisions (that's the plant supervisor's / accredited calibration laboratory's exclusive human/institutional authority)
- Does NOT actuate calibration-bench/assembly-line/test-bench equipment (human plant supervisor decides)
- Does NOT self-issue a NIST-traceable calibration certificate (the accredited calibration laboratory's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and metrology certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`measctrlmfg.operation/build`, a langgraph-clj StateGraph):
1. **`measctrlmfg.advisor`** (sealed intelligence node, `MeasCtrlAdvisor`): proposes decisions only, never commits
2. **`measctrlmfg.governor`** (independent, `Measuring Control Equipment Plant Operations Governor`): validates against domain rules, re-derived from `measctrlmfg.registry`'s pure functions and `measctrlmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct calibration/assembly-line-equipment control)
     - Directly actuating calibration-bench/assembly-line/test-bench equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a NIST-traceable calibration certificate (`:issue-nist-traceable-calibration-certificate? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:instrument-class` value on a production-batch patch
     - No physically implausible `:calibration-accuracy-ppm` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`measctrlmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`measctrlmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
