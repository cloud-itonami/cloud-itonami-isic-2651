(ns measctrlmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack -- `measctrlmfg.store` (seed) ->
  `measctrlmfg.operation` (the langgraph StateGraph) ->
  `measctrlmfg.advisor` -> `measctrlmfg.governor` -> `measctrlmfg.phase`
  -- through one deterministic scenario, then renders the resulting
  store. Nothing on the page is hand-typed domain data: every batch,
  equipment unit, maintenance draft, shipment draft, safety concern,
  ledger fact, HARD-hold rule name and HARD-hold detail string is read
  back out of the store after the graph ran, and the action-gate table
  is derived from `measctrlmfg.governor/allowed-ops` +
  `measctrlmfg.phase/phases` rather than described by hand.

  Input provenance: every `:subject` driven below is either seeded by
  `measctrlmfg.store/sample-data!` (`batch-001`..`batch-003`,
  `calibration-bench-001`, `test-bench-002`) or is a NEW draft record
  the demo itself registers through the actor (`mnt-*` via
  `:schedule-maintenance`, `ship-*` via `:coordinate-shipment`,
  `concern-1` via `:flag-safety-concern`). No fabricated ids.

  Ledger fact types: `measctrlmfg.operation`'s `:commit` node appends
  `:committed` and its `:hold` node appends `:governor-hold` /
  `:approval-rejected`. `:approval-granted` and `:approval-requested`
  are written to the in-memory `:audit` channel ONLY and never reach
  `measctrlmfg.store/ledger`, so this renderer deliberately does NOT
  branch on them -- an approved op shows up in the ledger as the
  `:committed` fact that followed it.

  Determinism: no timestamps, no hashes, no wall-clock. `all-batches` /
  `all-equipment` / `all-maintenance` sort by `:id`; the ledger and the
  registry histories are append-only in graph-run order. Two
  consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [measctrlmfg.governor :as governor]
            [measctrlmfg.operation :as op]
            [measctrlmfg.phase :as phase]
            [measctrlmfg.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private coordinator
  "The injected `:context` -- same shape `measctrlmfg.sim` uses."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec!
  "One coordination request = one graph run."
  [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- resume!
  "Resume a run paused at `:request-approval` (interrupt-before) with a
  human plant supervisor's / shipping approver's decision."
  [actor tid status]
  (g/run* actor {:approval {:status status :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a freshly seeded store through every disposition this actor can
  reach, and returns the store.

  Clean path -- `batch-001` (seeded verified + registered) takes a
  phase-3 auto-commit production-batch log; `mnt-1` schedules
  maintenance against the seeded, verified + registered
  `calibration-bench-001` (`:schedule-maintenance` is never in any
  phase's `:auto` set, so it escalates even when the governor is clean
  -- approved); `concern-1` flags a safety concern (always
  `:coordination/safety-concern` high-stakes -- approved); `ship-1`
  coordinates 500 units out of `batch-001` (approved).

  Human-rejection path -- `ship-4` is governor-clean and escalates, and
  the approver REJECTS it, producing the `:approval-rejected` ledger
  fact and leaving `batch-001`'s shipped-units untouched.

  HARD holds -- one request per governor rule, each exercised directly
  rather than only via a happy path (the discipline
  `measctrlmfg.sim`'s docstring records): a caller whose own request
  `:effect` is not `:propose`; an op outside the closed allowlist; a
  maintenance window against the seeded UNVERIFIED/unregistered
  `test-bench-002`; a shipment against the seeded
  UNVERIFIED/unregistered `batch-003`; a shipment whose claimed units
  blow through `batch-002`'s own logged production quantity; an
  attempt to directly ACTUATE calibration-bench equipment; a
  double-schedule of `mnt-1`; a fabricated instrument class; an
  implausible calibration-accuracy reading; an implausible defect
  rate; and an attempt to self-issue a NIST-traceable calibration
  certificate. None of these ever reaches a human."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean path ---
    (exec! actor "t01-batch-001-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:instrument-class :process-control-instrument
                    :last-assessed "2026-07-14"}})

    (exec! actor "t02-mnt-1"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "calibration-bench-001"
                    :maintenance-type :tool-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (resume! actor "t02-mnt-1" :approved)

    (exec! actor "t03-concern-1"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "calibration-bench-001" :severity :moderate
                    :description "校正ドリフト兆候、電気安全再確認要"}})
    (resume! actor "t03-concern-1" :approved)

    (exec! actor "t04-ship-1"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 500.0
                    :destination "buyer-industrial-instrumentation-north"}})
    (resume! actor "t04-ship-1" :approved)

    ;; --- human rejection (governor clean, approver says no) ---
    (exec! actor "t05-ship-4"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :units 200.0
                    :destination "buyer-industrial-instrumentation-west"}})
    (resume! actor "t05-ship-4" :rejected)

    ;; --- HARD holds, one request per governor rule ---
    (exec! actor "t06-not-propose"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:instrument-class :process-control-instrument}})

    (exec! actor "t07-unknown-op"
           {:op :actuate-calibration-bench :effect :propose :subject "batch-001"})

    (exec! actor "t08-mnt-2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "test-bench-002"
                    :maintenance-type :collimation-check
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t09-ship-2"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-industrial-instrumentation-south"}})

    (exec! actor "t10-ship-3"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 100.0
                    :destination "buyer-industrial-instrumentation-east"}})

    (exec! actor "t11-mnt-3"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "calibration-bench-001"
                    :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! actor "t12-mnt-1-again"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "calibration-bench-001"
                    :maintenance-type :tool-inspection
                    :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "t13-bad-class"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:instrument-class :spectacle-lens}})

    (exec! actor "t14-bad-ppm"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:calibration-accuracy-ppm 999999.0}})

    (exec! actor "t15-bad-defect-rate"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:defect-rate-percent 999.0}})

    (exec! actor "t16-self-certify"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-nist-traceable-calibration-certificate? true}})

    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw
  "Keyword -> its bare name (unqualified keywords only, e.g. rule and op
  names). Use `kw-full` when the namespace part carries meaning."
  [v] (if (keyword? v) (name v) (str v)))

