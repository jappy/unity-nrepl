(ns ^{:author "Chas Emerick"}
  clojure.tools.nrepl.transport
  (:require [clojure.tools.nrepl.bencode :as be]
            [clojure.clr.io :as io]
            [clojure.tools.nrepl.debug :as debug]
            [clojure.tools.nrepl.sync-channel :as sc]
            (clojure walk set))
  (:use [clojure.tools.nrepl.misc :only (returning uuid)])
  (:refer-clojure :exclude (send))
  (:import (UnityEngine Debug))
  (:import (System.IO Stream EndOfStreamException)
           (System.Net.Sockets Socket SocketException)
           (System.Collections.Generic |List`1[System.Object]|))
  (:import (clojure.lang PushbackInputStream PushbackTextReader)
           ;;clojure.lang.RT
           ))

(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg. Implementations should return the transport."))

(deftype FnTransport [recv-fn send-fn close]
  Transport
  ;; TODO this keywordization/stringification has no business being in FnTransport
  (send [this msg]
    (-> msg clojure.walk/stringify-keys send-fn) this)
  (recv [this]
    (.recv this Int32/MaxValue))
  (recv [this timeout]
    (clojure.walk/keywordize-keys (recv-fn timeout)))
  System.IDisposable
  (Dispose [this]
    (close)))

(defn fn-transport
  "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
  ([read write] (fn-transport read write nil))
  ([read write close]
     (let [read-queue (sc/make-simple-sync-channel)]
       (future (try
                 (while true
                   (sc/put read-queue (read)))
                 (catch Exception t
                   (sc/put read-queue t))))
       (FnTransport.
        (let [failure (atom nil)]
          #(if @failure
             (throw @failure)
             (let [msg (sc/poll read-queue %)]
               (if (instance? Exception msg)
                 (do
                   (reset! failure msg) (throw msg))
                 msg))))
        write
        close))))

(defmulti #^{:private true} <bytes class)

(defmethod <bytes :default
  [input]
  input)

(def #^{:private true} utf8 (System.Text.UTF8Encoding.))

(defmethod <bytes |System.Byte[]|    ;;DM: (RT/classForName "[B")
  [#^|System.Byte[]| input]          ;;DM: #^"[B"
  (.GetString utf8 input))           ;;DM: (String. input "UTF-8"))

(defmethod <bytes clojure.lang.IPersistentVector
  [input]
  (vec (map <bytes input)))

(defmethod <bytes clojure.lang.IPersistentMap
  [input]
  (->> input
       (map (fn [[k v]] [k (<bytes v)]))
       (into {})))

(defmacro ^{:private true} rethrow-on-disconnection
  [^Socket s & body]
  `(try
     ~@body
     (catch EndOfStreamException e#
       (throw (ObjectDisposedException.
               "The transport's socket appears to have lost its connection to the nREPL server" e#)))
     (catch Exception e#
       (if (or (instance? SocketException (#'clojure.main/root-cause e#))
               (and ~s (not (.Connected ~s))))
         (throw (ObjectDisposedException.
                 "The transport's socket appears to have lost its connection to the nREPL server" e#))
         (throw e#)))))

(def bencode-sockets (atom ())) ;DEBUG

(defn bencode
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using bencode."
  ([^Socket s] (bencode s s s))
  ([in out & [^Socket s]]
     #_(debug/prn-thread "Creating bencode")  ;DEBUG
     (swap! bencode-sockets conj s)
     (let [in (PushbackInputStream. (io/input-stream in))
           out (io/output-stream out)]
       (fn-transport
        #(let [payload (rethrow-on-disconnection s (be/read-bencode in))
               unencoded (<bytes (payload "-unencoded"))
               to-decode (apply dissoc payload "-unencoded" unencoded)]
           (merge (dissoc payload "-unencoded")
                  (when unencoded {"-unencoded" unencoded})
                  (<bytes to-decode)))
        #(rethrow-on-disconnection s
                                   (locking out
                                     (doto out
                                       (be/write-bencode %)
                                       .Flush)))
        (fn []
          (.Close in)
          (.Close out)
          (when s (.Close s)))))))

(defn tty
  "Returns a Transport implementation suitable for serving an nREPL backend
   via simple in/out readers, as with a tty or telnet connection."
  ([^Socket s] (tty s s s))
  ([in out & [^Socket s]]
     (let [r (PushbackTextReader. (io/text-reader in))
           w (io/text-writer out)
           cns (atom "user")
           prompt (fn [newline?]
                    (when newline? (.Write w (int \newline)))
                    (.Write w (str @cns "=> ")))
           session-id (atom nil)
           read-msg #(let [code (read r)]
                       (merge {:op "eval" :code [code] :ns @cns :id (str "eval" (uuid))}
                              (when @session-id {:session @session-id})))
           read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
           write (fn [{:strs [out err value status ns new-session id] :as msg}]
                   (when new-session (reset! session-id new-session))
                   (when ns (reset! cns ns))
                   (doseq [^String x [out err value] :when x]
                     (.Write w x))
                   (when (and (= status #{:done}) id (.startsWith ^String id "eval"))
                     (prompt true))
                   (.Flush w))
           read #(let [head (promise)]
                   (swap! read-seq (fn [s]
                                     (deliver head (first s))
                                     (rest s)))
                   @head)]
       (fn-transport read write
                     (when s
                       (swap! read-seq (partial cons {:session @session-id :op "close"}))
                       #(.Close s))))))

(defn tty-greeting
  "A greeting fn usable with clojure.tools.nrepl.server/start-server,
   meant to be used in conjunction with Transports returned by the
   `tty` function.

   Usually, Clojure-aware client-side tooling would provide this upon connecting
   to the server, but telnet et al. isn't that."
  [transport]
  (do
    (send transport {:out (str ";; Clojure " (clojure-version)
                               \newline "user=> "
                               )})))

(deftype QueueTransport [^|System.Collections.Generic.List`1[System.Object]| in
                         ^|System.Collections.Generic.List`1[System.Object]| out]
  clojure.tools.nrepl.transport.Transport
  (send [this msg] (.Add out msg) this)
  (recv [this] (.Take in))
  (recv [this timeout] (let [x nil] (.TryTake in (by-ref x) (int timeout)) x)))

(defn piped-transports
  "Returns a pair of Transports that read from and write to each other."
  []
  (let [a (|System.Collections.Generic.List`1[System.Object]|.)
        b (|System.Collections.Generic.List`1[System.Object]|.)]
    [(QueueTransport. a b) (QueueTransport. b a)]))
