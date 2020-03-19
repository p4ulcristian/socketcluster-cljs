(ns frontend.core
  (:require-macros [cljs.core.async.macros :refer [go alt! go-loop]])
  (:require
    [reagent.core :as reagent :refer [atom]]
    [reagent.session :as session]
    [reitit.frontend :as reitit]
    [clerk.core :as clerk]
    [accountant.core :as accountant]
    [cljs.reader :refer [read-string]]
    [cljs.core.async :as async :refer [<! >! put! chan timeout close!]]))


(def socket (.create js/socketClusterClient))

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
                      (.log js/console "rejected :(")
                      (when close? (close! output))))))
(defn async-iter-chan
      "Take an async iterable `iter-obj` and push it to channel `output`. Closes
      `output` when the iterable is exhausted. Provide a false `:close?` argument to
      leave the output open."
      [output iter-obj & {:keys [close? rejected] :or {close? true rejected js/console.error}}]
      (.log js/console "added")
      (async-iter-next
        output (js-invoke iter-obj js/Symbol.asyncIterator) close? rejected)
      output)

(def wss-connections-chan (async/chan))
(def wss-procedure-chan (async/chan))

(go-loop [x 1]
         (let [conn (<! wss-connections-chan)]
              (.log js/console "cljs-wss-connected")
              (recur (inc x))))
(go-loop [x 1]
         (let [conn (<! wss-procedure-chan)]
              (.log js/console "wss-procedure-answer")
              (recur (inc x))))

(async-iter-chan wss-connections-chan (.listener socket "connect"))



(def router
  (reitit/router
    [["/" :index]]))



(defn client-page []
      [:div
       [:h1.uk-text-center.uk-padding-small "cljs-socketcluster"]

       [:div.uk-flex.uk-flex-center
        [:button.uk-button-default.uk-button
         {:on-click #(.transmit socket "simple-event" "hello test")}
         "Transmit"]
        [:button.uk-button-default.uk-button
         {:on-click #(async-iter-chan wss-procedure-chan (.invoke socket "simple-proc" "Hi browser"))}
         "Procedure"]]])


(defn mount-root []
      (reagent/render [client-page] (.getElementById js/document "app")))

(defn init []
  (clerk/initialize!)
  (accountant/configure-navigation!
    {:nav-handler
     (fn [path]
         (let [match (reitit/match-by-path router path)
               current-page (:name (:data  match))
               route-params (:path-params match)]
              (reagent/after-render clerk/after-render!)
              ;(dispatch [:add-to-db {:current-page current-page
              ;                       :route-params route-params)
              (clerk/navigate-page! path)))

     :path-exists?
     (fn [path]
         (boolean (reitit/match-by-path router path)))})
  (accountant/dispatch-current!)
  (mount-root))