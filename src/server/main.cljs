(ns server.main
  (:require-macros [cljs.core.async.macros :refer [go alt! go-loop]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >! put! chan timeout close!]]))


;Requires for node libraries
(defonce http (nodejs/require "http"))
(defonce eetase (nodejs/require "eetase"))
(defonce socketcluster-server (nodejs/require "socketcluster-server"))
(defonce express (nodejs/require "express"))
(defonce serve-static (nodejs/require "serve-static"))
(defonce path (nodejs/require "path"))
(defonce morgan (nodejs/require "morgan"))
(defonce scc-broker-client (nodejs/require "scc-broker-client"))
(defonce http (nodejs/require "http"))

;interop needed for these two
(def process (js* "process"))
(def __dirname (js* "__dirname"))

;enviroment variables for socketcluster cluster
(def enviroment  (or (-> process .-env .-ENV) "dev"))
(def socketcluster-port  (or (-> process .-env .-SOCKETCLUSTER_PORT) 8000))
(def socketcluster-ws-engine  (or (-> process .-env .-SOCKETCLUSTER_WS_ENGINE) "ws"))
(def socketcluster-socket-channel-limit  (or (int (-> process .-env .-SOCKETCLUSTER_SOCKET_CHANNEL_LIMIT)) 1000))
(def socketcluster-log-level  (or (-> process .-env .-SOCKETCLUSTER_LOG_LEVEL) 2))
(def scc-instance-id (random-uuid))
(def scc-state-server-host (or (-> process .-env .-SCC_STATE_SERVER_HOST) nil))
(def scc-state-server-port (or (-> process .-env .-SCC_STATE_SERVER_PORT) nil))
(def scc-mapping-engine (or (-> process .-env .-SCC_MAPPING_ENGINE) nil))
(def scc-client-pool-size (or (-> process .-env .-SCC_CLIENT_POOL_SIZE) nil))
(def scc-auth-key (or (-> process .-env .-SCC_AUTH_KEY) nil))
(def scc-instance-ip (or (-> process .-env .-SCC_INSTANCE_IP) nil))
(def scc-instance-ip-family (or (-> process .-env .-SCC_INSTANCE_IP_FAMILY) nil))
(def scc-state-server-connect-timeout (or (int (-> process .-env .-SCC_STATE_SERVER_CONNECT_TIMEOUT)) nil))
(def scc-state-server-ack-timeout (or (int (-> process .-env .-SCC_STATE_SERVER_ACK_TIMEOUT)) nil))
(def scc-state-server-reconnect-randomness (or (int (-> process .-env .-SCC_STATE_SERVER_RECONNECT_RANDOMNESS)) nil))
(def scc-pub-sub-batch-duration (or (int (-> process .-env .-SCC_PUB_SUB_BATCH_DURATION)) nil))
(def scc-broker-retry-delay (or (int (-> process .-env .-SCC_BROKER_RETRY_DELAY))  nil))

(def ag-options {})

(if (-> process .-env .-SOCKETCLUSTER_OPTIONS)
  (let [env-options (.parse js/JSON.parse (-> process .-env .-SOCKETCLUSTER_OPTIONS))]
       (.log js/console "socketcluster-options okay")
       (.assign js/Object ag-options env-options)))

(def http-server (eetase (.createServer http)))
(def ag-server (.attach socketcluster-server http-server ag-options))

