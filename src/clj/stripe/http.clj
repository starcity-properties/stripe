(ns stripe.http
  (:require [cheshire.core :as json]
            [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [org.httpkit.client :as http]
            [stripe.util.codec :as codec]
            [toolbelt.async :as ta]
            [toolbelt.core :as tb]))

;; =============================================================================
;; Spec
;; =============================================================================


(s/def ::api-token
  string?)

(s/def ::expansion
  (s/or :keyword keyword? :keyseq (s/+ keyword?)))

(s/def ::http-kit-options
  map?)

(s/def ::params
  map?)

(s/def ::method
  #{:get :delete :post})

(s/def ::out-ch
  ta/chan?)

(s/def ::client-options
  ::http-kit-options)

(s/def ::token
  ::api-token)

(s/def ::throw-on-error?
  boolean?)

(s/def ::account
  string?)

(s/def ::request-options
  (s/keys :opt-un [::account ::out-ch ::params ::client-options ::token ::throw-on-error?]))

(s/def ::endpoint
  string?)

(s/def ::api-call
  (s/and (s/keys :req-un [::method ::endpoint])
         ::request-options))


(defn request-options?
  "Is the argument a valid request options map?"
  [x]
  (s/valid? ::request-options x))


(def api-call?
  "Is the argument a valid api call map?"
  (partial s/valid? ::api-call))


;; =============================================================================
;; Authorization
;; =============================================================================


(def ^:dynamic *token* nil)
(def ^:dynamic *api-version* nil)
(def ^:dynamic *connect-account* nil)


;; =====================================
;; API Token


(defn api-token [] *token*)

(s/fdef api-token
        :ret (s/nilable ::api-token))


(defmacro with-token [t & forms]
  `(binding [*token* ~t]
     ~@forms))


(defn use-token!
  "Permanently sets a base token. The token can still be overridden on
  a per-thread basis using with-token."
  [t]
  (alter-var-root #'*token* (constantly t)))


;; =====================================
;; API Version


(defn api-version [] *api-version*)

(s/fdef api-version
        :args (s/cat)
        :ret (s/nilable string?))


(defmacro with-api-version
  [v & forms]
  `(binding [*api-version* ~v]
     ~@forms))


(defn use-api-version!
  "Permanently sets an API version. The api version can still be
  overridden on a per-thread basis using with-api-version."
  [s]
  (alter-var-root #'*api-version* (constantly s)))


;; =====================================
;; Connect Account


(defn connect-account [] *connect-account*)

(s/fdef connect-account
        :ret (s/nilable string?))


(defmacro with-connect-account
  [v & forms]
  `(binding [*connect-account* ~v]
     ~@forms))


(defn use-connect-account!
  "Permanently sets an API version. The api version can still be
  overridden on a per-thread basis using with-connect-account."
  [s]
  (alter-var-root #'*connect-account* (constantly s)))


;; =============================================================================
;; Private
;; =============================================================================


(def ^:dynamic *url* "https://api.stripe.com/v1/")


(defmacro with-base-url
  [u & forms]
  `(binding [*url* ~u]
     ~@forms))


(defn method-url
  "URL for calling a method."
  [method]
  (str *url* method))

(s/fdef method-url
        :args (s/cat :method string?)
        :ret string?)


(defn- encode-params
  [method params]
  (case method
    :get    [:query-params params]
    :delete [:query-params params]
    :post   [:body (codec/form-encode params)]))


(defn prepare-params
  "Returns a parameter map suitable for feeding in to a request to Stripe.

  `opts` is a set of options for http-kit's client. These kick out the
  defaults.

  `params` is the parameters for the stripe API calls."
  [token method params {:keys [headers] :as opts :or {headers {}}}]
  (let [[k params'] (encode-params method params)
        base-params {:basic-auth token
                     k           params'}
        version     (or (:api-version opts) (api-version))
        connect     (or (:account opts) (connect-account))
        headers     (tb/assoc-when headers "Stripe-Version" version "Stripe-Account" connect)]
    (merge base-params {:headers headers} (dissoc opts :api-version :account :headers))))

(s/fdef prepare-params
        :args (s/cat :token  ::api-token
                     :method ::method
                     :params ::params
                     :opts   ::http-kit-options)
        :ret map?)


;; =============================================================================
;; Public API
;; =============================================================================


(defn response [res]
  (get res ::response))


(defn status [res]
  (:status (response res)))


(defn headers [res]
  (:headers (response res)))


(defn- process
  [response]
  (or (-> (json/parse-string (:body response) keyword)
          (assoc ::response response))
      {:error (:error response)}))


(defn- respond-sync
  [params {:keys [throw-on-error?] :or {throw-on-error? true}}]
  (let [response                @(http/request params)
        {:keys [error] :as res} (process response)]
    (if (and throw-on-error? (some? error))
      (throw (ex-info (get-in res [:error :message]) res))
      res)))


(defn- respond-async
  [params {:keys [out-ch throw-on-error?] :or {throw-on-error? true}}]
  (http/request params
                (fn [res]
                  (let [{:keys [error] :as res} (process res)]
                    (->> (if (and throw-on-error? (some? error))
                           (ex-info (get-in res [:error :message]) res)
                           res)
                         (a/put! out-ch)))
                  (a/close! out-ch))))


(defn api-call
  "Call an API method on Stripe. If an output channel is supplied, the
  method will place the result in that channel; if not, returns
  synchronously."
  [{:keys [params client-options token account method endpoint out-ch throw-on-error?]
    :or   {params         {}
           client-options {}
           account        *connect-account*
           token          (api-token)}
    :as   opts}]
  (assert token "API Token must not be nil.")
  (let [url     (method-url endpoint)
        params' (->> (assoc client-options :account account)
                     (prepare-params token method params)
                     (merge {:method method :url url}))]
    (if-not (some? out-ch)
      (respond-sync params' opts)
      (do (respond-async params' opts)
          out-ch))))

(s/fdef api-call
        :args (s/cat :params ::api-call)
        :ret (s/or :result map? :chan ta/chan?))


(defmacro defapi
  "Generates a synchronous and async version of the same function."
  [sym method]
  `(defn ~sym
     ([endpoint#]
      (~sym endpoint# {}))
     ([endpoint# opts#]
      (api-call
       (assoc opts#
              :method ~method
              :endpoint endpoint#)))))

(s/fdef defapi
        :args (s/cat :symbol symbol? :method keyword?)
        :ret list?)


(defapi post-req :post)
(defapi get-req :get)
(defapi delete-req :delete)
