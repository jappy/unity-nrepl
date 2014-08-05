(ns nrepl
  (:refer-clojure)
  (:require [clojure.main :as main])
  (:import
   (UnityEngine Debug GUILayout)
   (UnityEditor EditorGUILayout EditorWindow EditorStyles))
  (:require [clojure.tools.nrepl.middleware :as middleware]
            [unity.nrepl.middleware.interruptible-eval :as eval]
            [unity.nrepl.middleware.session :as session]
            [unity.nrepl.middleware.load-file :as load-file]
            )
  (:use [clojure.tools.nrepl.server :only (start-server stop-server
                                           ;default-handler
                                           unknown-op)]))

(Debug/Log (str "CLR Version: v." Environment/Version))

(def PORT 4555)
(def HOST "0.0.0.0")

(declare server)

(def default-middlewares
  [#'clojure.tools.nrepl.middleware/wrap-describe
   #'eval/interruptible-eval
   #'load-file/wrap-load-file
   #'session/add-stdin
   #'session/session])

(defn default-handler
  "A default handler supporting interruptible evaluation, stdin, sessions, and
   readable representations of evaluated expressions via `pr`.

   Additional middlewares to mix into the default stack may be provided; these
   should all be values (usually vars) that have an nREPL middleware descriptor
   in their metadata (see clojure.tools.nrepl.middleware/set-descriptor!)."
  [& additional-middlewares]
  (let [stack (middleware/linearize-middleware-stack (concat default-middlewares
                                                             additional-middlewares))]
    ((apply comp (reverse stack)) unknown-op)))

(def server-running? (atom false))

(defn server-start
  ([] (server-start PORT HOST))
  ([port host]
      (do
        (def server (start-server :port (or port PORT)
                                  ;;:bind (or host HOST)
                                  :handler (default-handler)
                                  ;;:greeting-fn (fn [msg] (Debug/Log "received greeting: " msg))
                                  ))
        (swap! server-running? not))))

(defn server-stop
  ([] (server-stop "default-reason"))
  ([msg]
     (if @server-running?
       (do
         (stop-server server)
         (swap! server-running? not)))))

(defn start [this]
  (Debug/Log "calling start")
  (server-start))

(defn update [this]
  (eval/process-queue))

(defn on-destroy []
  (Debug/Log "on-disable")
  (server-stop "upon destroy."))

(defn on-disable []
  (Debug/Log "on-disable")
  (server-stop "upon disable."))
