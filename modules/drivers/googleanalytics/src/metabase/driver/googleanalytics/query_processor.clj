(ns metabase.driver.googleanalytics.query-processor
  "The Query Processor is responsible for translating the Metabase Query Language into Google Analytics request format.
  See https://developers.google.com/analytics/devguides/reporting/core/v3"
  (:require [clojure.string :as str]
            [java-time :as t]
            [metabase.mbql.util :as mbql.u]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.util.date-2 :as u.date]
            [metabase.util.i18n :as ui18n :refer [deferred-tru tru]]
            [metabase.util.schema :as su]
            [schema.core :as s]))

(def ^:private ^:const earliest-date "2005-01-01")
(def ^:private ^:const latest-date   "today")
(def ^:private ^:const max-rows-maximum 10000)

(defmulti ^:private ->rvalue mbql.u/dispatch-by-clause-name-or-class)

(defmethod ->rvalue nil [_] nil)

(defmethod ->rvalue Object [this] this)

(defmethod ->rvalue :field-id
  [[_ field-id]]
  (:name (qp.store/field field-id)))

(defmethod ->rvalue :field-literal
  [[_ field-name]]
  field-name)

(defmethod ->rvalue :datetime-field
  [[_ field]]
  (->rvalue field))

;; TODO - I think these next two methods are no longer used, since `->date-range` handles these clauses
(defmethod ->rvalue :absolute-datetime
  [[_ t unit]]
  (t/format "yyyy-MM-dd" (u.date/truncate t unit)))

(defmethod ->rvalue :relative-datetime
  [[_ amount unit]]
  (cond
    (and (= unit :day) (= amount 0))  "today"
    (and (= unit :day) (= amount -1)) "yesterday"
    (and (= unit :day) (< amount -1)) (str (- amount) "daysAgo")

    :else
    (t/format
     "yyyy-MM-dd"
     (u.date/truncate (u.date/add unit amount) unit))))

(defmethod ->rvalue :value
  [[_ value _]]
  value)

(defn- char-escape-map
  "Generate a map of characters to escape to their escaped versions."
  [chars-to-escape]
  (into {} (for [c chars-to-escape]
             {c (str "\\" c)})))

(defn- escape-for-regex [s]
  ;; See https://support.google.com/analytics/answer/1034324?hl=en for list of regex special characters
  ;; TODO -- I'm not sure we need to escape everything we're escaping here
  (str/escape s (char-escape-map ".\\+*?[^]$(){}=!<>|:")))

(defn- escape-for-filter-clause [s]
  (str/escape s (char-escape-map ",;\\")))

(defn- ga-filter ^String [& parts]
  (escape-for-filter-clause (apply str parts)))


;;; -------------------------------------------------- source-table --------------------------------------------------

(defn- handle-source-table [{source-table-id :source-table}]
  (let [{source-table-name :name} (qp.store/table source-table-id)]
    {:ids (str "ga:" source-table-name)}))


;;; -------------------------------------------------- aggregation ---------------------------------------------------

(defn- handle-aggregation
  [{ags :aggregation}]
  (when (seq ags)
    {:metrics (str/join "," (mbql.u/match ags [:metric (metric-name :guard string?)] metric-name))}))


;;; ---------------------------------------------------- breakout ----------------------------------------------------


(defn- unit->ga-dimension
  [unit]
  (case unit
    :minute-of-hour "ga:minute"
    :hour           "ga:dateHour"
    :hour-of-day    "ga:hour"
    :day            "ga:date"
    :day-of-week    "ga:dayOfWeek"
    :day-of-month   "ga:day"
    :week           "ga:yearWeek"
    :iso-week       "ga:isoYearIsoWeek"
    :week-of-year   "ga:week"
    :month          "ga:yearMonth"
    :month-of-year  "ga:month"
    :year           "ga:year"))

(defn- handle-breakout [{breakout-clause :breakout}]
  {:dimensions (if-not breakout-clause
                 ""
                 (str/join "," (for [breakout-field breakout-clause]
                                 (mbql.u/match-one breakout-field
                                   [:datetime-field _ unit] (unit->ga-dimension unit)
                                   _                        (->rvalue &match)))))})


