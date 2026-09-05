(ns measctrlmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave 5): this repo previously had NO demo page and no generator at
  all. This namespace drives the REAL actor stack
  (`measctrlmfg.operation` -> `measctrlmfg.governor` ->
  `measctrlmfg.store`) via `langgraph.graph/run*` and renders the page
  from what that run actually left in the store -- no hand-typed ids,
  no hand-typed statuses, no hand-typed hold reasons.

  INPUT PROVENANCE. Every ground-truth entity this scenario names
  already exists in this repo's own seed (`measctrlmfg.store/
  sample-data!`, which is what `measctrlmfg.store/mem-store` is seeded
  with): batches `batch-001` / `batch-002` / `batch-003` and equipment
  `calibration-bench-001` / `test-bench-002`. Nothing else is asserted
  as pre-existing. The maintenance / safety-concern / shipment subject
  ids (`mnt-1`, `mnt-4`, `concern-1`, `ship-1` ...) are NOT seed
  lookups and are not claimed to be: they are the ids of the DRAFT
  records this run creates, which is precisely what
  `measctrlmfg.registry/register-maintenance` and `register-shipment`
  exist to build (the seeded store starts with `:maintenance {}` and
  `:shipments {}`). Their ground truth -- the equipment or batch each
  draft is checked against -- is always a seeded id. Scenario shape and
  literal values (dates, maintenance type, severity, unit counts) are
  taken from this repo's own demo driver `measctrlmfg.sim`
  (`clojure -M:dev:run`), which was run BEFORE this file was written to
  confirm it produces a sensible ledger against the real seed.

  WHAT EACH SUBJECT EXERCISES
    batch-001              clean lifecycle head: a governor-clean,
                           high-confidence `:log-production-batch` --
                           the ONLY op in any phase's `:auto` set --
                           auto-commits with no human in the loop.
    mnt-1                  full escalate -> human approval -> commit
                           against the seeded, verified+registered
                           `calibration-bench-001`; then re-proposed
                           later to fire `:already-scheduled`.
    concern-1              `:flag-safety-concern` is ALWAYS high-stakes
                           (`:coordination/safety-concern`), so it
                           escalates even though the governor is clean;
                           approved -> commit.
    ship-1                 `:coordinate-shipment` of 500 units against
                           seeded `batch-001` (recorded quantity 5000,
                           shipped 1000) -- inside capacity, escalates,
                           approved -> commit. Completes the clean
                           lifecycle: the batch's own `:shipped-units`
                           ground truth is advanced by the commit.
    mnt-4                  the human REJECTS. Reaches `:approval-
                           rejected` -- the third and last fact type
                           this store ever appends (see `status-cell`).
    mnt-2                  HARD `:equipment-not-verified` -- seeded
                           `test-bench-002` is unverified/unregistered.
    ship-2                 HARD `:batch-not-verified` -- seeded
                           `batch-003` is unverified/unregistered.
    ship-3                 HARD `:shipment-quantity-exceeded` -- seeded
                           `batch-002` has recorded quantity 800 and
                           750 already shipped; the governor
                           independently recomputes and refuses 100.
    mnt-3                  HARD `:actuate-equipment-blocked` --
                           PERMANENT, never reaches a human.
    batch-002              HARD `:metrology-certification-authority-
                           blocked` (PERMANENT), then HARD
                           `:not-propose-effect` (a mis-wired caller),
                           then HARD `:unknown-op`.
    batch-003              HARD `:invalid-instrument-class`,
                           `:invalid-calibration-accuracy-ppm`,
                           `:invalid-defect-rate` -- fabricated /
                           sensor-error batch data rejected outright.

  DETERMINISM. The advisor is `measctrlmfg.advisor/mock-advisor` (no
  network), the store is a fresh in-memory seed, and nothing in the
  page is a timestamp or a generated id. Two consecutive runs are
  byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [measctrlmfg.store :as store]
            [measctrlmfg.registry :as registry]
            [measctrlmfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store (`store/mem-store` + `store/sample-data!`)
  through the scenario documented in the namespace docstring: one full
  clean lifecycle, one human rejection, and ten HARD holds covering
  every distinct rule this governor can fire. Returns the resulting
  store -- every field `render` reads below is real governor/store
  output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean lifecycle (all ground truth from the seed) ---
    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                       :patch {:instrument-class :process-control-instrument
                               :last-assessed "2026-07-14"}})

    (exec! actor "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                       :value {:equipment-id "calibration-bench-001"
                               :maintenance-type :tool-inspection
                               :scheduled-date "2026-08-01"
                               :actuate-equipment? false}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:equipment-id "calibration-bench-001"
                               :severity :moderate
                               :description "校正ドリフト兆候、電気安全再確認要"}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                       :value {:batch-id "batch-001" :units 500.0
                               :destination "buyer-industrial-instrumentation-north"}})
    (approve! actor "t4")

    ;; --- the human says no ---
    (exec! actor "t5" {:op :schedule-maintenance :effect :propose :subject "mnt-4"
                       :value {:equipment-id "calibration-bench-001"
                               :maintenance-type :recalibration-window
                               :scheduled-date "2026-09-15"
                               :actuate-equipment? false}})
    (reject! actor "t5")

    ;; --- HARD holds, one request per rule ---
    (exec! actor "t6" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                       :value {:equipment-id "test-bench-002"
                               :maintenance-type :collimation-check
                               :scheduled-date "2026-08-01"
                               :actuate-equipment? false}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                       :value {:batch-id "batch-003" :units 100.0
                               :destination "buyer-industrial-instrumentation-south"}})

    (exec! actor "t8" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                       :value {:batch-id "batch-002" :units 100.0
                               :destination "buyer-industrial-instrumentation-east"}})

    (exec! actor "t9" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                       :value {:equipment-id "calibration-bench-001"
                               :maintenance-type :force-run
                               :scheduled-date "2026-09-01"
                               :actuate-equipment? true}})

    (exec! actor "t10" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "calibration-bench-001"
                                :maintenance-type :tool-inspection
                                :scheduled-date "2026-08-01"
                                :actuate-equipment? false}})

    (exec! actor "t11" {:op :log-production-batch :effect :propose :subject "batch-002"
                        :patch {:issue-nist-traceable-calibration-certificate? true}})

    (exec! actor "t12" {:op :log-production-batch :effect :direct-write :subject "batch-002"
                        :patch {:instrument-class :meter}})

    (exec! actor "t13" {:op :actuate-calibration-bench :effect :propose :subject "batch-002"})

    (exec! actor "t14" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:instrument-class :spectacle-lens}})

    (exec! actor "t15" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:calibration-accuracy-ppm 999999.0}})

    (exec! actor "t16" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:defect-rate-percent 999.0}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm
  "`name` for the mixed keyword/string values the ledger's `:basis`
  carries (`:cites` is a vector of field keywords for a batch patch,
  and of entity-id strings for a maintenance/shipment/concern draft)."
  [v]
  (cond (nil? v) "" (keyword? v) (name v) :else (str v)))

(defn- fmt [v]
  (if (nil? v) "<span class=\"muted\">—</span>" (esc v)))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell
  "Renders the last ledger fact for `subject`.

  Branches ONLY on the three fact types `measctrlmfg.store` is ever
  actually asked to append: `:committed` (the `:commit` node) and
  `:governor-hold` / `:approval-rejected` (the `:hold` node, which
  filters its audit for exactly that pair). `:approval-granted` and
  `:approval-requested` are deliberately NOT branched on -- they exist
  only on the in-memory `:audit` channel in `measctrlmfg.operation`
  and never reach `store/append-ledger!`, so a branch on them would be
  dead code that reads like a rendered state."
  [ledger subject]
  (let [f (last-fact-for ledger subject)
        rule (fn [] (-> f :violations first :rule))]
    (cond
      (nil? f) "<span class=\"muted\">no activity this run</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold · " (esc (nm (rule))) "</span>")
      (= :approval-rejected (:t f))
      (str "<span class=\"warn\">approval rejected · " (esc (nm (rule))) "</span>")
      :else "<span class=\"muted\">in progress</span>")))

(defn- gate-cell [ready?]
  (if ready?
    "<span class=\"ok\">verified + registered</span>"
    "<span class=\"critical\">unverified / unregistered</span>"))

(defn- batch-row
  [ledger {:keys [id instrument-class lot-number calibration-accuracy-ppm
                  quantity-units shipped-units defect-rate-percent last-assessed]
           :as b}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (fmt (nm instrument-class)) (fmt lot-number)
          (fmt quantity-units) (fmt shipped-units) (fmt defect-rate-percent)
          (fmt calibration-accuracy-ppm)
          (fmt last-assessed)
          (gate-cell (registry/batch-ready? b))
          (status-cell ledger id)))

(defn- equipment-row
  [{:keys [id kind last-maintenance-date last-scheduled-maintenance-date] :as e}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (fmt (nm kind))
          (gate-cell (registry/equipment-ready? e))
          (fmt last-maintenance-date)
          (fmt last-scheduled-maintenance-date)))

(defn- maintenance-row
  [ledger {:keys [id equipment-id maintenance-type scheduled-date
                  maintenance-number scheduled?]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>")
          (esc id) (fmt equipment-id) (fmt (nm maintenance-type))
          (fmt scheduled-date) (fmt maintenance-number)
          (if scheduled? "<span class=\"ok\">scheduled</span>"
              "<span class=\"muted\">draft</span>")
          (status-cell ledger id)))

(defn- shipment-row
  [ledger {:keys [id batch-id units destination shipment-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
               "<td class=\"num\">%s</td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>")
          (esc id) (fmt batch-id) (fmt units) (fmt destination)
          (fmt shipment-number) (status-cell ledger id)))

(defn- concern-row
  [ledger {:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (fmt equipment-id) (fmt (nm severity)) (fmt description)
          (status-cell ledger id)))

(defn- hold-rows
  "One row per violation on every hold fact in the ledger -- the rule
  keyword and the governor's own detail string, verbatim."
  [ledger]
  (for [{:keys [t op subject violations]} ledger
        :when (#{:governor-hold :approval-rejected} t)
        v violations]
    (format (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
                 "<td><code>%s</code></td><td>%s</td></tr>")
            (if (= :governor-hold t)
              "<span class=\"critical\">HARD hold</span>"
              "<span class=\"warn\">approval rejected</span>")
            (esc (nm op)) (esc subject) (esc (nm (:rule v)))
            (fmt (:detail v)))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td></tr>")
          (esc (nm t)) (esc (nm op)) (esc subject)
          (esc (nm disposition))
          (esc (str/join ", " (map nm basis)))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own CLOSED op contract --
  ;; `measctrlmfg.governor/allowed-ops`, `measctrlmfg.phase/phases`
  ;; and README `Ops`. This is documentation of fixed code, not
  ;; runtime telemetry, so it is legitimately hand-described here
  ;; rather than derived from a live run. Every OTHER cell on this
  ;; page comes from the run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean and high-confidence — the only member of any phase's <code>:auto</code> set</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval · never auto at any phase · equipment verified+registered re-derived independently · direct actuation permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval · <code>:coordination/safety-concern</code> is permanently high-stakes · never gated on the equipment being verified</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">phase-3: human approval · batch verified+registered re-derived independently · shipped-units + claim recomputed against the batch's own recorded quantity</span></td></tr>"
   "        <tr><td colspan=\"2\"><span class=\"critical\">anything else</span> — HARD hold. The op allowlist and the proposal-effect allowlist are both closed; NIST-traceable calibration certificates and direct calibration/assembly/test-bench actuation are permanently outside this actor's authority, with no phase and no human override.</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        maintenance (store/all-maintenance db)
        shipments (map #(store/shipment db (get % "shipment_id"))
                       (store/shipment-history db))
        concerns (store/safety-concerns db)]
    (str
     "<!doctype html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2651 · measuring/testing/navigating/control equipment — Operator Console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Measuring, testing, navigating &amp; control equipment manufacture (ISIC 2651) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance scheduling &amp; safety concerns always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot of <code>measctrlmfg.store</code> after a real actor run — generated by <code>measctrlmfg.render-html</code> (<code>clojure -M:dev:render-html</code>). The gate column is <code>measctrlmfg.registry/batch-ready?</code> re-derived from each batch's own permanent fields, never from a proposal's self-report.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Instrument class</th><th>Lot</th><th>Quantity (units)</th><th>Shipped (units)</th><th>Defect rate (%)</th><th>Calibration accuracy (ppm)</th><th>Last assessed</th><th>Ground truth</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger) batches)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Calibration-bench / assembly-line / test-bench units. <code>measctrlmfg.registry/equipment-ready?</code> must clear before any maintenance window may be scheduled against a unit; the last-scheduled column is written only by a committed <code>:maintenance/schedule</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Ground truth</th><th>Last maintenance</th><th>Last scheduled maintenance</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map equipment-row equipment)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Maintenance-schedule drafts</h2>\n"
     "    <p class=\"muted\">DRAFT windows only — this actor never actuates equipment. Draft numbers come from <code>measctrlmfg.registry/register-maintenance</code>. A held proposal mutates nothing, so it never appears here.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Maintenance</th><th>Equipment</th><th>Type</th><th>Scheduled date</th><th>Draft number</th><th>Schedule guard</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial maintenance-row ledger) maintenance)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Shipment-coordination drafts</h2>\n"
     "    <p class=\"muted\">DRAFT outbound shipments only — no freight carrier is ever dispatched. Draft numbers come from <code>measctrlmfg.registry/register-shipment</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Shipment</th><th>Batch</th><th>Units</th><th>Destination</th><th>Draft number</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial shipment-row ledger) shipments)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Safety concerns</h2>\n"
     "    <p class=\"muted\">Always high-stakes, always a human's call, and never blocked on the referenced equipment being verified — a concern may be raised about any unit.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Concern</th><th>Equipment</th><th>Severity</th><th>Description</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial concern-row ledger) concerns)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Measuring Control Equipment Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden and never reach a human.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Refusals (this run)</h2>\n"
     "    <p class=\"muted\">Every rule that actually fired, with the governor's own detail text verbatim.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Disposition</th><th>Op</th><th>Subject</th><th>Rule</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hold-rows ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log. Only three fact types ever reach it: <code>:committed</code>, <code>:governor-hold</code>, <code>:approval-rejected</code> — the approval handshake itself stays on the actor's in-memory audit channel.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>Regenerate with <code>clojure -M:dev:render-html</code>. Every id, status and refusal reason above is produced by running <code>measctrlmfg.operation</code> against a freshly seeded <code>measctrlmfg.store</code>; the page is deterministic and contains no timestamps.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "("
             (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
