(ns backend.core
  (:gen-class)
  (:import
   [io.netty.handler.ssl SslContextBuilder]
   [com.google.cloud.datastore Datastore DatastoreOptions Entity EntityQuery Key StringValue TimestampValue Query Cursor StructuredQuery$OrderBy]
   [com.google.auth.oauth2 ServiceAccountCredentials]
   [com.google.cloud Timestamp])
  (:require
   [clojure.string :as string]
   [compojure.core :as compojure :refer [GET POST OPTIONS defroutes wrap-routes]]
   [buddy.hashers :as hashers]
   [buddy.sign.jwt :as jwt]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.params :as params]
   [ring.middleware.json :refer [wrap-json-response]]
   [compojure.route :as route]
   [compojure.response :refer [Renderable]]
   [aleph.http :as http]
   [aleph.netty :as netty]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [clojure.java.io :refer [file]]))

; http://aleph.io/examples/literate.html taken from here as a starting point

; authentication
; potentially could add a time-to-live but not critical
; have a user-id that we could check for banning purposes

(def credentials (json/read-str (System/getenv "GOOGLE_CREDENTIALS")))

(def resolved-creds (ServiceAccountCredentials/fromPkcs8
                     (get credentials "client_id")
                     (get credentials "client_email")
                     (get credentials "private_key")
                     (get credentials "private_key_id")
                     nil))

; ; Will have to customize indexing rules as indexing forbids length (unless we limit length)
(def ^:private datastore (let [builder (DatastoreOptions/newBuilder)]
                           (doto builder
                             (.setCredentials resolved-creds)
                             (.setProjectId (get credentials "project_id")))
                           (.getService (.build builder))))

; Local Development - ;$env:GOOGLE_APPLICATION_CREDENTIALS="F:\Repos\memelords-backend\datastore-creds.json"
; $env:PORT=80
; TODO - Make this work locally without having to comment out code
; (def ^:private datastore (.getService (DatastoreOptions/getDefaultInstance)))

(defn add-cors [map]
  (merge map {"Access-Control-Allow-Headers", "Content-Type, Authorization, Accept"
              "Access-Control-Allow-Methods", "OPTIONS, GET, POST"
              "Access-Control-Allow-Origin", "*"}))

(defn ^:private create-key [key kind]
  (let [factory (.newKeyFactory datastore)]
    (doto factory
      (.setKind kind))
    (.newKey factory key)))

(defn ^:private auto-gen-key [kind]
  (let [factory (.newKeyFactory datastore)]
    (doto factory
      (.setKind kind))
    (.newKey factory)))

(defn ^:private create-entity [key m]
  (let [builder (Entity/newBuilder key)]
    (doseq [[key args] m]
      ; TODO disgusting, clean this up
      (if (:indexed? args)
        (.set builder key (str (:val args)))
        (let [string-builder (StringValue/newBuilder (str (:val args)))]
          (.setExcludeFromIndexes string-builder true)
          (.set builder key (.build string-builder)))))
    (let [timestamp-builder (TimestampValue/newBuilder (Timestamp/now))]
      (.set builder "timestamp" (.build timestamp-builder)))
    (.build builder)))

(defn write! [{:keys [kind id]} m]
  (let [key (create-key id kind)
        entity (create-entity key m)]
    (.put datastore entity)))

(defn write-genid! [{:keys [kind]} m]
  (let [key (auto-gen-key kind)
        entity (create-entity key m)]
    (.put datastore entity)))

(defn read-datastore [{:keys [kind id]}]
  (let [key (create-key id kind)
        entity (.get datastore key)]
    (when-not (nil? entity)
      (into {}
            (map (fn [key]
                   (if (= key "timestamp")
                     [key (.getTimestamp entity key)]
                     [key (read-string (.getString entity key))])))
            (.getNames entity)))))

(def jwt-secret (or (System/getenv "JWT_SECRET") "password123"))