;;; ----------------------------------------------------- filter -----------------------------------------------------

(defmulti ^:private compile-filter mbql.u/dispatch-by-clause-name-or-class)

(defmethod compile-filter nil [& _]
  nil)

(defmethod compile-filter :contains
  [[_ field value {:keys [case-sensitive], :or {case-sensitive true}}]]
  (ga-filter (->rvalue field) "=~" (if case-sensitive "(?-i)" "(?i)") (escape-for-regex (->rvalue value))))

(defmethod compile-filter :starts-with
  [[_ field value {:keys [case-sensitive], :or {case-sensitive true}}]]
  (ga-filter (->rvalue field) "=~" (if case-sensitive "(?-i)" "(?i)") \^ (escape-for-regex (->rvalue value))))

(defmethod compile-filter :ends-with
  [[_ field value {:keys [case-sensitive], :or {case-sensitive true}}]]
  (ga-filter (->rvalue field) "=~" (if case-sensitive "(?-i)" "(?i)") (escape-for-regex (->rvalue value)) \$))

(defmethod compile-filter :=
  [[_ field value]]
  (ga-filter (->rvalue field) "==" (->rvalue value)))

(defmethod compile-filter :!=
  [[_ field value]]
  (ga-filter (->rvalue field) "!=" (->rvalue value)))

(defmethod compile-filter :>
  [[_ field value]]
  (ga-filter (->rvalue field) ">" (->rvalue value)))

(defmethod compile-filter :<
  [[_ field value]]
  (ga-filter (->rvalue field) "<" (->rvalue value)))

(defmethod compile-filter :>=
  [[_ field value]]
  (ga-filter (->rvalue field) ">=" (->rvalue value)))

(defmethod compile-filter :<=
  [[_ field value]]
  (ga-filter (->rvalue field) "<=" (->rvalue value)))

(defmethod compile-filter :between
  [[_ field min-val max-val]]
  (str (ga-filter (->rvalue field) ">=" (->rvalue min-val))
       ";"
       (ga-filter (->rvalue field) "<=" (->rvalue max-val))))

(defmethod compile-filter :and
  [[_ & clauses]]
  (str/join ";" (filter seq (map compile-filter clauses))))

(defmethod compile-filter :or
  [[_ & clauses]]
  (str/join "," (filter seq (map compile-filter clauses))))

(defmethod compile-filter :not
  [[_ clause]]
  (str "!" (compile-filter clause)))

(defn- compile-filter:filters [{filter-clause :filter}]
  (when filter-clause
    ;; remove all clauses that operate on datetime fields or built-in segments because we don't want to handle them
    ;; here, we'll do that seperately with the filter:interval and compile-filter:built-in-segment stuff below
    ;;
    ;; (Recall that `auto-bucket-datetimes` guarantees all datetime Fields will be wrapped by `:datetime-field`
    ;; clauses in a fully-preprocessed query.)
    (let [filter-str (compile-filter (mbql.u/replace filter-clause
                                       [:segment (_ :guard mbql.u/ga-id?)] nil
                                       [_ [:datetime-field & _] & _] nil))]

      (when-not (str/blank? filter-str)
        {:filters filter-str}))))

;;; ----------------------------------------------- filter (intervals) -----------------------------------------------

(defn- format-range [{:keys [start end]}]
  (merge
   (when start
     {:start-date (t/format "yyyy-MM-dd" start)})
   (when end
     {:end-date (t/format "yyyy-MM-dd" end)})))

