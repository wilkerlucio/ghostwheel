;; Copyright (c) George Lipov. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 2.0 (https://choosealicense.com/licenses/epl-2.0/)
;; which can be found in the file LICENSE at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ghostwheel.core
  #?(:cljs (:require-macros ghostwheel.core))
  (:require [cuerdas.core :as cs]
            [clojure.set :refer [union difference map-invert]]
            [clojure.walk :as walk]
            [clojure.pprint :refer [pprint]]
            [clojure.test :as t]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [clojure.spec.gen.alpha :as gen]
            [clairvoyant.core :as cv :include-macros true]
            [ghostwheel.reporting :as r]
            [ghostwheel.utils :as u :refer [cljs-env? get-ghostwheel-compiler-config
                                            get-ns-meta get-ns-name clj->cljs DBG]]
            [ghostwheel.tracing :as tr :refer [pr-clog get-styled-label clog]]
            #?@(:clj  [[clojure.core.specs.alpha]
                       [orchestra.spec.test :as ost]
                       #_[com.rpl.specter
                          :refer [setval transform select select-any
                                  filterer nthpath ALL MAP-VALS MAP-KEYS NAMESPACE]]]
                :cljs [[cljs.core.specs.alpha :include-macros true]
                       [orchestra-cljs.spec.test :as ost]
                       [re-frame-tracer.core :as rt]
                       #_[com.rpl.specter
                          :refer-macros [setval transform select select-any]
                          :refer [filterer nthpath ALL MAP-VALS MAP-KEYS NAMESPACE]]])))


;; REVIEW: Replace this pattern:
;; `(let [fn-name (fn  ...)] (defn ...))` with
;; `letfn` when the ClojureScript bug is fixed:
;; https://dev.clojure.org/jira/browse/CLJS-1965


;;;; Global vars and state


(def ghostwheel-colors tr/ghostwheel-colors)
(def ^:private test-suffix "__ghostwheel-test")

