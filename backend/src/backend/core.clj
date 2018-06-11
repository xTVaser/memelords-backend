(ns backend.core
  (:import
   [io.netty.handler.ssl SslContextBuilder]
   [com.google.cloud.datastore Datastore DatastoreOptions Entity Key])
  (:require
   [compojure.core :as compojure :refer [GET POST]]
   [buddy.hashers :as hashers]
   [buddy.sign.jwt :as jwt]
   [ring.middleware.params :as params]
   [ring.middleware.json :refer [wrap-json-response]]
   [compojure.route :as route]
   [compojure.response :refer [Renderable]]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [manifold.deferred :as d]
   [clojure.core.async :as a]
   [clojure.java.io :refer [file]]))

; http://aleph.io/examples/literate.html taken from here as a starting point

; authentication
; potentially could add a time-to-live but not critical
; have a user-id that we could check for banning purposes


; Will have to customize indexing rules as indexing forbids length

;$env:GOOGLE_APPLICATION_CREDENTIALS= "F:\Repos\memelords\backend\datastore-creds.json"
(def ^:private datastore (.getService (DatastoreOptions/getDefaultInstance)))

(defn ^:private create-key [key kind]
  (let [factory (.newKeyFactory datastore)]
    (doto factory
      (.setKind kind))
    (.newKey factory key)))

(defn ^:private create-entity [key m]
  (let [builder (Entity/newBuilder key)]
    (doseq [[key value] m]
      (.set builder key (str value)))
    (.build builder)))

;(write! {:kind "test" :id 123} {"test" "123"})
(defn write! [{:keys [kind id]} m]
  (let [key (create-key id kind)
        entity (create-entity key m)]
    (.put datastore entity)))

(defn read [{:keys [kind id]}]
  (let [key (create-key id kind)
        entity (.get datastore key)]
    (when-not (nil? entity)
      (into {}
            (map (fn [key]
                   [key (read-string (.getString entity key))]))
            (.getNames entity)))))

(def jwt-secret (or (System/getenv "JWT_SECRET") "password123"))

(defn login-handler
  ""
  [req]
  ; TODO ensure username and password were passed in
  (let [username (-> req (:params) (get "username"))
        password (-> req (:params) (get "password"))]
    ; TODO check db for ban list
    ; TODO Validate in DB
    {:status 200
     :headers {"content-type" "application/json"}
     :body {:jwt (jwt/sign {:username username
                      :scopes ["post-memes" "comment" "moderate" "root"]}
                     jwt-secret)}}))

(def default-scopes ["view-memes" "post-memes" "comment"])

(defn register-handler
  ""
  [req]
  (let [username (-> req (:params) (get "username"))
        password (-> req (:params) (get "password"))
        password-hash (hashers/derive password)
        check-db (read {:kind "user" :id username})]
    ; TODO ensure username and password are present in the case of a bad API call from frontend doods
    (if (nil? check-db)
      (do
        (write! {:kind "user" :id username} {"password" password-hash "scopes" default-scopes})
        {:status 200
         :headers {"content-type" "application/json"}
         :body (jwt/sign {:username username
                          :scopes default-scopes}
                         jwt-secret)})
      ; else
      {:status 500
       :headers {"content-type" "application/json"}
       :body {:error "Username already taken"}})))




;(jwt/unsign jwt "secret")

(defn hello-world-handler
  "A basic Ring handler which immediately returns 'hello world'"
  [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "hello world!"})

(defn memes-handler
  "Memes Handler"
  [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body (str "memes" req)})

(defn specific-meme-handler
  "Memes Handler"
  [req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body (str "meme id " (-> req (:route-params) (:id)) req)})

;; Compojure will normally dereference deferreds and return the realized value.
;; Unfortunately, this blocks the thread. Since Aleph can accept the unrealized
;; deferred, we extend Compojure's `Renderable` protocol to pass the deferred
;; through unchanged so it can be handled asynchronously.
(extend-protocol Renderable
  manifold.deferred.IDeferred
  (render [d _] d))

;; This handler defines a set of endpoints via Compojure's `routes` macro.  Notice that above
;; we've added the `GET` macro via `:refer` so it doesn't have to be qualified.  We wrap the
;; result in `ring.middleware.params/wrap-params` so that we can get the `count` parameter in
;; `streaming-numbers-handler`.
;;
;; Notice that at the bottom we define a default `compojure.route/not-found` handler, which
;; will return a `404` status.  If we don't do this, a call to a URI we don't recognize will
;; return a `nil` response, which will cause Aleph to log an error.
(def handler
  (wrap-json-response (params/wrap-params
                       (compojure/routes
                        (GET "/hello"                       [] hello-world-handler)
                        (GET "/register"                    [] register-handler)
                        (GET "/resetpassword"               [] hello-world-handler)
                        (GET "/login"                       [] login-handler)
                        (GET "/memes"                       [] memes-handler)
                        (GET "/memes/:id"                   [id] specific-meme-handler)
                        (GET "/memes/:id/comments"          [id] memes-handler) ; probably want a pagination URL parameter (varaible?).
                        (POST "/memes"                      [] hello-world-handler)
                        (POST "/memes/:id/comments"         [id] hello-world-handler)
                        (route/not-found "No such page.")))))




(defn -main [& args]
  (http/start-server handler {:port 10000}))


;; TODO

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