(defn- kw-full
  "Keyword -> `ns/name` (drops only the leading colon), so a qualified
  stake like `:coordination/safety-concern` is not silently truncated."
  [v] (if (keyword? v) (subs (str v) 1) (str v)))

(defn- yn [v]
  (if (true? v)
    "<span class=\"ok\">yes</span>"
    "<span class=\"critical\">no</span>"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"subtitle\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- ledger-derived views -----------------------------

(defn- facts-for [ledger subject]
  (filter #(= subject (:subject %)) ledger))

(defn- status-cell
  "Status of `subject` from the LAST ledger fact naming it. Only the
  three fact types `measctrlmfg.store`'s ledger actually receives are
  branched on here -- see this namespace's docstring."
  [ledger subject]
  (let [f (last (facts-for ledger subject))]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">approver rejected</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kw (:basis f)))) "</span>")
      :else (str "<span class=\"muted\">" (esc (kw (:t f))) "</span>"))))

(defn- hard-holds [ledger]
  (for [f ledger
        :when (= :governor-hold (:t f))
        v (:violations f)]
    {:subject (:subject f) :op (:op f) :rule (:rule v) :detail (:detail v)}))

;; ----------------------------- sections -----------------------------

(defn- batches-section [db ledger]
  (section
   "Production batches"
   (str "Seeded by <code>measctrlmfg.store/sample-data!</code>, then mutated only by "
        "committed <code>:batch/upsert</code> / <code>:shipment/propose</code> effects. "
        "<code>verified?</code> / <code>registered?</code> are the batch's own ground truth "
        "— <code>measctrlmfg.governor</code> re-derives them itself and never trusts the advisor's rationale.")
   (table ["Batch" "Instrument class" "Lot" "Quantity (units)" "Shipped (units)"
           "Calib. accuracy (ppm)" "Defect rate (%)" "Verified?" "Registered?"
           "Last assessed" "Last ledger fact"]
          (for [b (store/all-batches db)]
            (row (code (:id b)) (esc (kw (:instrument-class b))) (esc (:lot-number b))
                 (str "<span class=\"num\">" (esc (:quantity-units b)) "</span>")
                 (str "<span class=\"num\">" (esc (:shipped-units b)) "</span>")
                 (str "<span class=\"num\">" (esc (:calibration-accuracy-ppm b)) "</span>")
                 (str "<span class=\"num\">" (esc (:defect-rate-percent b)) "</span>")
                 (yn (:verified? b)) (yn (:registered? b))
                 (esc (:last-assessed b))
                 (status-cell ledger (:id b)))))))

(defn- equipment-section [db]
  (section
   "Plant equipment"
   (str "Calibration-bench / assembly-line / test-bench units. Maintenance may only be scheduled "
        "against a unit that is BOTH verified and registered "
        "(<code>measctrlmfg.registry/equipment-ready?</code>); "
        "<code>:last-scheduled-maintenance-date</code> is written by a committed "
        "<code>:maintenance/schedule</code> effect.")
   (table ["Equipment" "Kind" "Verified?" "Registered?" "Last maintenance" "Last scheduled window"]
          (for [e (store/all-equipment db)]
            (row (code (:id e)) (esc (kw (:kind e)))
                 (yn (:verified? e)) (yn (:registered? e))
                 (esc (or (:last-maintenance-date e) "—"))
                 (esc (or (:last-scheduled-maintenance-date e) "—")))))))

(defn- maintenance-section [db ledger]
  (section
   "Maintenance schedule drafts"
   (str "DRAFT windows built by <code>measctrlmfg.registry/register-maintenance</code>. "
        "This actor never actuates equipment — it only proposes the record a plant coordinator keeps. "
        "The <code>:scheduled?</code> flag is the dedicated double-schedule guard.")
   (table ["Maintenance" "Equipment" "Type" "Scheduled date" "Record no." "Scheduled?" "Last ledger fact"]
          (for [m (store/all-maintenance db)]
            (row (code (:id m)) (code (:equipment-id m)) (esc (kw (:maintenance-type m)))
                 (esc (:scheduled-date m)) (code (:maintenance-number m))
                 (yn (:scheduled? m))
                 (status-cell ledger (:id m)))))))

(defn- shipments-section [db ledger]
  (section
   "Shipment coordination drafts"
   (str "DRAFT outbound shipments built by <code>measctrlmfg.registry/register-shipment</code>, "
        "listed in append-only <code>shipment-history</code> order. "
        "The governor independently recomputes each batch's remaining headroom before any of these commit.")
   (table ["Shipment" "Batch" "Units" "Destination" "Record no." "Last ledger fact"]
          (for [r (store/shipment-history db)
                :let [s (store/shipment db (get r "shipment_id"))]]
            (row (code (:id s)) (code (:batch-id s))
                 (str "<span class=\"num\">" (esc (:units s)) "</span>")
                 (esc (:destination s)) (code (:shipment-number s))
                 (status-cell ledger (:id s)))))))

(defn- concerns-section [db ledger]
  (section
   "Safety concerns"
   (str "Always <code>:coordination/safety-concern</code> high-stakes — "
        "<code>measctrlmfg.governor</code> escalates every one of these to a human regardless of "
        "confidence, and no phase ever puts <code>:flag-safety-concern</code> in its <code>:auto</code> set.")
   (table ["Concern" "Equipment" "Severity" "Description" "Last ledger fact"]
          (for [c (store/safety-concerns db)]
            (row (code (:id c)) (code (:equipment-id c)) (esc (kw (:severity c)))
                 (esc (:description c))
                 (status-cell ledger (:id c)))))))

(defn- gate-section []
  (let [ph phase/default-phase
        {:keys [writes]} (get phase/phases ph)
        auto-anywhere (into #{} (mapcat (comp :auto val)) phase/phases)]
    (section
     (str "Action gate — phase " ph " (" (esc (:label (get phase/phases ph))) ")")
     (str "Derived from <code>measctrlmfg.governor/allowed-ops</code> and "
          "<code>measctrlmfg.phase/phases</code>, not hand-described. "
          "Confidence floor <span class=\"num\">" governor/confidence-floor "</span>; "
          "high-stakes set <code>" (esc (str/join ", " (map kw-full (sort-by kw-full governor/high-stakes)))) "</code>. "
          "A governor HARD violation always stays a HOLD — no phase and no human approval overrides it.")
     (table ["Op" "Writes at this phase?" "Auto-commit gate"]
            (for [o (sort-by kw governor/allowed-ops)]
              (row (code o)
                   (yn (contains? writes o))
                   (cond
                     (not (contains? auto-anywhere o))
                     "<span class=\"warn\">ALWAYS human approval — never auto at ANY phase</span>"
                     (contains? (:auto (get phase/phases ph)) o)
                     "<span class=\"ok\">auto-commit when governor-clean</span>"
                     :else
                     "<span class=\"warn\">human approval at this phase</span>")))))))

(defn- holds-section [ledger]
  (let [holds (hard-holds ledger)]
    (section
     (str "Governor HARD holds this run (" (count holds) ")")
     (str "Every row below is a real <code>measctrlmfg.governor</code> violation map produced by the "
          "governor's own rules against deliberately non-compliant input — rule name and detail text "
          "are read straight out of the ledger fact, never hardcoded here. "
          "None of these reached a human: a HARD hold short-circuits the approval node entirely.")
     (table ["Subject" "Op" "Rule" "Governor detail"]
            (for [h holds]
              (row (code (:subject h)) (code (kw (:op h)))
                   (str "<span class=\"critical\">" (esc (kw (:rule h))) "</span>")
                   (esc (:detail h))))))))

(defn- ledger-section [ledger]
  (section
   (str "Audit ledger (" (count ledger) " facts)")
   (str "The append-only decision log <code>measctrlmfg.store</code> received during this run. "
        "<code>:approval-granted</code> / <code>:approval-requested</code> are audit-channel-only and "
        "never appear here — an approved op lands as the <code>:committed</code> fact that followed it.")
   (table ["#" "Fact" "Op" "Subject" "Disposition" "Basis"]
          (map-indexed
           (fn [i {:keys [t op subject disposition basis]}]
             (row (str "<span class=\"num\">" (inc i) "</span>")
                  (esc (kw t)) (code (kw (or op :n-a))) (code subject)
                  (esc (kw (or disposition "")))
                  (esc (str/join ", " (map kw basis)))))
           ledger))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a store `db` that has
  already been driven by `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "  <head>\n"
     "    <meta charset=\"utf-8\">\n"
     "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "    <meta name=\"color-scheme\" content=\"light\">\n"
     "    <title>Operator console · cloud-itonami-isic-2651 · measctrlmfg</title>\n"
     "    <style>\n" (skin/dds+skin) "\n"
     "table{border-collapse:collapse;width:100%;font-size:.875rem;margin:0}\n"
     "th,td{text-align:left;padding:.45rem .6rem;border-bottom:1px solid var(--color-neutral-solid-gray-200);vertical-align:top}\n"
     "th{font-weight:700;color:var(--color-neutral-solid-gray-700);white-space:nowrap}\n"
     ".ok{color:var(--color-primitive-green-900);font-weight:700}\n"
     ".warn{color:var(--color-primitive-orange-900,#8a5000);font-weight:700}\n"
     ".critical{color:var(--color-primitive-red-900,#b41010);font-weight:700}\n"
     ".muted{color:var(--color-neutral-solid-gray-600)}\n"
     ".card{overflow-x:auto}\n"
     "    </style>\n"
     "  </head>\n"
     "  <body>\n"
     "    <div class=\"dds-ext-container\">\n"
     "      <header class=\"bar\">\n"
     "        <span class=\"badge\">ISIC 2651</span>\n"
     "        <strong>Manufacture of measuring, testing, navigating and control equipment — Operator Console</strong>\n"
     "      </header>\n"
     "      <p class=\"subtitle\">Read-only build-time sample, generated by "
     "<code>clojure -M:dev:render-html</code> (<code>measctrlmfg.render-html</code>). "
     "Every value below was produced by driving the REAL actor "
     "(<code>measctrlmfg.operation</code> — a langgraph StateGraph — through "
     "<code>measctrlmfg.advisor</code>, <code>measctrlmfg.governor</code> and "
     "<code>measctrlmfg.phase</code>) over the seeded "
     "<code>measctrlmfg.store</code>. No mock output, no invented ids, no timestamps: "
     "re-running the generator produces a byte-identical file.</p>\n"
     "<main>\n"
     (batches-section db ledger)
     (equipment-section db)
     (maintenance-section db ledger)
     (shipments-section db ledger)
     (concerns-section db ledger)
     (gate-section)
     (holds-section ledger)
     (ledger-section ledger)
     "</main>\n"
     "      <footer>\n"
     "        <p>cloud-itonami-isic-2651 · <code>measctrlmfg</code> · governed actor: the advisor proposes, "
     "the Measuring Control Equipment Plant Operations Governor disposes. "
     "This actor never actuates calibration/assembly/test-bench equipment, never dispatches freight, "
     "and never self-issues a NIST-traceable calibration certificate.</p>\n"
     "      </footer>\n"
     "    </div>\n"
     "  </body>\n"
     "</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        f (java.io.File. ^String out)]
    (some-> (.getParentFile f) .mkdirs)
    (spit f (render db) :encoding "UTF-8")
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-maintenance db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