(def ^:private **post-check-callbacks (atom []))
(def ^:private ^:dynamic *unsafe-bound-ops* #{})

(def ^:dynamic *global-trace-allowed?* true)
(def ^:dynamic *global-check-allowed?* true)          ; REVIEW: Is anyone using this?


;;;; Misc helper functions


(defn- set-trace [enabled]
  #?(:clj (alter-var-root #'*global-trace-allowed?* enabled))
  #?(:cljs (set! *global-trace-allowed?* enabled)))

(defn enable-trace! [] (set-trace true) "Tracing enabled.")
(defn disable-trace! [] (set-trace false) "Tracing disabled.")

(defn- set-check [enabled]
  #?(:clj (alter-var-root #'*global-check-allowed?* enabled))
  #?(:cljs (set! *global-check-allowed?* enabled)))

(defn enable-check! [] (set-check true) "Check enabled.")
(defn disable-check! [] (set-check false) "Check disabled.")

;; REVIEW: this doesn't seem to be needed, there's a function for
;; it in the cljs.analyzer namespace, ns-resolve or something
(defn- get-qualified-symbol
  [sym-name]
  (let [namespace (if *ns* (.-name *ns*) 'undefined)]
    (symbol (str namespace) (str sym-name))))

(defn- count-args
  "Returns a tuple with the number of regular and non-variadic arguments."
  [conformed-args]
  [(count (:args conformed-args))
   (if (:varargs conformed-args) 1 0)])


;;;; Operators


;; It doesn't actually matter what these are bound to, they are stripped by the macros
;; they're used in and never end up in the final code. This is just so they can be used
;; without '=> cannot be resolved' errors in the IDE.
(def => :ret)
(def | :st)
(def <- :gen)

(defmacro ? [& forms]
  (cond-> `(s/nilable ~@forms)
          (cljs-env? &env) clj->cljs))


;;;; Specs


(s/def ::trace #{0 1 2 3 4 5 true})
(s/def ::trace-color (s/or :keyword keyword?
                           :literal (s/and string?
                                           #(re-matches #"#[a-fA-F0-9]+" %)
                                           #(or (= (count %) 7)
                                                (= (count %) 4)))))
(s/def ::check boolean?)
(s/def ::check-coverage boolean?)
(s/def ::ignore-fx boolean?)
(s/def ::num-tests nat-int?)
(s/def ::num-tests-ext nat-int?)
(s/def ::extensive-tests boolean?)
(s/def ::instrument boolean?)
(s/def ::outstrument boolean?)
(s/def ::extrument (s/nilable (s/coll-of qualified-symbol? :kind vector?)))

;; TODO: Integrate bhaumann/spell-spec
(s/def ::ghostwheel-config
  (s/and (s/keys :req [::trace ::trace-color ::check ::check-coverage ::ignore-fx
                       ::num-tests ::num-tests-ext ::extensive-tests
                       ::instrument ::outstrument ::extrument])))

(def ghostwheel-default-config
  (s/assert ::ghostwheel-config
            ;; TODO: Add check to make sure instrument and outstrument aren't both on
            {;; Evaluation trace verbosity level. 0 disables all tracing code generation.
             ::trace           0

             ;; #RRGGBB, #RGB, or keyword from the `ghostwheel-colors` map.
             ::trace-color     :violet

             ;; When disabled no checks of any kind are
             ;; performed and no test code is generated.
             ::check           false

             ;; Determines whether Ghostwheel should warn on missing fspecs
             ;; and plain (non-Ghostwheel) `defn` usage. When enabled on a
             ;; namespace or higher level, you can exclude individual `defn`s or
             ;; `declare`s by setting it to false in their respective metadata
             ::check-coverage  false

             ;; Disable side effect detection
             ::ignore-fx       false

             ;; Number of generative tests performed when quick-checking (on hot-reload)
             ::num-tests       0

             ;; Number of generative tests performed when checking extensively (test suite)
             ::num-tests-ext   100

             ;; Determines which of the above two options should take
             ;; precedence. Set to true in your test build configuration.
             ::extensive-tests false

             ;; Spec-instrument functions on namespace reload.
             ::instrument      false

             ;; Spec-instrument functions on namespace reload using
             ;; orchestra, which spec-checks the output in addition to
             ;; the input. Use either this or `::instrument`, not both.
             ::outstrument     false

             ;; Nilable vector of qualified external namespaces or functions
             ;; (unquoted) to spec-instrument before and unstrument after
             ;; testing to catch incorrect function calls at test time without
             ;; the runtime performance impact. Fspecs must be defined for
             ;; the relevant functions in a `require`d namespace using either
             ;; `s/fdef` or Ghostwheel's `>fdef`. Only works down to the
             ;; namespace level, cannot be set for an individual function.
             ::extrument       nil}))

;; These are lifted straight from clojure.core.specs.alpha, because it
;; didn't seem possible to access them directly in the original namespace.

(s/def ::local-name (s/and simple-symbol? #(not= '& %)))

;; sequential destructuring

(s/def ::seq-binding-form
  (s/and vector?
         (s/cat :elems (s/* ::binding-form)
                :rest (s/? (s/cat :amp #{'&} :form ::binding-form))
                :as (s/? (s/cat :as #{:as} :sym ::local-name)))))

;; map destructuring

(s/def ::keys (s/coll-of ident? :kind vector?))
(s/def ::syms (s/coll-of symbol? :kind vector?))
(s/def ::strs (s/coll-of simple-symbol? :kind vector?))
(s/def ::or (s/map-of simple-symbol? any?))
(s/def ::as ::local-name)

(s/def ::map-special-binding
  (s/keys :opt-un [::as ::or ::keys ::syms ::strs]))

(s/def ::map-binding (s/tuple ::binding-form any?))

(s/def ::ns-keys
  (s/tuple
   (s/and qualified-keyword? #(-> % name #{"keys" "syms"}))
   (s/coll-of simple-symbol? :kind vector?)))

(s/def ::map-bindings
  (s/every (s/or :mb ::map-binding
                 :nsk ::ns-keys
                 :msb (s/tuple #{:as :or :keys :syms :strs} any?))
           :into {}))

(s/def ::map-binding-form (s/merge ::map-bindings ::map-special-binding))

(s/def ::binding-form
  (s/or :sym ::local-name
        :seq ::seq-binding-form
        :map ::map-binding-form))

;;; Function and >defn specs

(s/def ::arg-list
  (s/and vector?
         (s/cat :args (s/* ::binding-form)
                :varargs (s/? (s/cat :amp #{'&} :form ::binding-form)))))

(s/def ::pred-arg-list
  (s/and vector?
         (s/cat :args (s/* (s/or :sym ::local-name)))))

(s/def ::anon-args+body
  (s/cat :args ::arg-list
         :body (s/* any?)))

(s/def ::anon-fn
  (s/and seq?
         (s/cat :op #{'fn* 'fn}
                :name (s/? simple-symbol?)
                :bs (s/alt :arity-1 ::anon-args+body
                           :arity-n (s/+ (s/spec ::anon-args+body))))))

(s/def ::pred-fn
  (s/and seq?
         (s/cat :op #{'fn* 'fn}
                :name (s/? simple-symbol?)
                :args ::pred-arg-list
                :body any?)))

(s/def ::spec-elem
  (s/or :set set?
        :pred-sym (s/and symbol?
                         (complement #{'| '=>})
                         ;; REVIEW: should the `?` be a requirement?
                         #(cs/ends-with? (str %) "?"))
        :gspec (s/or :nilable-gspec ::nilable-gspec :gspec ::gspec)
        :spec-key qualified-keyword?
        :fun ::pred-fn
        :list seq?))

(s/def ::such-that-op #{:st '|})
(s/def ::ret-op #{:ret '=>})
(s/def ::gen-op #{:gen '<-})

(s/def ::gspec
  (s/and vector?
         (s/cat :args (s/? (s/cat :args (s/+ ::spec-elem)
                                  :args-such-that (s/? (s/cat :op ::such-that-op
                                                              :preds (s/+ ::pred-fn)))))
                :ret-op ::ret-op
                :ret ::spec-elem
                :fn-such-that (s/? (s/cat :op ::such-that-op
                                          :preds (s/+ ::pred-fn)))
                :gen (s/? (s/cat :op ::gen-op
                                 :gen-fn (s/? (some-fn seq? symbol?)))))))

(s/def ::nilable-gspec
  (s/and vector?
         (s/cat :maybe #{'? 's/nilable}
                :gspec ::gspec)))

(s/def ::prepost (s/map-of #{:pre :post}
                           (s/coll-of seq?
                                      :kind vector?
                                      :distinct true)))

(s/def ::args+body
  (s/cat :args ::arg-list
         :body (s/alt :prepost+body (s/cat :prepost ::prepost
                                           :body (s/+ any?))
                      :body (s/* any?))))

(s/def ::args+gspec+body
  (s/&
   (s/cat :args ::arg-list
          :gspec (s/nilable ::gspec)
          :body (s/alt :prepost+body (s/cat :prepost ::prepost
                                            :body (s/+ any?))
                       :body (s/* any?)))
   (fn arg-specs-match-param-count? [{:keys [args gspec]}]
     (if-not gspec
       true
       (let [argcount  (->> args count-args (apply +))
             spec-args (:args gspec)]
         (if spec-args
           (-> spec-args :args count (= argcount))
           (= argcount 0)))))))

(s/def ::fdef
  (s/and seq?
         (s/cat :op #{'cljs.spec.alpha/fdef 'clojure.spec.alpha/fdef}
                :name symbol?
                :args-op #{:args}
                :args seq?                            ; REVIEW - refine?
                :ret (s/? (s/cat :ret-op #{:ret}
                                 :ret ::spec-elem))
                :fun (s/? (s/cat :fn-op #{:fn}
                                 :pred seq?)))))      ; REVIEW - refine?

(s/def ::defn
  (s/and seq?
         (s/cat :op #{'defn 'defn-}
                :name simple-symbol?
                :docstring (s/? string?)
                :meta (s/? map?)
                :bs (s/alt :arity-1 ::args+body
                           :arity-n (s/cat :bodies (s/+ (s/spec ::args+body))
                                           :attr (s/? map?))))))

(s/def ::deftest
  (s/and seq?
         (s/cat :op #{'clojure.test/deftest 'cljs.test/deftest}
                :name symbol?
                :body any?)))

;; FIXME: Update the spec - doesn't match current `desugar-defn` output
(s/def ::desugared->defn
  (s/and seq?
         (s/cat :do #{'do}
                :defn ::defn
                :fdef (s/nilable ::fdef)
                :deftest (s/nilable ::deftest))))

;;; Side effect detection specs

(s/def ::threading-macro-op
  #{'-> '->> 'as-> 'cond-> 'cond->> 'some-> 'some->>
    '*-> '*->> '*as-> '*cond-> '*cond->> '*some-> '*some->>})

(s/def ::binding-op
  #{'let 'for 'doseq 'binding})

(s/def ::single-function-composition
  #{'partial 'fnil})

(s/def ::safe-single-function-composition
  #{'memoize 'complement})

(s/def ::multi-function-composition
  #{'comp})

(s/def ::safe-multi-function-composition
  #{'juxt 'every-pred 'some-fn})

(s/def ::function-application
  #{'apply 'map 'fmap 'map-indexed 'reduce})

(s/def ::safe-function-application
  #{'mapcat 'reduce-kv 'mapv 'reductions 'iterate 'keep 'keep-indexed
    'remove 'filter 'filterv 'take-while 'drop-while
    'sort 'sort-by 'sorted-map-by 'group-by 'merge-with})

(s/def ::unsafe-clj-block #{'do
                            'doseq
                            'dotimes})

;; REVIEW: maybe move the re-frame stuff out of here
(s/def ::unsafe-clj-call #{'dorun
                           'repeatedly
                           'dispatch
                           'js-delete
                           'aset})

(s/def ::unsafe-clj-comp
  (s/alt :single-fn (s/cat :composition (s/alt :generic ::single-function-composition
                                               :safe ::safe-single-function-composition)
                           :unsafe-op ::unsafe-op
                           :rest ::rest)
         :multi-fn (s/cat :composition (s/alt :generic ::multi-function-composition
                                              :safe ::safe-multi-function-composition)
                          :some-unsafe-ops ::some-unsafe-ops
                          :rest ::rest)))

(let [bang-suffix? #(cs/ends-with? (str %) "!")]
  (s/def ::bang-suffix (every-pred symbol? bang-suffix?))
  (s/def ::unsafe-op
    (s/alt :bang-suffix ::bang-suffix
           :unsafe-anon-fn (s/and seq?
                                  (s/alt :unsafe-body (s/cat :fun #{'fn 'fn*}
                                                             :name (s/? simple-symbol?)
                                                             :args ::arg-list
                                                             :body ::unsafe-form)
                                         :unsafe-name (s/cat :fun #{'fn 'fn*}
                                                             :name (every-pred simple-symbol? bang-suffix?)
                                                             :args ::arg-list
                                                             :body any?)))
           :unsafe-clj-call ::unsafe-clj-call
           :unsafe-clj-comp (s/spec ::unsafe-clj-comp)
           :unsafe-bound-call #(contains? *unsafe-bound-ops* %)
           :multi-form-op (s/cat :op #{'when 'when-not 'when-let 'when-first
                                       'when-some 'let 'binding}
                                 :pred-or-bindings any?
                                 :fx (s/+ any?)
                                 :return any?))))

(s/def ::safe-op #(not (s/valid? ::unsafe-op (list %))))

(s/def ::some-unsafe-ops (s/+ (s/cat :skipped-ops (s/* ::safe-op)
                                     :unsafe-op ::unsafe-op)))

(s/def ::rest (s/* any?))

(s/def ::some-unsafe-bindings
  (s/and vector?
         (s/+ (s/cat :skipped-bindings (s/* (s/cat :binding ::binding-form
                                                   :value ::safe-op))
                     :unsafe-binding (s/cat :binding ::binding-form
                                            :value ::unsafe-op)))))

(s/def ::unsafe-form
  ;; REVIEW: maybe make sure we are only matching on the simple symbol part
  (s/or :unsafe-block (s/and seq?
                             (s/cat :unsafe-clj-block ::unsafe-clj-block
                                    :rest ::rest))

        :unsafe-call
        (s/and seq?
               (s/alt :direct (s/cat :application
                                     (s/? (s/alt :generic ::function-application
                                                 :safe ::safe-function-application))
                                     :unsafe-op ::unsafe-op
                                     :rest ::rest)
                      :threading (s/cat :threading-macro-op ::threading-macro-op
                                        :threaded-form any?
                                        :some-unsafe-ops ::some-unsafe-ops
                                        :rest ::rest)
                      :update (s/cat :update #{'update 'update-in}
                                     :map any?
                                     :path any?
                                     :unsafe-op ::unsafe-op
                                     :rest ::rest)))

        :unsafe-composition (s/and seq? ::unsafe-clj-comp)
        :unsafe-binding (s/and seq?
                               (s/cat :binding-op ::binding-op
                                      :bindings ::some-unsafe-bindings
                                      :rest ::rest))
        :unsafe-argument (s/and seq?
                                (s/cat :fun ::safe-op
                                       :some-unsafe-ops ::some-unsafe-ops
                                       :rest ::rest))
        #_::unsafe-something #_(s/spec (s/cat ::some-unsafe-ops ::some-unsafe-ops
                                              ::rest ::rest))))


;;;; Main code generating functions


(let [find-fx
      (fn find-fx [form]
        (let [maybe-fx           (s/conform ::unsafe-form form)
              [found-fx
               unsafe-bindings] (if (= maybe-fx ::s/invalid)
                                  [nil nil]
                                  [(conj {} maybe-fx)
                                   (when (= (key maybe-fx) :unsafe-binding)
                                     (-> maybe-fx
                                         val
                                         :bindings))])
              ;; TODO implement map and vec destructuring support for bindings
              unsafe-binding-set (when unsafe-bindings
                                   (->> unsafe-bindings
                                        (map #(-> % :unsafe-binding :binding val))
                                        (set)))]
          [found-fx (vec
                     (for [nested-form form
                           :when (and (coll? nested-form))]
                       ;; REVIEW go into nested anon-fns or not?
                       ;(not (contains? #{'fn 'fn*} (first nested-form))))]
                       (binding [*unsafe-bound-ops* (cond-> *unsafe-bound-ops*
                                                            unsafe-bindings (union unsafe-binding-set))]
                         (find-fx nested-form))))]))

      check-arity-fx
      (fn [unformed-args-gspec-body]
        (let [effects (->> (find-fx unformed-args-gspec-body)
                           (flatten)
                           (remove nil?)
                           (map first)
                           (vec))]
          (-> (for [fx effects]
                [(-> fx key name keyword)
                 (->> fx
                      (s/unform ::unsafe-form)
                      (apply list)
                      (str))
                 (->> fx
                      val
                      (walk/postwalk #(if (qualified-keyword? %)
                                        (keyword (name %))
                                        %))
                      vec)])
              (cond->> (next unformed-args-gspec-body) (cons [:multiple-body-forms])))))]
  (defn- generate-test [fn-name fspecs body-forms config]
    (let [{:keys [::check ::num-tests ::num-tests-ext ::extensive-tests
                  ::check-coverage ::ignore-fx]}
          config

          num-tests         (if extensive-tests num-tests-ext num-tests)
          marked-unsafe     (s/valid? ::bang-suffix fn-name)
          found-fx          (if ignore-fx
                              []
                              (->> (case (key body-forms)
                                     :arity-1 [(val body-forms)]
                                     :arity-n (val body-forms))
                                   (map #(->> % (s/unform ::args+gspec+body) (drop 2)))
                                   (mapcat check-arity-fx)
                                   distinct
                                   vec))
          unexpected-fx     (boolean (and (not ignore-fx)
                                          (not marked-unsafe)
                                          (seq found-fx)))
          unexpected-safety (boolean (and (not ignore-fx)
                                          marked-unsafe
                                          (empty? found-fx)))
          spec-checks       (let [defined-fspecs (->> fspecs (remove nil?) vec)]
                              (when (and (seq defined-fspecs)
                                         (not marked-unsafe)
                                         (empty? found-fx)
                                         (> num-tests 0))
                                `(for [spec# ~defined-fspecs]
                                   (st/check-fn
                                    ~fn-name
                                    spec#
                                    {~(keyword (str `clojure.test.check) "opts")
                                     {:num-tests ~num-tests}}))))]
      [unexpected-fx
       `(t/deftest ~(symbol (str fn-name test-suffix))
          (let [spec-checks# ~spec-checks]
            ;; TODO The `spec-checks#` thing trips up clairvoyant
            ;; and prevents tracing during ghostwheel development
            (t/is (and (every? #(-> % :clojure.test.check/ret :pass?) spec-checks#)
                       ~(not unexpected-fx)
                       ~(not unexpected-safety))
                  {::r/fn-name        (quote ~fn-name)
                   ::r/fspec          ~(every? some? fspecs)
                   ::r/spec-checks    spec-checks#
                   ::r/check-coverage ~check-coverage
                   ::r/failure        ~(cond unexpected-fx ::r/unexpected-fx
                                             unexpected-safety ::r/unexpected-safety
                                             :else ::r/spec-failure)
                   ::r/found-fx       (quote ~found-fx)
                   ::r/marked-unsafe  ~marked-unsafe})))])))

(defn- unscrew-vec-unform
  "Half-arsed workaround for spec bugs CLJ-2003 and CLJ-2021."
  [unformed-arg]
  (if-not (sequential? unformed-arg)
    unformed-arg
    (let [malformed-seq-destructuring? (every-pred seq? (comp #{:as '&} first))
          [unformed malformed] (split-with (complement malformed-seq-destructuring?) unformed-arg)]
      (vec (concat unformed (apply concat malformed))))))

(defn- gspec->fspec*
  [conformed-arg-list conformed-gspec anon-fspec? multi-arity-args? nilable?]
  (let [{argspec-def              :args
         retspec                  :ret
         fn-such-that             :fn-such-that
         {:keys [gen-fn] :as gen} :gen}
        conformed-gspec]
    (if (and anon-fspec?
             argspec-def
             (not gen)
             (some #{'any?} (-> argspec-def :args vals)))
      (if nilable? `(s/nilable ifn?) `ifn?)
      (let [extract-spec
            (fn extract-spec [[spec-type spec]]
              (if (= spec-type :gspec)
                (if (= (key spec) :nilable-gspec)
                  (gspec->fspec* nil (-> spec val :gspec) true false true)
                  (gspec->fspec* nil (val spec) true false false))
                spec))

            named-conformed-args
            (when argspec-def
              (let [all-args     (remove nil? (concat (:args conformed-arg-list)
                                                      [(-> conformed-arg-list :varargs :form)]))
                    gen-arg-name (fn [index] (str "arg" (inc index)))
                    gen-name     (fn [index [arg-type arg :as full-arg]]
                                   (let [arg-name (if-not arg-type
                                                    (gen-arg-name index)
                                                    (case arg-type
                                                      :sym arg
                                                      :seq (or (-> arg :as :sym)
                                                               (gen-arg-name index))
                                                      :map (or (-> arg :as)
                                                               (gen-arg-name index))))]
                                     [(keyword arg-name) full-arg]))]
                (map-indexed gen-name (or (seq all-args)
                                          (-> argspec-def :args count (repeat nil))))))

            arg-binding-map
            (if-not conformed-arg-list
              {}
              (if (every? #(= (-> % second key) :sym) named-conformed-args)
                `{:keys ~(vec (map #(-> % first name symbol) named-conformed-args))}
                (->> named-conformed-args
                     (map (fn [[arg-key conformed-arg]]
                            [(->> conformed-arg (s/unform ::binding-form) unscrew-vec-unform)
                             arg-key]))
                     (into {}))))

            process-arg-pred
            (fn process-arg-pred [{:keys [name args body]}]
              (let [bindings (if-let [anon-arg (some-> args :args first second)]
                               (assoc arg-binding-map :as anon-arg)
                               arg-binding-map)]
                (remove nil? `(fn ~name [~bindings] ~body))))

            processed-args
            (if-not argspec-def
              `(s/cat)
              (let [wrapped-params (->> argspec-def
                                        :args
                                        (map extract-spec)
                                        (interleave (map first named-conformed-args))
                                        (cons `s/cat))]
                (if-let [args-such-that (:args-such-that argspec-def)]
                  (->> args-such-that
                       :preds
                       (map process-arg-pred)
                       (list* `s/and wrapped-params))
                  wrapped-params)))

            process-ret-pred
            (fn process-ret-pred [{:keys [name args body]}]
              (let [anon-arg       (some-> args :args first second)
                    ret-sym        (gensym "ret__")
                    bindings       [{(if multi-arity-args?
                                       ['_ arg-binding-map]
                                       arg-binding-map) :args
                                     ret-sym            :ret}]
                    processed-body (if anon-arg
                                     (walk/postwalk-replace {anon-arg ret-sym} body)
                                     body)]
                (remove nil? `(fn ~name ~bindings ~processed-body))))

            fn-spec
            (when fn-such-that
              (let [processed-ret-preds (map process-ret-pred (:preds fn-such-that))]
                (if (next processed-ret-preds)
                  (cons `s/and processed-ret-preds)
                  (first processed-ret-preds))))

            final-fspec
            (concat (when anon-fspec? [`s/fspec])
                    [:args processed-args]
                    [:ret (extract-spec retspec)]
                    (when fn-spec [:fn fn-spec])
                    (when gen-fn [:gen gen-fn]))]
        (if nilable? `(s/nilable ~final-fspec) final-fspec)))))


;; TODO make sure we check whether the variadic bodies are legit
;; Can not have more than one
;; Can not have one with more regular args than the variadic one
;; To what extent does the compiler already check this?
(let [get-fspecs    (fn [fn-body]
                      (let [[param-count variadic] (-> fn-body :args count-args)
                            gspec (or (:gspec fn-body)
                                      (s/conform ::gspec
                                                 (vec (concat (repeat param-count 'any?)
                                                              (when (> variadic 0)
                                                                `[(s/* any?)])
                                                              '[=> any?]))))]
                        [(->> (if (> variadic 0) "n" param-count)
                              (str "arity-")
                              keyword)
                         (gspec->fspec* (:args fn-body) gspec false true false)]))
      get-spec-part (fn [part spec]
                      (->> spec
                           (drop-while (complement #{part}))
                           second))]
  (defn- generate-fspec-body [fn-bodies]
    (case (key fn-bodies)
      :arity-1
      (when-let [gspec (-> fn-bodies val :gspec)]
        (gspec->fspec* (-> fn-bodies val :args) gspec false false false))

      :arity-n
      (when (some :gspec (val fn-bodies))
        (let [fspecs           (map get-fspecs (val fn-bodies))
              arg-specs        (mapcat (fn [[arity spec]]
                                         [arity (or (get-spec-part :args spec) `empty?)])
                                       fspecs)
              fn-param         (gensym "p1__")
              multi-ret-specs  (when (->> fspecs
                                          (map #(get-spec-part :ret (second %)))
                                          distinct
                                          count
                                          (not= 1))
                                 (mapcat (fn [[arity spec]]
                                           [arity `(s/valid? ~(get-spec-part :ret spec)
                                                             (:ret ~fn-param))])
                                         fspecs))
              get-fn-clause    (partial get-spec-part :fn)
              fn-specs         (when (->> fspecs (map second) (some get-fn-clause))
                                 (mapcat (fn [[arity spec]]
                                           [arity (if-let [fn-spec (get-fn-clause spec)]
                                                    `(s/valid? ~fn-spec ~fn-param)
                                                    true)])
                                         fspecs))
              ;; NOTE: destructure args and ret in the arg vec
              multi-ret-clause (when multi-ret-specs
                                 `(fn ~'valid-multi-arity-ret? [~fn-param]
                                    (case (-> ~fn-param :args key)
                                      ~@multi-ret-specs)))
              multi-fn-clause  (when fn-specs
                                 `(fn ~'valid-multi-arity-fn? [~fn-param]
                                    (case (-> ~fn-param :args key)
                                      ~@fn-specs)))]
          ;; Using s/or here even though s/alt seems to be more common
          ;; for multi-arity specs in the wild. The spec error reporting
          ;; is much better and it's immediately clear what didn't match.
          (concat [:args `(s/or ~@arg-specs)]
                  (when-not multi-ret-clause
                    [:ret (get-spec-part :ret (-> fspecs first second))])
                  (when (or multi-ret-clause multi-fn-clause)
                    [:fn (if multi-fn-clause
                           (if multi-ret-clause
                             `(s/and ~multi-ret-clause ~multi-fn-clause)
                             multi-fn-clause)
                           multi-ret-clause)])))))))

(def ^:private spec-op->type
  (let [map-prot     "cljs.core.IMap"
        coll-prot    "cljs.core.ICollection"
        ;; Needed because Closure compiler/JS doesn't consider strings seqable
        seqable-prot "(cljs.core.ISeqable|string)"]
    {'number?      "number"
     'integer?     "number"
     'int?         "number"
     'nat-int?     "number"
     'pos-int?     "number"
     'neg-int?     "number"
     'float?       "number"
     'double?      "number"
     'int-in       "number"
     'double-in    "number"

     'string?      "string"

     'boolean?     "boolean"

     'keys         map-prot
     'map-of       map-prot
     'map?         map-prot
     'merge        map-prot

     'set?         "cljs.core.ISet"
     'vector?      "cljs.core.IVector"
     'tuple        "cljs.core.IVector"
     'seq?         "cljs.core.ISeq"
     'seqable?     seqable-prot
     'associative? "cljs.core.IAssociative"
     'atom?        "cljs.core.IAtom"

     'coll-of      coll-prot
     'every        coll-prot

     'keyword?     "cljs.core.Keyword"
     'ifn?         "cljs.core.IFn"
     'fn?          "Function"}))

(declare get-gspec-type)

(defn- get-type [recursive-call conformed-spec-elem]
  (let [[spec-type spec-def] conformed-spec-elem

        spec-op
        ;; REVIEW: This kinda wants to be a multi-method when it grows up.
        (case spec-type
          :list (let [op (-> spec-def first name symbol)]
                  (cond
                    (#{'nilable '?} op) (concat (->> spec-def
                                                     second
                                                     (s/conform ::spec-elem)
                                                     (get-type true))
                                                [::nilable])
                    (#{'* '+} op) (concat (->> spec-def
                                               second
                                               (s/conform ::spec-elem)
                                               (get-type true))
                                          [::variadic])
                    (#{'and} op) [(-> spec-def second)] ; TODO
                    (#{'coll-of 'every} op) [(or (->> spec-def
                                                      (drop-while (complement #{:kind}))
                                                      second)
                                                 op)]
                    :else [op]))
          ;;TODO support (some-fn and (s/or
          :gspec (let [gspec-def (val spec-def)]
                   (if (= (key spec-def) :nilable-gspec)
                     [(get-gspec-type (:gspec gspec-def)) ::nilable]
                     [(get-gspec-type gspec-def)]))
          :pred-sym [spec-def]
          [nil])]
    (if recursive-call
      spec-op
      (if-let [js-type (spec-op->type (first spec-op))]
        (let [modifiers (set (rest spec-op))]
          (as-> js-type t
                (str (if (::nilable modifiers) "?" "!") t)
                (str (when (::variadic modifiers) "...") t)))
        "*"))))

(defn- get-gspec-type [conformed-gspec]
  (let [argspec-def (:args conformed-gspec)
        args-jstype (if-not argspec-def
                      ""
                      (->> (-> conformed-gspec :args :args)
                           (map (partial get-type false))
                           (cs/join ", ")))
        ret-jstype  (get-type false (:ret conformed-gspec))]
    (str "function(" args-jstype "): " ret-jstype)))

(defn- generate-type-annotations [conformed-bs]
  (case (key conformed-bs)
    :arity-1 (when-let [gspec (-> conformed-bs val :gspec)]
               {:jsdoc [(str "@type {" (get-gspec-type gspec) "}")]})
    ;; REVIEW: There doesn't seem to be a way to get valid annotations for args of
    ;; multi-arity functions and attempts to just annotate the return value(s) failed
    ;; as well. It wasn't possible to put together an annotation which was both
    ;; considered valid and resulted in a successful type check.
    :arity-n nil #_(when-let [ret-types (as-> (val conformed-bs) x
                                              (map #(get-type false (-> % :gspec :ret)) x)
                                              (distinct x)
                                              (when (not-any? #{"*" "?"} x) x))]
                     {:jsdoc [(str "@return {" (cs/join "|" ret-types) "}")]})))

(defn- generate-fdef
  [forms]
  (let [{:keys [name bs]} (s/conform ::>fdef-args forms)]
    (case (key name)
      :sym `(s/fdef ~(val name) ~@(generate-fspec-body bs))
      :key `(s/def ~(val name) (s/fspec ~@(generate-fspec-body bs))))))

(let [process-defn-body
      (fn [cfg fspec args+gspec+body]
        (let [{:keys [env fn-name traced-fn-name trace color unexpected-fx]} cfg
              {:keys [args body]} args+gspec+body
              [prepost orig-body-forms] (case (key body)
                                          :prepost+body [(-> body val :prepost)
                                                         (-> body val :body)]
                                          :body [nil (val body)])
              process-arg (fn [[arg-type arg]]
                            (as-> arg arg
                                  (case arg-type
                                    :sym [arg-type arg]
                                    :seq [arg-type (update arg :as #(or % {:as :as :sym (gensym "arg_")}))]
                                    :map [arg-type (update arg :as #(or % (gensym "arg_")))])))
              ;; NOTE: usage of extract-arg isn't elegant, there's duplication, refactor
              extract-arg (fn [[arg-type arg]]
                            (case arg-type
                              :sym arg
                              :seq (get-in arg [:as :sym])
                              :map (:as arg)
                              nil))
              unform-arg  #(->> % (s/unform ::binding-form) unscrew-vec-unform)
              reg-args    (->> args :args (map process-arg))
              var-arg     (some-> args :varargs :form process-arg)
              arg-list    (vec (concat (map unform-arg reg-args)
                                       (when var-arg ['& (unform-arg var-arg)])))
              body-forms  (if (and fspec (every? nil? orig-body-forms))
                            ;; TODO error handling when specs too fancy for stub auto-generation
                            [`(apply (-> ~fspec s/gen gen/generate)
                                     ~@(map extract-arg reg-args) ~(extract-arg var-arg))]

                            (cond unexpected-fx
                                  [`(throw (~(if (cljs-env? env) 'js/Error. 'Exception.)
                                            ~(str "Calling function `"
                                                  fn-name
                                                  "` which has unexpected side effects.")))]

                                  (= trace :dispatch)
                                  [`(if *global-trace-allowed?*
                                      (apply ~traced-fn-name
                                             ~@(map extract-arg reg-args)
                                             ~(extract-arg var-arg))
                                      (do ~@orig-body-forms))]

                                  (= trace 1)
                                  `[(do
                                      (pr-clog ~(str (list fn-name arg-list))
                                               nil
                                               {::r/background ~color})
                                      ~@orig-body-forms)]

                                  (>= trace 4)
                                  (let [threading-macros-mappings
                                        {'->      'ghostwheel.tracing/*->
                                         '->>     'ghostwheel.tracing/*->>
                                         'as->    'ghostwheel.tracing/*as->
                                         'cond->  'ghostwheel.tracing/*cond->
                                         'cond->> 'ghostwheel.tracing/*cond->>
                                         'some->  'ghostwheel.tracing/*some->
                                         'some->> 'ghostwheel.tracing/*some->>}]
                                    (cond->> (walk/postwalk-replace threading-macros-mappings
                                                                    orig-body-forms)

                                             ;; Make sure we don't trace threading macros in anon-fns
                                             ;; when anon-fns themselves aren't traced
                                             (< trace 5)
                                             (walk/postwalk
                                              #(if (and (list? %)
                                                        (#{'fn 'fn*} (first %)))
                                                 (walk/postwalk-replace (map-invert threading-macros-mappings) %)
                                                 %))))

                                  :else
                                  orig-body-forms))]
          (remove nil? `(~arg-list ~prepost ~@body-forms))))]
  (defn- generate-defn
    [forms private env compiler-config]
    (let [conformed-gdefn   (s/conform ::>defn-args forms)
          fn-deftype        (if private 'defn- 'defn)
          fn-bodies         (:bs conformed-gdefn)
          empty-bodies      (every? empty?
                                    (case (key fn-bodies)
                                      :arity-1 (list (-> fn-bodies val :body val))
                                      :arity-n (->> fn-bodies
                                                    val
                                                    (map :body)
                                                    (map val))))
          arity             (key fn-bodies)
          fn-name           (:name conformed-gdefn)
          quoted-qualified-fn-name
                            `(quote ~(symbol (str (get-ns-name env)) (str fn-name)))
          traced-fn-name    (gensym (str fn-name "__"))
          docstring         (:docstring conformed-gdefn)
          meta-map          (merge (:meta conformed-gdefn)
                                   (generate-type-annotations fn-bodies)
                                   {::ghostwheel true})
          ;;; Assemble the config
          config            (s/assert ::ghostwheel-config
                                      (->> (merge ghostwheel-default-config
                                                  compiler-config
                                                  (get-ns-meta env)
                                                  (meta fn-name)
                                                  meta-map)
                                           (filter #(= (-> % key namespace) (name `ghostwheel.core)))
                                           (into {})))
          color             (if-let [color (get ghostwheel-colors (::trace-color config))]
                              color
                              (:black ghostwheel-colors))
          {:keys [::instrument ::outstrument ::trace ::check]} config
          trace             (if (cljs-env? env)
                              (cond empty-bodies 0
                                    (true? trace) 4
                                    :else trace)
                              0)                      ; TODO: Clojure
          ;;; Code generation
          fdef-body         (generate-fspec-body fn-bodies)
          fdef              (when fdef-body `(s/fdef ~fn-name ~@fdef-body))
          instrumentation   (when (not empty-bodies)
                              (cond outstrument `(ost/instrument ~quoted-qualified-fn-name)
                                    instrument `(st/instrument ~quoted-qualified-fn-name)
                                    :else nil))
          individual-arity-fspecs
                            (map (fn [{:keys [args gspec]}]
                                   (when gspec
                                     (gspec->fspec* args gspec true false false)))
                                 (val fn-bodies))
          [unexpected-fx generated-test] (when (and check (not empty-bodies))
                                           (let [fspecs (case arity
                                                          :arity-1 [(when fdef-body `(s/fspec ~@fdef-body))]
                                                          :arity-n individual-arity-fspecs)]
                                             (generate-test fn-name fspecs fn-bodies config)))
          process-fn-bodies (fn [trace]
                              (let [process-cfg {:env            env
                                                 :fn-name        fn-name
                                                 :traced-fn-name traced-fn-name
                                                 :trace          trace
                                                 :color          color
                                                 :unexpected-fx  unexpected-fx}]
                                (case arity
                                  :arity-1 (->> fn-bodies val (process-defn-body process-cfg `(s/fspec ~@fdef-body)))
                                  :arity-n (map (partial process-defn-body process-cfg)
                                                individual-arity-fspecs
                                                (val fn-bodies)))))
          clairvoyant-trace (fn [forms exclude]
                              `(cv/trace-forms
                                {:enabled true
                                 :tracer  (re-frame-tracer.core/tracer
                                           :color "#fff"
                                           :background ~color
                                           :expand ~(if (>= trace 3)
                                                      '#{:bindings 'let 'defn 'defn-}
                                                      '#{'defn 'defn-}))
                                 :exclude ~exclude}
                                ~forms))
          main-defn         (remove nil? `(~fn-deftype
                                           ~fn-name
                                           ~docstring
                                           ~meta-map
                                           ~@(process-fn-bodies (if (> trace 0) :dispatch 0))))
          traced-defn       (when (> trace 0)
                              (let [traced-defn (remove nil? `(~fn-deftype
                                                               ~traced-fn-name
                                                               ~@(process-fn-bodies trace)))]
                                (case trace
                                  1 traced-defn
                                  2 (clairvoyant-trace traced-defn '#{'fn 'fn* 'let})
                                  3 (clairvoyant-trace traced-defn '#{'fn 'fn*})
                                  4 (clairvoyant-trace traced-defn '#{'fn 'fn*})
                                  5 (clairvoyant-trace traced-defn nil))))]
      `(do ~fdef ~traced-defn ~main-defn ~instrumentation ~generated-test))))

(defn post-check-async [done]
  (let [success @r/**all-tests-successful]
    (when success (doseq [f @**post-check-callbacks] (f)))
    (reset! r/**all-tests-successful true)
    (reset! **post-check-callbacks [])
    (when success (done))))

(defn- generate-check [env callbacks]
  (let [{:keys [::extrument ::check ::check-coverage]}
        (merge (get-ghostwheel-compiler-config env)
               (get-ns-meta env))

        plain-defns (when check-coverage
                      ;; TODO: Make this work on clj in addition to cljs
                      (some->> env
                               :ns
                               :defs
                               (filter #(-> % val :fn-var))
                               (remove #(-> % key meta ::ghostwheel))
                               (remove #(false? (-> % key meta ::check-coverage)))
                               (remove #(-> % key str (cs/ends-with? test-suffix)))
                               (map (comp str key))
                               vec))]
    ;; TODO implement for clj
    (when (and check (cljs-env? env))
      `(do
         ~(when (seq plain-defns)
            `(t/deftest ~(symbol (str "coverage__" (gensym) test-suffix))
               (t/is true
                     {::r/plain-defns    ~plain-defns
                      ::r/check-coverage ~check-coverage})))
         ~(when (seq callbacks)
            `(swap! **post-check-callbacks (comp vec concat) ~(vec callbacks)))
         (when *global-check-allowed?*
           ~@(remove nil?
                     [(when extrument
                        `(st/instrument (quote ~extrument)))
                      `(binding [*global-trace-allowed?* false]
                         (t/run-tests (t/empty-env ::r/default)))
                      (when extrument
                        `(st/unstrument (quote ~extrument)))]))))))


;;;; Main macros and public API


(defn- clean-defn
  "This removes the gspec and returns a
  clean defn for use in production builds."
  [op forms]
  (let [single-arity? (fn [fn-forms] (boolean (some vector? fn-forms)))
        strip-gspec   (fn [body] (let [[args gspec & more] body]
                                   (cons args more)))]
    (->> (if (single-arity? forms)
           (let [[head-forms body-forms] (split-with (complement vector?) forms)]
             `(~op ~@head-forms ~@(strip-gspec body-forms)))
           (let [[head-forms body-forms tail-attr-map] (partition-by (complement seq?) forms)]
             `(~op ~@head-forms ~@(map strip-gspec body-forms) ~@tail-attr-map)))
         (remove nil?))))

(s/def ::>defn-args
  (s/and seq?                                         ; REVIEW
         (s/cat :name simple-symbol?
                :docstring (s/? string?)
                :meta (s/? map?)
                :bs (s/alt :arity-1 ::args+gspec+body
                           ;; TODO: add tail-attr-map support after this
                           :arity-n (s/+ (s/and seq? ::args+gspec+body))))))

(s/fdef >defn
  :args ::>defn-args
  :ret ::desugared->defn)

(defmacro >defn
  "Like defn, but requires a (nilable) gspec definition and generates
  additional s/fdef, generative tests, instrumentation code, an
  fspec-based stub, and/or tracing code, depending on the configuration
  metadata and the existence of a valid gspec and non-nil body."
  {:arglists '([name doc-string? attr-map? [params*] gspec prepost-map? body?]
               [name doc-string? attr-map? ([params*] gspec prepost-map? body?) + attr-map?])}
  [& forms]
  (if-let [compiler-config (get-ghostwheel-compiler-config &env)]
    (cond-> (remove nil? (generate-defn forms false &env compiler-config))
            (cljs-env? &env) clj->cljs)
    (clean-defn 'defn forms)))

(s/fdef >defn-
  :args ::>defn-args
  :ret ::desugared->defn)

;; NOTE: lots of duplication - refactor this to set/pass ^:private differently and call >defn
(defmacro >defn-
  "Like defn-, but requires a (nilable) gspec definition and generates
  additional s/fdef, generative tests, instrumentation code, an
  fspec-based stub, and/or tracing code, depending on the configuration
  metadata and the existence of a valid gspec and non-nil body."
  {:arglists '([name doc-string? attr-map? [params*] gspec prepost-map? body?]
               [name doc-string? attr-map? ([params*] gspec prepost-map? body?) + attr-map?])}
  [& forms]
  (if-let [compiler-config (get-ghostwheel-compiler-config &env)]
    (cond-> (remove nil? (generate-defn forms true &env compiler-config))
            (cljs-env? &env) clj->cljs)
    (clean-defn 'defn- forms)))

(defmacro check
  "Runs all tests in the namespace. The optional callbacks are for a
  hot-reloading environment and will be executed only if all reloaded
  namespaces test successfully and `ghostwheel.core/post-check-async`
  is called correctly from your build system after reloading."
  [& post-check-callbacks]
  (when (get-ghostwheel-compiler-config &env)
    (cond-> (generate-check &env post-check-callbacks)
            (cljs-env? &env) (clj->cljs false))))

(s/def ::>fdef-args
  (s/and seq?                                         ;REVIEW
         (s/cat :name (s/or :sym symbol? :key qualified-keyword?)
                :bs (s/alt :arity-1 ::args+gspec+body
                           :arity-n (s/+ (s/and seq? ::args+gspec+body))))))

(s/fdef >fdef
  :args ::>fdef-args
  :ret ::fdef)

(defmacro >fdef
  "Defines an fspec, supports gspec syntax. `name` can be a symbol
  or a qualified keyword, depending on whether the fspec is meant
  to be registered as a top-level fspec (=> s/fdef fn-sym ...) or
  used in other specs (=> s/def ::spec-keyword (s/fspec ...))."
  {:arglists '([name [params*] gspec]
               [name ([params*] gspec) +])}
  [& forms]
  (when (get-ghostwheel-compiler-config &env)
    (cond-> (remove nil? (generate-fdef forms))
            (cljs-env? &env) clj->cljs)))


;;;; DEBUG


#_(defmacro print-env []
    `(clog (quote ~&env)))

;; This can be used to trace code generating functions at
;; runtime when hacking on Ghostwheel in ClojureScript
;#?(:cljs
;   (when (identical? js/goog.DEBUG true)
;     (s/check-asserts true)
;     (def env {:ns {:name (with-meta 'ghostwheel.whatever ghostwheel-default-config)}})
;     (cv/trace-forms
;      {:enabled true
;       ;:exclude #{'fn 'fn*}
;       :tracer  (rt/tracer
;                 :color "#fff"
;                 :background (:violet ghostwheel-colors)
;                 :expand #{'defn 'defn- 'let :bindings})}
;      #_(defn traced-function-goes-here))))

