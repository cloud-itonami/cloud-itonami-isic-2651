(ns measctrlmfg.governor
  "Measuring Control Equipment Plant Operations Governor -- the
  independent compliance layer that earns the MeasCtrlAdvisor the
  right to commit. The advisor has no notion of whether a piece of
  equipment it wants to schedule maintenance against has actually been
  inspected/registered, whether a batch it wants to coordinate a
  shipment against has actually been QC-verified/registered, whether a
  maintenance proposal secretly tries to ACTUATE (rather than merely
  draft-schedule) calibration-bench/assembly-line/test-bench
  equipment, whether a proposal secretly tries to self-issue a
  NIST-traceable calibration certificate (an authority this actor
  never holds), whether a shipment proposal's own claimed quantity
  would blow through the batch's own logged production quantity, or
  when an act stops being a coordination proposal and becomes direct
  calibration/assembly-line-equipment control, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is
  `:measuring-control-equipment-plant-operations-governor` (see
  docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:calibration-
                                       bench/actuate` or `:test-bench/
                                       run`) is the 'direct calibration/
                                       assembly-line-equipment control'
                                       scope violation this actor must
                                       NEVER perform -- HARD, PERMANENT,
                                       unconditional.
    4. Equipment-actuate blocked   -- for `:schedule-maintenance`, does
                                       the proposal's own `:value`
                                       declare `:actuate-equipment?
                                       true`? Directly actuating
                                       calibration-bench/assembly-line/
                                       test-bench equipment is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `measctrlmfg.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Metrology-certification-
       authority blocked            -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:issue-nist-traceable-
                                       calibration-certificate? true`
                                       is attempting to self-issue a
                                       NIST-traceable calibration
                                       certificate -- an authority
                                       exclusively reserved to the
                                       accredited calibration
                                       laboratory, never this actor --
                                       HARD, PERMANENT, unconditional.
    6. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`measctrlmfg.registry/
                                       equipment-ready?`) -- never trust
                                       the advisor's own rationale
                                       about verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('plant/batch record must be
                                       independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    7. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`measctrlmfg.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'plant/batch record'
                                       HARD invariant: a batch's own
                                       verified/registered status is as
                                       much a ground-truth fact as an
                                       equipment unit's own.
    9. Shipment quantity exceeded  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-units` plus the
                                       proposal's own claimed `:units`
                                       would exceed the batch's own
                                       recorded `:quantity-units`
                                       (`measctrlmfg.registry/
                                       shipment-quantity-exceeded?`) --
                                       ground truth from the batch's
                                       own permanent fields, never a
                                       self-reported quantity claim.
   10. Invalid instrument-class    -- for `:log-production-batch`, if
                                       the patch declares an
                                       `:instrument-class` outside the
                                       closed known set
                                       (`measctrlmfg.registry/
                                       instrument-class-valid?`), the
                                       batch record is rejected rather
                                       than let a fabricated
                                       classification through.
   11. Invalid calibration-
       accuracy reading             -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:calibration-accuracy-ppm` that
                                       is not a physically plausible
                                       calibration reading
                                       (`measctrlmfg.registry/
                                       calibration-accuracy-ppm-valid?`),
                                       the batch record is rejected
                                       rather than let a fabricated/
                                       sensor-error reading through.
   12. Invalid defect-rate         -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:defect-rate-percent` that is
                                       not a physically plausible
                                       reading
                                       (`measctrlmfg.registry/
                                       defect-rate-valid?`), the batch
                                       record is rejected rather than
                                       let fabricated/sensor-error data
                                       through.
   13. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve."
  (:require [measctrlmfg.registry :as registry]
            [measctrlmfg.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct calibration/
  assembly-line-equipment-control effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct calibration/assembly-line-equipment control, a
  fabricated actuation effect) is this actor's central scope
  boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は校正・組立・試験等の設備の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- actuate-equipment-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:actuate-equipment? true` is attempting
  to directly actuate calibration-bench/assembly-line/test-bench
  equipment -- this actor may only ever propose/schedule a DRAFT
  maintenance window, never actuate equipment directly. No override,
  ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:actuate-equipment? (:value proposal))))
    [{:rule :actuate-equipment-blocked
      :detail "校正ベンチ/組立ライン/試験ベンチ設備の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- metrology-certification-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:issue-nist-traceable-calibration-
  certificate? true` is attempting to self-issue a NIST-traceable
  calibration certificate -- an authority exclusively reserved to the
  accredited calibration laboratory, never this actor. No phase and no
  human approval can ever override this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (true? (:issue-nist-traceable-calibration-certificate? payload))
      [{:rule :metrology-certification-authority-blocked
        :detail "NISTトレーサブル校正証明書の自己発行提案は恒久的に禁止 -- 認定校正機関の専権事項"}])))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('plant/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'plant/batch
  record must be independently verified/registered before any action'
  HARD invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-quantity-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date quantity plus the proposal's
  own claimed quantity would exceed the batch's own recorded
  `:quantity-units` -- ground truth from the batch's own permanent
  fields, never a self-reported quantity claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id units]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-quantity-exceeded? b units))
        [{:rule :shipment-quantity-exceeded
          :detail (str batch-id " の記録済み生産数量(" (:quantity-units b)
                       "個)を、既存出荷実績(" (:shipped-units b 0.0)
                       "個)+今回申請(" units "個)が超過")}]))))

(defn- invalid-instrument-class-violations
  "For `:log-production-batch`, if the patch declares an
  `:instrument-class` outside the closed known set, reject rather than
  let a fabricated instrument class through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [instrument-class (:instrument-class (:value proposal))]
      (when (and (some? instrument-class) (not (registry/instrument-class-valid? instrument-class)))
        [{:rule :invalid-instrument-class
          :detail (str instrument-class " は既知の instrument-class 値ではない")}]))))

(defn- invalid-calibration-accuracy-violations
  "For `:log-production-batch`, if the patch declares a
  `:calibration-accuracy-ppm` that is not a physically plausible
  calibration reading, reject rather than let fabricated/sensor-error
  data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [ppm (:calibration-accuracy-ppm (:value proposal))]
      (when (and (some? ppm) (not (registry/calibration-accuracy-ppm-valid? ppm)))
        [{:rule :invalid-calibration-accuracy-ppm
          :detail (str ppm "ppm は物理的に妥当な校正精度の範囲外")}]))))

(defn- invalid-defect-rate-violations
  "For `:log-production-batch`, if the patch declares a
  `:defect-rate-percent` that is not a physically plausible reading,
  reject rather than let fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [rr (:defect-rate-percent (:value proposal))]
      (when (and (some? rr) (not (registry/defect-rate-valid? rr)))
        [{:rule :invalid-defect-rate
          :detail (str rr "% は物理的に妥当な不良率の範囲外")}]))))

(defn check
  "Censors a MeasCtrlAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (actuate-equipment-blocked-violations request proposal)
                           (metrology-certification-authority-blocked-violations proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-quantity-exceeded-violations request proposal st)
                           (invalid-instrument-class-violations request proposal)
                           (invalid-calibration-accuracy-violations request proposal)
                           (invalid-defect-rate-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
