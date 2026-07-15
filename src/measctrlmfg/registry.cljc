(ns measctrlmfg.registry
  "Pure-function domain logic for the measuring/testing/navigating/
  control-equipment plant-operations coordination actor -- equipment/
  batch verification, shipment-quantity recompute, instrument-class
  validation, calibration-accuracy plausibility validation, defect-rate
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/measctrlmfg`-style capability library to
  wrap (verified: no such repo exists, and no `metrology`/`measctrl`-
  named repo exists in kotoba-lang either). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `measctrlmfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `opticalmfg.registry/shipment-quantity-exceeded?` from
  `cloud-itonami-isic-2670`, and `medinstrmfg.registry` from
  `cloud-itonami-isic-3250`): never trust a proposal's own self-
  reported quantity/status when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating calibration-bench/
  assembly-line/test-bench equipment or dispatching a real freight
  carrier, and never the act of self-issuing a NIST-traceable
  calibration certificate (this actor NEVER does any of those -- see
  README `What this actor does NOT do`).

  SCOPE: ISIC 2651 covers manufacture of measuring, testing,
  navigating and control equipment -- calibration-bench, assembly-line
  and test-bench lines producing process-control instruments,
  laboratory analytical instruments, test-and-inspection equipment,
  navigational instruments, meters, radiation-detection instruments,
  surveying instruments and meteorological instruments (excludes
  optical instruments -- ISIC 2670, watches/clocks -- ISIC 2652, and
  medical/dental instruments -- ISIC 3250). This actor coordinates the
  back-office record-keeping around that plant (production-batch
  logging, maintenance scheduling, safety-concern flagging, shipment
  coordination) -- it never touches the calibration/assembly/test-bench
  equipment directly, and it never stands in for the accredited
  calibration laboratory that issues NIST-traceable calibration
  certificates.")

;; ----------------------------- constants -----------------------------

(def valid-instrument-classes
  "The closed set of instrument-class values a production-batch record
  may declare -- the core measuring/testing/navigating/control
  equipment categories ISIC 2651 covers (excluding optical instruments,
  watches/clocks and medical/dental instruments, which fall outside
  this class by definition). Anything else is a fabricated/
  unrecognized instrument class -- the governor HARD-holds rather than
  let an invented classification pass through. This actor never
  DECIDES a batch's instrument classification (that is the
  manufacturer's production-engineering function); it only validates
  that a batch record declares one of the real, known values."
  #{:process-control-instrument :laboratory-analytical-instrument
    :test-and-inspection-equipment :navigational-instrument :meter
    :radiation-detection-instrument :surveying-instrument
    :meteorological-instrument})

(def calibration-accuracy-ppm-min
  "Physical floor for a batch's own calibration-accuracy reading
  (deviation from a certified reference standard measured during a
  calibration check, in parts per million). A reading of exactly zero
  IS achievable (a perfectly-tracking reading against the reference)
  but never negative -- this field records an absolute deviation
  magnitude, never a signed offset, so a negative reading is a
  data-entry error, never a genuine calibration-check reading."
  0.0)

(def calibration-accuracy-ppm-max
  "Physical ceiling for a batch's own calibration-accuracy reading, in
  parts per million deviation from the certified reference standard.
  Grounded in real calibration-tolerance classes for measuring/
  testing/navigating/control equipment: industrial-grade instruments
  (process-control instruments, meters, test-and-inspection equipment)
  typically calibrate to within a few hundred ppm up to low single-
  digit percent of full scale (e.g. IEC 60751 class B RTDs ~0.3-0.8%,
  ASME B40.100 grade-2A pressure gauges ~2%); a reading beyond 100,000
  ppm (10% deviation) means the instrument failed calibration outright
  -- implausible/fabricated data for a batch record that logs a
  completed, passing calibration-check reading, never a real one."
  100000.0)

(def defect-rate-min-percent
  "Physical floor for a batch's own calibration/assembly/test defect-
  rate reading (zero defects is the best possible outcome, never
  negative)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own calibration/assembly/test defect-
  rate reading -- a batch cannot reject more than 100% of its own
  output. A reading above this is implausible sensor/QC data, not a
  real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its instrument-class/calibration-accuracy/quantity/defect-rate
  claims have actually been QC-inspected, not merely logged from an
  unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-quantity-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal: would
  `shipped-units` + `new-units` exceed `batch`'s own recorded
  `:quantity-units` (the batch's own logged production quantity)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-units]
  (let [capacity (:quantity-units batch)
        so-far (:shipped-units batch 0.0)]
    (and (number? capacity)
         (number? new-units)
         (> (+ (double so-far) (double new-units)) (double capacity)))))

(defn instrument-class-valid?
  "Is `instrument-class` one of the closed, known instrument-class
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real instrument class, not omit it silently)."
  [instrument-class]
  (contains? valid-instrument-classes instrument-class))

(defn calibration-accuracy-ppm-valid?
  "Is `ppm` a physically plausible calibration-accuracy reading
  (deviation from the certified reference standard, in parts per
  million)? Rejects nil, non-numbers, negative values, and values
  beyond the real-world calibration-tolerance ceiling -- a fabricated
  or sensor-error reading, never let through as a real, passing
  calibration-check result."
  [ppm]
  (and (number? ppm)
       (>= (double ppm) calibration-accuracy-ppm-min)
       (<= (double ppm) calibration-accuracy-ppm-max)))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch calibration/assembly/test
  defect-rate reading? Rejects nil, non-numbers, negative values, and
  values beyond `defect-rate-max-percent` -- a fabricated or sensor-
  error reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's. And NEVER a NIST-traceable calibration certificate -- this
  actor is never the certifying authority (see README `What this
  actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  calibration-bench/assembly-line/test-bench-equipment maintenance
  window against a verified, registered piece of equipment. Pure
  function -- does not actuate the calibration/assembly/test-bench
  equipment or execute any maintenance; it builds the RECORD a plant
  coordinator would keep. `measctrlmfg.governor` independently
  re-verifies the equipment's own verified/registered ground truth,
  and permanently blocks any attempt to directly actuate calibration/
  assembly/test-bench equipment (see README `Actuation`), before this
  is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound measuring/testing/navigating/control-equipment shipment
  against a verified, registered production batch. Pure function --
  does not dispatch any real freight carrier; it builds the RECORD a
  plant coordinator would keep. `measctrlmfg.governor` independently
  re-verifies the shipment's own claimed quantity against
  `shipment-quantity-exceeded?`, before this is ever allowed to
  commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