(def express-app (express))
;Missing morgan dev, later
(. express-app (use (serve-static "public" #js {:index "index.html"})))
(. express-app (get "/health-check" (fn [req res]
                                        (.send
                                          (.status res 200)
                                          "OK"))))


;Beautiful interop between asyncIterable and core.async
(defn async-iter-next
      [output iterator close? rejected]
      (when-let [elem-promise (.next iterator)]
                (.then
                  elem-promise
                  (fn [next-elem]
                      (go
                        ;; Try to push the current value if we have one
                        (if (and (not (.-done next-elem)) (>! output (.-value next-elem)))
                          ;; Take the next value if the iterator is not exhausted and the output is open
                          (async-iter-next output iterator close? rejected)
                          ;; Close the channel if requested
                          (when close? (close! output)))))
                  (fn [reject-reason]
                      (rejected reject-reason)
                      (when close? (close! output))))))
(defn async-iter-chan
      "Take an async iterable `iter-obj` and push it to channel `output`. Closes
      `output` when the iterable is exhausted. Provide a false `:close?` argument to
      leave the output open."
      [output iter-obj & {:keys [close? rejected] :or {close? true rejected js/console.error}}]
      (async-iter-next
            output (js-invoke iter-obj js/Symbol.asyncIterator) close? rejected)
      output)


;Channels for handling events
(def http-chan (async/chan))
(def wss-connections-chan (async/chan))
(def wss-event-chan (async/chan))
(def wss-procedure-chan (async/chan))
(def wss-channel-chan (async/chan))
(def wss-middleware-chan (async/chan))
(def wss-error-chan (async/chan))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;SocketCluster/WebSocket connection handling loop.;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(if scc-state-server-host
  (let [scc-client (.attach scc-broker-client
                            (.-brokerEngine ag-server)
                            (clj->js {:instanceId scc-instance-id
                                      :instancePort socketcluster-port
                                      :instanceIp scc-instance-ip
                                      :instanceIpFamily scc-instance-ip-family
                                      :pubSubBatchDuration scc-pub-sub-batch-duration
                                      :stateServerHost scc-state-server-host
                                      :stateServerPort scc-state-server-port
                                      :mappingEngine scc-mapping-engine
                                      :clientPoolSize scc-client-pool-size
                                      :authKey scc-auth-key
                                      :stateServerConnectTimeout scc-state-server-connect-timeout
                                      :stateServerAckTimeout scc-state-server-ack-timeout
                                      :stateServerReconnectRandomness scc-state-server-reconnect-randomness
                                      :brokerRetryDelay scc-broker-retry-delay}))]
       (if (>= socketcluster-log-level 1)
         (let [error-chan (async/chan)]
              (async-iter-chan error-chan (.listener scc-client "error"))
              (async/pipe error-chan wss-error-chan false)))))

(go-loop [x 1]
  (let [req-data (<! wss-error-chan)]
       (.apply express-app  nil req-data)
       (.log js/console "wss-error: ")
       (recur (inc x))))

(defn main! []
      (println ";;;;;;;;;;;;;;;;;;;")
      (println ";;; App loaded! ;;;")
      (println ";;;;;;;;;;;;;;;;;;;")

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;      Middleware listener        ;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (.setMiddleware ag-server (.-MIDDLEWARE_INBOUND ag-server)
                      (fn [middleware]
                          (let [client-receive (async/chan)]
                               (async-iter-chan
                                 client-receive
                                 middleware)
                               (async/pipe client-receive wss-middleware-chan false))))
      (.log js/console "sikeres hozzaadas")



      ;;;      http and wss listener       ;;;

      (async-iter-chan wss-connections-chan (.listener ag-server "connection"))
      (async-iter-chan http-chan (.listener http-server "request"))
      (.listen http-server socketcluster-port)

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;   Go loop for http connections  ;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (go-loop [x 1]
               (let [req-data (<! http-chan)]
                    (.apply express-app  nil req-data)
                    (.log js/console "http-req: " (.-path (.-_parsedUrl (first (js->clj req-data)))))
                    (recur (inc x))))

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;Go loop for websocket connections;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (go-loop [x 1]
        (let [data (<! wss-connections-chan)]
             (println "wss-connected: " (.-id data))
             ;(.log js/console (.-clients ag-server))

             ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
             ;;;   Listen to wss-event channel   ;;;
             ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

             (let [client-receive (async/chan)]
                  (async-iter-chan
                    client-receive
                    (.receiver (.-socket data) "simple-event"))
                  (.-onClose data #(async/close! client-receive))
                  (async/pipe client-receive wss-event-chan false))

             ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
             ;;;Listen to wss-procedure channel ;;;
             ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

             (let [client-receive (async/chan)]
                  (async-iter-chan
                    client-receive
                    (.procedure (.-socket data) "simple-proc"))
                  (.-onClose data #(async/close! client-receive))
                  (async/pipe client-receive wss-procedure-chan false)))

        (recur (inc x)))



      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;        Events go-loops          ;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (go-loop [x 1]
        (let [data (<! wss-event-chan)]
             (.log js/console x ". wss-events " data)
             (recur (inc x))))

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;      Procedures go-loops        ;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (go-loop [x 1]
               (let [request (<! wss-procedure-chan)
                     data (.-data request)]
                    (.log js/console "wss-procedure " x)
                    (.end request (str "Success: " data))
                    (recur (inc x))))

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;;;      middleware go-loops        ;;;
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      (go-loop [x 1]
               (let [action (<! wss-middleware-chan)]
                    (.log js/console "wss-action-type " (.-type action))
                    (.allow action)
                    ;(.block action "pff");
                    (recur (inc x)))))





;(.transmitPublish (.-exchange ag-server) "simple-channel" "simple-data")