; TODO probably want a capctha or something
(defn login-handler
  ""
  [req]
  ; TODO ensure username and password were passed in
  (let [username (-> req (:params) (get "username"))
        password (-> req (:params) (get "password"))
        check-db (read-datastore {:kind "user" :id username})]
    (if (some? check-db)
      (if (hashers/check password (get check-db "password"))
        {:status 200
         :headers (add-cors {"content-type" "application/json"})
         :body {:jwt (str "Bearer " (jwt/sign {:username username
                                               :scopes (get check-db "scopes")
                                               :created (new java.util.Date)}
                                              jwt-secret))
                :message "User successfully signed in"}}
        ;; TODO this can be greatly minified
        {:status 403
         :headers (add-cors {"content-type" "application/json"})
         :body {:error "Invalid credentials"}})
      ; else
      {:status 403
       :headers (add-cors {"content-type" "application/json"})
       :body {:error "User does not exist"}})))

(def default-scopes ["view-memes" "post-memes" "comment"])

(defn register-handler
  ""
  [req]
  (let [username (-> req (:params) (get "username"))
        password (-> req (:params) (get "password"))
        password-hash (hashers/derive password)
        check-db (read-datastore {:kind "user" :id username})]
    ; TODO ensure username and password are present in the case of a bad API call from frontend doods
    (if (nil? check-db)
      (do
        (write! {:kind "user" :id username} {"password" {:val (str "\"" password-hash "\""), :indexed? false}
                                             "scopes"   {:val default-scopes, :indexed? true}})
        {:status 200
         :headers (add-cors {"content-type" "application/json"})
         :body {:jwt (str "Bearer " (jwt/sign {:username username
                                               :scopes default-scopes
                                               :created (new java.util.Date)}
                                              jwt-secret))
                :message "User successfully registered"}})
      ; else
      {:status 400
       :headers (add-cors {"content-type" "application/json"})
       :body {:error "Username already taken"}})))

; TODO emmet strip html tags
(defn publish-meme-handler
  [req]
  (let [title (-> req (:params) (get "title"))
        caption (-> req (:params) (get "caption"))
        link (-> req (:params) (get "link"))]
    ; TODO wrap in try-catches
    (let [result (write-genid! {:kind "meme"} {"title" {:val title :indexed? true}
                                               "caption" {:val caption :indexed? false}
                                               "link" {:val link :indexed? false}
                                               "comments" {:val [] :indexed? false}})]
      {:status 200
       :headers (add-cors {"content-type" "application/json"})
       :body {:message "Meme published successfully"
              :id (.getId (.getKey result))}})))

(defn hello-world-handler
  "A basic Ring handler which immediately returns 'hello world'"
  [req]
  {:status 200
   :headers (add-cors {"content-type" "text/plain"})
   :body "hello world!"})

(def page-size 25)

(defn read-with-cursor
  [cursor-param, kind, sort-key ascending]
  (let [order-by (if ascending
                   (StructuredQuery$OrderBy/asc sort-key)
                   (StructuredQuery$OrderBy/desc sort-key))
        query (.setOrderBy (.setLimit (.setKind (Query/newEntityQueryBuilder) kind) (int page-size)) order-by (make-array StructuredQuery$OrderBy 0))] ; https://dev.clojure.org/jira/browse/CLJ-440
    ; try catch incase invalid cursor passed in
    (when (some? cursor-param)
      (.setStartCursor query (Cursor/fromUrlSafe cursor-param)))
    (let [raw-results (.run datastore (.build query))
          results (loop [result []] (if (.hasNext raw-results) (recur (conj result (.next raw-results))) result))]
      {:entities results
       :next-cursor (if (> (count results) 0)
                      (.toUrlSafe (.getCursorAfter raw-results))
                      nil)})))

(defn meme-to-edn
  [m]
  {:id (.getId (.getKey m))
   :title (.getString m "title")
   :caption (.getString m "caption")
   :link (.getString m "link")
   :comments (read-string (.getString m "comments"))
   :timestamp (str (.getTimestamp m "timestamp"))})

(defn memes-handler
  "Memes Handler"
  ([req]
   ; look for next page number
   (let [params (:params req)
         next-page (get params "next-page")
         sort-direction (and (contains? params "sort") (= (string/lower-case (get params "sort")) "asc"))
         results (read-with-cursor next-page "meme" "timestamp" sort-direction)]
     {:status 200
      :headers (add-cors {"content-type" "application/json"})
      :body {:memes (map meme-to-edn (:entities results))
             :next-page (:next-cursor results)}}))
  ([req id]
   {:status 200
    :headers (add-cors {"content-type" "application/json"})
    ; TODO doesnt currently handle if it finds nothing nicely
    :body (read-datastore {:kind "meme" :id id})}))

