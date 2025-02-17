(ns quo.components.tags.context-tag.view
  (:require
    [quo.components.avatars.account-avatar.view :as account-avatar]
    [quo.components.avatars.group-avatar.view :as group-avatar]
    [quo.components.avatars.user-avatar.view :as user-avatar]
    [quo.components.icon :as icons]
    [quo.components.list-items.preview-list.view :as preview-list]
    [quo.components.markdown.text :as text]
    [quo.components.tags.context-tag.schema :as component-schema]
    [quo.components.tags.context-tag.style :as style]
    [quo.components.utilities.token.view :as token]
    [quo.foundations.colors :as colors]
    [quo.theme :as quo.theme]
    [react-native.core :as rn]
    [react-native.fast-image :as fast-image]
    [schema.core :as schema]))

(defn- tag-skeleton
  [{:keys [theme size text] :or {size 24}} logo-component]
  [rn/view {:style (style/tag-container size)}
   logo-component
   [rn/view {:style (style/tag-spacing size)}
    [text/text
     {:style  (style/text theme)
      :weight :medium
      :size   (if (= size 24) :paragraph-2 :paragraph-1)}
     text]]])

(defn- communities-tag
  [{:keys [theme size community-logo community-name blur? channel? channel-name]}]
  (let [text-size (if (= size 24) :paragraph-2 :paragraph-1)
        icon-size (if (= size 24) 16 20)]
    [rn/view {:style (style/tag-container size)}
     [fast-image/fast-image {:style (style/circle-logo size) :source community-logo}]
     [rn/view {:style (style/tag-spacing size)}
      [text/text
       {:style  (style/text theme)
        :weight :medium
        :size   text-size}
       community-name]]
     (when channel?
       [:<>
        [icons/icon :i/chevron-right
         {:color (style/context-tag-icon-color theme blur?)
          :size  icon-size}]
        [text/text
         {:style  (style/text theme)
          :weight :medium
          :size   text-size}
         (str "# " channel-name)]])]))

(defn- trim-public-key
  [pk]
  (str (subs pk 0 5) "..." (subs pk (- (count pk) 3))))

(defn- address-tag
  [{:keys [theme size address]}]
  [rn/view {:style (style/address size)}
   [text/text
    {:style  (style/text theme)
     :weight :monospace ;; TODO: fix this style (issue #17009)
     :size   (if (= size 24) :paragraph-2 :paragraph-1)}
    (trim-public-key address)]])

(defn- icon-tag
  [{:keys [theme size icon blur? context]}]
  [rn/view {:style (style/icon size)}
   [icons/icon icon
    {:color (style/context-tag-icon-color theme blur?)
     :size  (if (= size 24) 12 20)}]
   [rn/view {:style (style/icon-spacing size)}
    [text/text
     {:style  (style/text theme)
      :weight :medium
      :size   (if (= size 24) :paragraph-2 :paragraph-1)}
     context]]])

(defn- view-internal
  [{:keys [type size state blur? customization-color profile-picture full-name users
           group-name amount token network-logo network-name networks
           account-name emoji collectible collectible-name collectible-number duration container-style]
    :or   {customization-color :blue
           type                :default
           state               :default}
    :as   props}]
  (let [theme (quo.theme/use-theme)]
    [rn/view {:style (merge {:align-items :flex-start} container-style)}
     [rn/view
      {:style               (style/container {:theme               theme
                                              :type                type
                                              :size                size
                                              :state               state
                                              :blur?               blur?
                                              :customization-color customization-color})
       :accessibility-label :context-tag}
      (case type
        :default
        [tag-skeleton {:theme theme :size size :text full-name}
         [user-avatar/user-avatar
          {:full-name           full-name
           :profile-picture     profile-picture
           :size                (if (= size 24) :xxs 28)
           :status-indicator?   false
           :ring?               false
           :customization-color customization-color}]]

        :multiuser
        [preview-list/view {:type :user :size :size-20}
         users]

        :multinetwork
        [preview-list/view {:type :network :size :size-20}
         networks]

        :audio
        [tag-skeleton {:theme theme :text (str duration)}
         [rn/view {:style (style/audio-tag-icon-container customization-color theme)}
          [icons/icon :i/play {:color style/audio-tag-icon-color :size 12}]]]

        :group
        [tag-skeleton {:theme theme :size size :text group-name}
         [group-avatar/view
          {:icon-name           :i/members
           :size                (if (= size 24) :size-20 :size-28)
           :customization-color (colors/custom-color customization-color 50)}]]

        (:channel :community)
        [communities-tag (assoc props :channel? (= type :channel))]

        :token
        [tag-skeleton {:theme theme :size size :text (str amount " " token)}
         [token/view
          {:style (style/token-logo size)
           :token token
           :size  (if (= size 24) :size-20 :size-28)}]]

        :network
        [tag-skeleton {:theme theme :size size :text network-name}
         [rn/image {:style (style/circle-logo size) :source network-logo}]]

        :collectible
        [tag-skeleton
         {:theme theme
          :size  size
          :text  (str collectible-name " #" collectible-number)}
         [rn/image {:style (style/rounded-logo size) :source collectible}]]

        :account
        [tag-skeleton {:theme theme :size size :text account-name}
         [account-avatar/view
          {:customization-color customization-color
           :emoji               emoji
           :size                (if (= size 24) 20 28)}]]

        :address
        [address-tag props]

        :icon
        [icon-tag props]

        nil)]]))

(def view (schema/instrument #'view-internal component-schema/?schema))