(defmulti ^:private ->date-range
  {:arglists '([unit comparison-type x])}
  (fn [_ _ x]
    (mbql.u/dispatch-by-clause-name-or-class x)))

(defmethod ->date-range :default
  [_ _ x]
  {:start-date (->rvalue x), :end-date (->rvalue x)})

(s/defn ^:private day-date-range :- (s/maybe {(s/optional-key :start-date) s/Str, (s/optional-key :end-date) s/Str})
  [comparison-type :- s/Keyword n :- s/Int]
  ;; since GA is normally inclusive add 1 to `:<` or `:>` filters so it starts and ends on the correct date
  ;; e.g
  ;;
  ;;    [:> ... [:relative-datetime -30 :day]]
  ;;    => (day-date-range :> -30)
  ;;    => include events whose day is > 30 days ago
  ;;    => include events whose day is >= 29 days ago
  ;;    => {:start-date "29daysAgo)}
  ;;
  ;;     (day-date-range :< 0)
  ;;     => include events whose day is < 0 days ago
  ;;     => include events whose day is < TODAY
  ;;     => include events whose day is <= YESTERDAY
  ;;     => {:end-date "yesterday"}
  ;;
  ;;     (day-date-range :> 0)
  ;;     => include events whose day is > 0 days ago
  ;;     => include events whose day is > TODAY
  ;;     => include events whose day is >= TOMORROW
  ;;     => nil (future dates aren't handled by this fn)
  (let [n (case comparison-type
            :< (dec n)
            :> (inc n)
            n)]
    (when-not (pos? n)
      (let [special-amount (cond
                             (zero? n) "today"
                             (= n -1)  "yesterday"
                             (neg? n)  (format "%ddaysAgo" (- n)))]
        (case comparison-type
          (:< :<=) {:end-date special-amount}
          (:> :>=) {:start-date special-amount}
          :=       {:start-date special-amount, :end-date special-amount}
          nil)))))

(defmethod ->date-range :relative-datetime
  [unit comparison-type [_ n relative-datetime-unit :as clause]]
  (or (when (= relative-datetime-unit :day)
        (day-date-range comparison-type n))
      (let [now (qp.timezone/now :googleanalytics nil :use-report-timezone-id-if-unsupported? true)
            t   (u.date/truncate now relative-datetime-unit)
            t   (u.date/add t relative-datetime-unit n)]
        (format-range (u.date/comparison-range t unit comparison-type {:end :inclusive, :resolution :day})))))

(defmethod ->date-range :absolute-datetime
  [unit comparison-type [_ t]]
  (format-range (u.date/comparison-range t unit comparison-type {:end :inclusive, :resolution :day})))

(defn- field->unit [field]
  (or (mbql.u/match-one field
        [:datetime-field _ unit] unit)
      :day))

(defmulti ^:private compile-filter:interval
  {:arglists '([filter-clause])}
  mbql.u/dispatch-by-clause-name-or-class)

(defmethod compile-filter:interval :default [_] nil)

(defmethod compile-filter:interval :>
  [[_ field x]]
  (select-keys (->date-range (field->unit field) :> x) [:start-date]))

(defmethod compile-filter:interval :<
  [[_ field x]]
  (select-keys (->date-range (field->unit field) :< x) [:end-date]))

(defmethod compile-filter:interval :>=
  [[_ field x]]
  (select-keys (->date-range (field->unit field) :>= x) [:start-date]))

(defmethod compile-filter:interval :<=
  [[_ field x]]
  (select-keys (->date-range (field->unit field) :<= x) [:end-date]))

(defmethod compile-filter:interval :=
  [[_ field x]]
  (->date-range (field->unit field) := x))


;; MBQL :between is INCLUSIVE just like SQL !!!
(defmethod compile-filter:interval :between
  [[_ field min-val max-val]]
  (merge
   (compile-filter:interval [:>= field min-val])
   (compile-filter:interval [:<= field max-val])))


;;; Compound filters

(defn- maybe-get-only-filter-or-throw [filters]
  (when-let [filters (seq (filter some? filters))]
    (when (> (count filters) 1)
      (throw (Exception. (tru "Multiple date filters are not supported"))))
    (first filters)))

(defn- try-reduce-filters [[filter1 filter2]]
  (merge-with
    (fn [_ _] (throw (Exception. (str (deferred-tru "Multiple date filters are not supported in filters: ") filter1 filter2))))
    filter1 filter2))

(defmethod compile-filter:interval :and
  [[_ & subclauses]]
  (let [filters (mapv compile-filter:interval subclauses)]
    (if (= (count filters) 2)
      (try-reduce-filters filters)
      (maybe-get-only-filter-or-throw filters))))

(defmethod compile-filter:interval :or
  [[_ & subclauses]]
  (maybe-get-only-filter-or-throw (mapv compile-filter:interval subclauses)))

(defmethod compile-filter:interval :not
  [[& _]]
  (throw (Exception. (tru ":not is not yet implemented"))))

(defn- remove-non-datetime-filter-clauses
  "Replace any filter clauses that operate on a non-datetime Field with `nil`."
  [filter-clause]
  (mbql.u/replace filter-clause
    ;; we don't support any of the following as datetime filters
    #{:!= :starts-with :ends-with :contains}
    nil

    [(_ :guard #{:< :> :<= :>= :between :=}) [(_ :guard (partial not= :datetime-field)) & _] & _]
    nil))

(defn- normalize-unit [unit]
  (if (= unit :default) :day unit))

(defn- normalize-datetime-units
  "Replace all unsupported datetime units with the default"
  [filter-clause]
  (mbql.u/replace filter-clause
    [:datetime-field field unit]        [:datetime-field field (normalize-unit unit)]
    [:absolute-datetime timestamp unit] [:absolute-datetime timestamp (normalize-unit unit)]
    [:relative-datetime amount unit]    [:relative-datetime amount (normalize-unit unit)]))

(defn- add-start-end-dates [filter-clause]
  (merge {:start-date earliest-date, :end-date latest-date} filter-clause))

(defn- compile-filter:interval
  "Handle datetime filter clauses. (Anything that *isn't* a datetime filter will be removed by the
  `handle-builtin-segment` logic)."
  [{filter-clause :filter}]
  (or (when filter-clause
        (add-start-end-dates
          (compile-filter:interval
            (normalize-datetime-units
              (remove-non-datetime-filter-clauses filter-clause)))))
      {:start-date earliest-date, :end-date latest-date}))


;;; ------------------------------------------- filter (built-in segments) -------------------------------------------

(s/defn ^:private built-in-segment :- (s/maybe su/NonBlankString)
  [{filter-clause :filter}]
  (let [segments (mbql.u/match filter-clause [:segment (segment-name :guard mbql.u/ga-id?)] segment-name)]
    (when (> (count segments) 1)
      (throw (Exception. (tru "Only one Google Analytics segment allowed at a time."))))
    (first segments)))

(defn- compile-filter:built-in-segment
  "Handle a built-in GA segment (a `[:segment <ga id>]` filter clause), if present. This is added to the native query
  under a separate `:segment` key."
  [inner-query]
  (when-let [built-in-segment (built-in-segment inner-query)]
    {:segment built-in-segment}))


;;; ---------------------------------------------------- order-by ----------------------------------------------------

(defn- handle-order-by [{:keys [order-by], :as query}]
  (when order-by
    {:sort (str/join
            ","
            (filter
             some?
             (for [[direction field] order-by]
               (str (case direction
                      :asc  ""
                      :desc "-")
                    (mbql.u/match-one field
                      [:datetime-field _ unit] (unit->ga-dimension unit)
                      [:aggregation index]     (mbql.u/aggregation-at-index query index)
                      [& _]                    (->rvalue &match))))))}))


;;; ----------------------------------------------------- limit ------------------------------------------------------

(defn- handle-limit [{limit-clause :limit}]
  {:max-results (int (if (nil? limit-clause)
                       10000
                       limit-clause))})

(defn mbql->native
  "Transpile MBQL query into parameters required for a Google Analytics request."
  [{inner-query :query, :as raw}]
  {:query (into
           ;; set to false to match behavior of other drivers
           {:include-empty-rows false}
           (for [f [handle-source-table
                    handle-aggregation
                    handle-breakout
                    compile-filter:interval
                    compile-filter:filters
                    compile-filter:built-in-segment
                    handle-order-by
                    handle-limit]]
             (f inner-query)))
   :mbql? true})