;; Compojure will normally dereference deferreds and return the realized value.
;; Unfortunately, this blocks the thread. Since Aleph can accept the unrealized
;; deferred, we extend Compojure's `Renderable` protocol to pass the deferred
;; through unchanged so it can be handled asynchronously.
(extend-protocol Renderable
  manifold.deferred.IDeferred
  (render [d _] d))

(defn verify-jwt [handler required-scopes]
  (fn [req]
    ; If no scopes, then let it through
    (println req)
    (println required-scopes)
    (try
      (let [token (jwt/unsign (subs (get (:headers req) "authorization") 7) jwt-secret)
            provided-scopes (into #{} (:scopes token))]
        (if (clojure.set/subset? (:required-scopes req) provided-scopes)
          (handler req)
          {:status 403
           :headers (add-cors {"content-type" "application/json"})
           :body "Access Denied"}))
      (catch Exception e
        (println e)
        {:status 401
         :headers (add-cors {"content-type" "application/json"})
         :body "Invalid Credentials"}))))

(defn generic-cors [& args]
  {:status 200
   :headers (add-cors {"content-type" "application/json"})})

(defroutes view-routes*
  (GET  "/memes"                                []             memes-handler)
  (GET  "/memes/:id"                            [id :as req]   (memes-handler req id)))

(defroutes post-routes*
  (POST "/memes"                                []             publish-meme-handler)
  (POST "/memes/:id/comments"                   [id]           hello-world-handler))
; TODO simplify no replies at this time (POST "/memes/:id/comments/:commentId/reply"  [meme-id, comment-id] hello-world-handler)

(defroutes public-routes*
  (GET  "/resetpassword"                        []             hello-world-handler) ; TODO not implemented yet
  (GET  "/login"                                []             login-handler)
  (POST "/register"                             []             register-handler)
  (OPTIONS "/memes"                             []             generic-cors)
  (OPTIONS  "/memes/:id"                            [id :as req]   generic-cors)
  (OPTIONS  "/login"                                []         generic-cors)
  (OPTIONS "/register"                             []             generic-cors))

(def app
  (compojure/routes (-> view-routes*
                        (wrap-cors)
                        (params/wrap-params)
                        (wrap-routes verify-jwt #{"view-memes"})
                        (wrap-json-response))
                    (-> post-routes*
                        (wrap-cors)
                        (params/wrap-params)
                        (wrap-routes verify-jwt #{"post-memes"})
                        (wrap-json-response))
                    (-> public-routes*
                        (wrap-cors)
                        (params/wrap-params)
                        (wrap-json-response))
                    (route/not-found "Page Not Found")))

(defn -main [& args]
  (println (str "Starting up Server on Port " (System/getenv "PORT") " chosen by Heroku.  Will be publically bound to 80"))
  (when-not *compile-files* (netty/wait-for-close (http/start-server app {:port (Integer/parseInt (System/getenv "PORT"))})))) ;https://stackoverflow.com/questions/32288195/why-lein-uberjar-evaluates-variables-defined-with-def


;; TODO TLS

; ;; ### TLS client certificate authentication

; ;; Aleph also supports TLS client certificate authentication. To make such connections, we must
; ;; build a custom SSL context and pass it to a connection pool that we'll use to make HTTP requests.

; (defn build-ssl-context
;   "Given the certificate authority (ca)'s certificate, a client certificate, and the key for the
;   client certificate in PKCS#8 format, we can build an SSL context for mutual TLS authentication."
;   [ca cert key]
;   (-> (SslContextBuilder/forClient)
;       (.trustManager (file ca))
;       (.keyManager (file cert) (file key))
;       .build))

; (defn ssl-connection-pool
;   "To use the SSL context, we set the `:ssl-context` connection option on a connection pool. This
;   allows the pool to make TLS connections with our client certificate."
;   [ca cert key]
;   (http/connection-pool
;    {:connection-options
;     {:ssl-context (build-ssl-context ca cert key)}}))

; ;; We can use our `ssl-connection-pool` builder to GET pages from our target endpoint by passing the
; ;; `:pool` option to `aleph.http/get`.

; @(d/chain
;   (http/get
;    "https://server.with.tls.client.auth"
;    {:pool (ssl-connection-pool "path/to/ca.crt" "path/to/cert.crt" "path/to/key.k8")})
;   :body
;   bs/to-string)