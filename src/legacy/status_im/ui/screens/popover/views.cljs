(ns legacy.status-im.ui.screens.popover.views
  (:require-macros [legacy.status-im.utils.views :as views])
  (:require
    ["react-native" :refer (BackHandler)]
    [legacy.status-im.ui.components.animation :as anim]
    [legacy.status-im.ui.components.colors :as colors]
    [legacy.status-im.ui.components.react :as react]
    [legacy.status-im.ui.screens.profile.user.views :as profile.user]
    [legacy.status-im.ui.screens.reset-password.views :as reset-password.views]
    [re-frame.core :as re-frame]
    [react-native.platform :as platform]
    [reagent.core :as reagent]))

(defn hide-panel-anim
  [bottom-anim-value alpha-value window-height]
  (anim/start
   (anim/parallel
    [(anim/timing bottom-anim-value
                  {:toValue         (- window-height)
                   :duration        300
                   :useNativeDriver true})
     (anim/timing alpha-value
                  {:toValue         0
                   :duration        300
                   :useNativeDriver true})])))

(defn show-panel-anim
  [bottom-anim-value alpha-value]
  (anim/start
   (anim/parallel
    [(anim/timing bottom-anim-value
                  {:toValue         0
                   :duration        300
                   :useNativeDriver true})
     (anim/timing alpha-value
                  {:toValue         0.4
                   :duration        300
                   :useNativeDriver true})])))

(defn popover-view
  [_ window-height]
  (let [bottom-anim-value (anim/create-value window-height)
        alpha-value       (anim/create-value 0)
        clear-timeout     (atom nil)
        current-popover   (reagent/atom nil)
        update?           (reagent/atom nil)
        request-close     (fn []
                            (when-not (:prevent-closing? @current-popover)
                              (reset! clear-timeout
                                (js/setTimeout
                                 #(do (reset! current-popover nil)
                                      (re-frame/dispatch [:hide-popover]))
                                 300))
                              (hide-panel-anim
                               bottom-anim-value
                               alpha-value
                               (- window-height)))
                            true)
        on-show           (fn []
                            (show-panel-anim bottom-anim-value alpha-value)
                            (when platform/android?
                              (.removeEventListener BackHandler
                                                    "hardwareBackPress"
                                                    request-close)
                              (.addEventListener BackHandler
                                                 "hardwareBackPress"
                                                 request-close)))
        on-hide           (fn []
                            (when platform/android?
                              (.removeEventListener BackHandler
                                                    "hardwareBackPress"
                                                    request-close)))]
    (reagent/create-class
     {:UNSAFE_componentWillUpdate
      (fn [_ [_ popover _]]
        (when @clear-timeout (js/clearTimeout @clear-timeout))
        (cond
          @update?
          (do (reset! update? false)
              (on-show))

          (and @current-popover popover)
          (do (reset! update? true)
              (js/setTimeout #(reset! current-popover popover) 600)
              (hide-panel-anim bottom-anim-value alpha-value (- window-height)))

          popover
          (do (reset! current-popover popover)
              (on-show))

          :else
          (do (reset! current-popover nil)
              (on-hide))))
      :component-will-unmount on-hide
      :reagent-render
      (fn []
        (when @current-popover
          (let [{:keys [view style disable-touchable-overlay? blur-view? blur-view-props]}
                @current-popover
                component (if blur-view? react/blur-view react/view)
                overlay-component (if disable-touchable-overlay? react/view react/touchable-highlight)]
            [component
             (merge {:style {:position :absolute :top 0 :bottom 0 :left 0 :right 0}} blur-view-props)
             (when platform/ios?
               [react/animated-view
                {:style {:flex 1 :background-color colors/black-persist :opacity alpha-value}}])
             [react/animated-view
              {:style
               {:position  :absolute
                :height    window-height
                :left      0
                :right     0
                :transform [{:translateY bottom-anim-value}]}}
              [overlay-component
               {:style    {:flex 1 :align-items :center :justify-content :center}
                :on-press request-close}
               [react/view
                (merge {:background-color (if blur-view? :transparent colors/white)
                        :border-radius    16
                        :margin           32
                        :shadow-offset    {:width 0 :height 2}
                        :shadow-radius    8
                        :shadow-opacity   1
                        :shadow-color     "rgba(0, 9, 26, 0.12)"}
                       style)
                (cond
                  (vector? view)
                  view

                  (= :share-chat-key view)
                  [profile.user/share-chat-key]

                  (= :password-reset-popover view)
                  [reset-password.views/reset-password-popover]

                  :else
                  [view])]]]])))})))

(views/defview popover
  []
  (views/letsubs [current-popover         [:popover/popover]
                  {window-height :height} [:dimensions/window]]
    [popover-view current-popover window-height]))
