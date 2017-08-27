(ns clj-chrome-devtools.impl.define
  "Macro for defining a domain of commands."
  (:require [clj-chrome-devtools.protocol-definitions :as proto]
            [clj-chrome-devtools.impl.connection :as connection]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [go <!! >!]]
            [clojure.set :as set]
            [clj-chrome-devtools.impl.util :refer [camel->clojure]]
            [clojure.spec.alpha :as s]))


(def to-symbol (comp symbol camel->clojure))

(defonce command-id (atom 0))

(defn next-command-id! []
  (swap! command-id inc))

(defn command-payload [id name params parameter-names]
  {:id id
   :method name
   :params (set/rename-keys params parameter-names)})

(defn process-doc [doc]
  (some-> doc
          (str/replace #"<code>" "`")
          (str/replace #"</code>" "`")))

(defn- describe-map-keys [ks]
  (let [max-key-length (reduce max 0 (map (comp count camel->clojure :name) ks))]
    (str "\n\n  Key " (str/join (repeat (- max-key-length 2) " ")) "| Description "
         "\n  " (str/join (repeat (+ 2 max-key-length) "-")) "|------------ \n"
         (str/join "\n"
                   (map #(str (format (str "  :%-" max-key-length "s | %s")
                                      (camel->clojure (:name %))
                                      (process-doc (:description %)))
                              (when (:optional %)
                                " (optional)"))
                        ks)))))

(def kw (comp keyword camel->clojure))

(defn ns-kw [string]
  (keyword (str *ns*) (camel->clojure string)))

(defn keys-spec [properties]
  (let [{opt-un true req-un false} (group-by (comp boolean :optional) properties)]
    `(s/keys ~@(when-not (empty? req-un)
                 `(:req-un [~@(map (comp ns-kw :name) req-un)]))
             ~@(when-not (empty? opt-un)
                 `(:opt-un [~@(map (comp ns-kw :name) opt-un)])))))


(defmacro define-type-specs [domain]
  `(do
     ~@(for [{id :id type :type :as t} (proto/types-for-domain domain)
             :let [name-kw (ns-kw id)]]

         `(do
            ;; If this is an object, create specs for basic type keys
            ~@(for [{:keys [name type]} (:properties t)
                    :when (#{"string" "number" "integer" "boolean"} type)]
                `(s/def ~(ns-kw name)
                   ~(case type
                      "string" `string?
                      "number" `number?
                      "integer" `integer?
                      "boolean" `boolean?)))
            (s/def ~name-kw
              ~(cond
                 (:enum t)
                 (into #{} (:enum t))

                 (= "object" type)
                 (keys-spec (:properties t))

                 "string" `string?
                 "integer" `integer?
                 "number" `number?
                 "boolean" `boolean?

                 :default
                 ::FIXME))))))


(defmacro define-command-functions [domain]
  `(do
     ~@(for [{:keys [name description parameters returns]} (proto/commands-for-domain domain)
             :let [fn-name (to-symbol name)
                   params (mapv (comp to-symbol :name) parameters)
                   param-names (zipmap (map (comp keyword camel->clojure :name) parameters)
                                       (map :name parameters))
                   [required-params optional-params] (split-with (comp not :optional) params)]]
         `(do
            (defn ~fn-name
              ~(str (process-doc description)
                    (when-not (empty? parameters)
                      (str "\n\nParameters map keys:\n"
                           (describe-map-keys parameters)))
                    (when-not (empty? returns)
                      (str "\n\nReturn map keys:\n"
                           (describe-map-keys returns))))
              ([] (~(to-symbol name) (connection/get-current-connection) {}))
              ([{:keys [~@params] :as ~'params}]
               (~(to-symbol name) (connection/get-current-connection) ~'params))
              ([~'connection {:keys [~@params] :as ~'params}]
               (let [id# (next-command-id!)
                     method# ~(str domain "." name)
                     ch# (async/chan)
                     payload# (command-payload id# method# ~'params
                                               ~param-names)]
                 (connection/send-command ~'connection payload# id# #(go (>! ch# %)))
                 (let [result# (<!! ch#)]
                   (if-let [error# (:error result#)]
                     (throw (ex-info (str "Error in command " method# ": " (:message error#))
                                     {:request payload#
                                      :error error#}))
                     (:result result#))))))
            (s/fdef ~fn-name
                    :args ~(keys-spec parameters)
                    :ret ~(keys-spec returns))))))

(defmacro define-domain [domain]
  `(do
     (define-type-specs ~domain)
     (define-command-functions ~domain)))

(comment
  ;; To regenerate the command namespaces, run this
  (doseq [{:keys [domain description]} (proto/all-domains)]
    (let [clj-name (camel->clojure domain)
          file-name (str "src/clj_chrome_devtools/commands/" (str/lower-case (str/replace clj-name "-" "_")) ".clj")]
      (spit file-name
            (str "(ns clj-chrome-devtools.commands." (str/lower-case clj-name) "\n"
                 (when description
                   (str "  " (pr-str (process-doc description)) "\n"))
                 "  (:require [clj-chrome-devtools.impl.define :refer [define-domain]]))\n"
                 "(define-domain \"" domain "\")")))))
