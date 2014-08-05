(ns clojure.tools.nrepl.debug
  (:import (UnityEngine Debug)))

(def ^{:private true} pr-agent (agent *out*))

(defn- write-out [out & args]
  (Debug/Log (str "Thd " (-> System.Threading.Thread/CurrentThread (.ManagedThreadId)) ": " (apply str args))))

(comment (defn- write-out [out & args]
           (binding [*out* out]
             (pr "Thd " (-> System.Threading.Thread/CurrentThread (.ManagedThreadId)) ": ")
             (apply prn args)
             out)))

(defn prn-thread [& args]
  (send pr-agent write-out args))
