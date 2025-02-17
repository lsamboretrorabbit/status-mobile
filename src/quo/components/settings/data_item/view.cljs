(ns quo.components.settings.data-item.view
  (:require
    [quo.components.avatars.account-avatar.view :as account-avatar]
    [quo.components.common.not-implemented.view :as not-implemented]
    [quo.components.icon :as icons]
    [quo.components.list-items.preview-list.view :as preview-list]
    [quo.components.markdown.text :as text]
    [quo.components.settings.data-item.style :as style]
    [quo.foundations.colors :as colors]
    [quo.theme :as quo.theme]
    [react-native.core :as rn]
    [utils.i18n :as i18n]))

(defn- left-loading
  [{:keys [size blur?]}]
  (let [theme (quo.theme/use-theme)]
    [rn/view {:style (style/loading-container size blur? theme)}]))

(defn- left-subtitle
  [{:keys [size subtitle-type icon icon-color blur? subtitle customization-color emoji network-image]
    :or   {subtitle-type :default}}]
  (let [theme (quo.theme/use-theme)]
    [rn/view {:style style/subtitle-container}
     (when (and subtitle-type (not= :small size))
       [rn/view {:style (style/subtitle-icon-container subtitle-type)}
        (case subtitle-type
          :icon    [icons/icon icon
                    {:accessibility-label :subtitle-type-icon
                     :size                16
                     :color               icon-color}]
          :account [account-avatar/view
                    {:customization-color customization-color
                     :size                16
                     :emoji               emoji
                     :type                :default}]
          :network [rn/image
                    {:accessibility-label :subtitle-type-image
                     :source              network-image
                     :style               style/image}]
          nil)])
     [text/text
      {:weight :medium
       :size   :paragraph-2
       :style  (style/description blur? theme)}
      subtitle]]))

(defn- left-title
  [{:keys [title label size blur?]}]
  (let [theme (quo.theme/use-theme)]
    [rn/view {:style style/title-container}
     [text/text
      {:weight :regular
       :size   :paragraph-2
       :style  (style/title blur? theme)}
      title]
     (when (and (= :graph label) (not= :small size))
       [text/text
        {:weight :regular
         :size   :label
         :style  (style/title blur? theme)}
        (i18n/label :t/days)])]))

(defn- left-side
  "The description can either be given as a string `subtitle-type` or a component `custom-subtitle`"
  [{:keys [title status size blur? custom-subtitle icon subtitle subtitle-type label icon-color
           customization-color network-image emoji]
    :as   props}]
  (let [theme (quo.theme/use-theme)]
    [rn/view {:style style/left-side}
     [left-title
      {:title title
       :label label
       :size  size
       :blur? blur?
       :theme theme}]
     (if (= status :loading)
       [left-loading
        {:size  size
         :blur? blur?
         :theme theme}]
       (if custom-subtitle
         [custom-subtitle props]
         [left-subtitle
          {:theme               theme
           :size                size
           :subtitle-type       subtitle-type
           :icon                icon
           :icon-color          icon-color
           :blur?               blur?
           :subtitle            subtitle
           :customization-color customization-color
           :emoji               emoji
           :network-image       network-image}]))]))

(defn- right-side
  [{:keys [label icon-right? right-icon icon-color communities-list]}]
  [rn/view {:style style/right-container}
   (case label
     :preview [preview-list/view
               {:type   :communities
                :number 3
                :size   :size-24}
               communities-list]
     :graph   [text/text "graph"]
     :none    nil
     nil)
   (when icon-right?
     [rn/view {:style (style/right-icon label)}
      [icons/icon right-icon
       {:accessibility-label :icon-right
        :color               icon-color
        :size                20}]])])

(defn view
  [{:keys [blur? card? icon-right? right-icon label status size on-press communities-list
           container-style]
    :as   props}]
  (let [theme      (quo.theme/use-theme)
        icon-color (if (or blur? (= :dark theme))
                     colors/neutral-40
                     colors/neutral-50)]
    (if (= :graph label)
      [not-implemented/view {:blur? blur?}]
      [rn/pressable
       {:accessibility-label :data-item
        :disabled            (not icon-right?)
        :on-press            on-press
        :style               (merge (style/container size card? blur? theme) container-style)}
       [left-side props]
       (when (and (= :default status) (not= :small size))
         [right-side
          {:label            label
           :icon-right?      icon-right?
           :right-icon       right-icon
           :icon-color       icon-color
           :communities-list communities-list}])])))
