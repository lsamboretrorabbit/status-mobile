(ns status-im.contexts.communities.events
  (:require
    [clojure.string :as string]
    [legacy.status-im.data-store.communities :as data-store.communities]
    [legacy.status-im.mailserver.core :as mailserver]
    [schema.core :as schema]
    [status-im.constants :as constants]
    [status-im.contexts.chat.messenger.messages.link-preview.events :as link-preview.events]
    status-im.contexts.communities.actions.accounts-selection.events
    status-im.contexts.communities.actions.addresses-for-permissions.events
    status-im.contexts.communities.actions.airdrop-addresses.events
    status-im.contexts.communities.actions.community-options.events
    status-im.contexts.communities.actions.leave.events
    [status-im.contexts.communities.utils :as utils]
    [status-im.navigation.events :as navigation]
    [taoensso.timbre :as log]
    [utils.re-frame :as rf]))

(defn handle-community
  [{:keys [db]} [community-js]]
  (when community-js
    (let [{:keys [clock
                  token-permissions-check joined id last-opened-at]
           :as   community}       (data-store.communities/<-rpc community-js)
          previous-last-opened-at (get-in db [:communities id :last-opened-at])]
      (when (> clock (get-in db [:communities id :clock]))
        {:db (assoc-in db
              [:communities id]
              (assoc community :last-opened-at (max last-opened-at previous-last-opened-at)))
         :fx [[:dispatch
               [:communities/check-permissions-to-join-community-with-all-addresses id]]
              (when (not joined)
                [:dispatch [:chat.ui/spectate-community id]])
              (when (nil? token-permissions-check)
                [:dispatch [:communities/check-permissions-to-join-community id]])]}))))

(rf/reg-event-fx :communities/handle-community handle-community)

(schema/=> handle-community
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema [:catn [:community-js map?]]]]]
   [:maybe
    [:map
     [:db [:map [:communities map?]]]
     [:fx vector?]]]])

(rf/defn handle-removed-chats
  [{:keys [db]} chat-ids]
  {:db (reduce (fn [db chat-id]
                 (update db :chats dissoc chat-id))
               db
               chat-ids)})

(defn- handle-my-request
  [db {:keys [community-id state deleted] :as request}]
  (cond (and (= constants/community-request-to-join-state-pending state) (not deleted))
        (assoc-in db [:communities/my-pending-requests-to-join community-id] request)
        (and (= constants/community-request-to-join-state-accepted state) (not deleted))
        (update-in db [:communities/my-pending-requests-to-join] dissoc community-id)
        :else (update-in db [:communities/my-pending-requests-to-join] dissoc community-id)))

(defn handle-admin-request
  [db {:keys [id community-id deleted] :as request}]
  (if deleted
    (update-in db [:communities/requests-to-join community-id] dissoc id)
    (assoc-in db [:communities/requests-to-join community-id id] request)))

(rf/defn handle-requests-to-join
  [{:keys [db]} requests]
  (let [my-public-key (get-in db [:profile/profile :public-key])]
    {:db (reduce (fn [db {:keys [public-key] :as request}]
                   (let [my-request? (= my-public-key public-key)]
                     (if my-request?
                       (handle-my-request db request)
                       (handle-admin-request db request))))
                 db
                 requests)}))

(rf/defn handle-communities
  {:events [:community/fetch-success]}
  [{:keys [db]} communities]
  {:fx
   (->> communities
        (map #(vector :dispatch [:communities/handle-community %])))})

(rf/reg-event-fx :communities/request-to-join-result
 (fn [{:keys [db]} [community-id request-id response-js]]
   {:db         (update-in db [:communities/requests-to-join community-id] dissoc request-id)
    :dispatch-n [[:sanitize-messages-and-process-response response-js]
                 [:activity-center.notifications/mark-as-read request-id]]}))

(rf/reg-event-fx :communities/decline-request-to-join-pressed
 (fn [_ [community-id request-id]]
   {:json-rpc/call
    [{:method      "wakuext_declineRequestToJoinCommunity"
      :params      [{:id request-id}]
      :js-response true
      :on-success  #(rf/dispatch [:communities/request-to-join-result community-id request-id %])
      :on-error    #(log/error "failed to decline " community-id request-id)}]}))

(rf/reg-event-fx :communities/accept-request-to-join-pressed
 (fn [_ [community-id request-id]]
   {:json-rpc/call
    [{:method      "wakuext_acceptRequestToJoinCommunity"
      :params      [{:id request-id}]
      :js-response true
      :on-success  #(rf/dispatch [:communities/request-to-join-result community-id request-id %])
      :on-error    #(log/error "failed to accept requests-to-join" community-id request-id %)}]}))

(rf/reg-event-fx :communities/get-user-requests-to-join-success
 (fn [{:keys [db]} [requests]]
   {:db (assoc db
               :communities/my-pending-requests-to-join
               (data-store.communities/<-requests-to-join-community-rpc requests :communityId))}))

(rf/reg-event-fx :communities/get-user-requests-to-join
 (fn [_]
   {:json-rpc/call [{:method     "wakuext_myPendingRequestsToJoin"
                     :params     []
                     :on-success #(rf/dispatch [:communities/get-user-requests-to-join-success %])
                     :on-error   #(log/error "failed to get requests to join community")}]}))

(rf/reg-event-fx :communities/fetched-collapsed-categories-success
 (fn [{:keys [db]} [categories]]
   {:db (assoc db
               :communities/collapsed-categories
               (reduce
                (fn [acc {:keys [communityId categoryId]}]
                  (assoc-in acc [communityId categoryId] true))
                {}
                categories))}))

(rf/reg-event-fx :community/fetch
 (fn [_]
   {:json-rpc/call [{:method     "wakuext_communities"
                     :params     []
                     :on-success #(rf/dispatch [:community/fetch-success %])
                     :on-error   #(log/error "failed to fetch communities" %)}
                    {:method      "wakuext_checkAndDeletePendingRequestToJoinCommunity"
                     :params      []
                     :js-response true
                     :on-success  #(rf/dispatch [:sanitize-messages-and-process-response %])
                     :on-error    #(log/info "failed to fetch communities" %)}
                    {:method     "wakuext_collapsedCommunityCategories"
                     :params     []
                     :on-success #(rf/dispatch [:communities/fetched-collapsed-categories-success %])
                     :on-error   #(log/error "failed to fetch collapsed community categories" %)}]}))

(defn update-previous-permission-addresses
  [{:keys [db]} [community-id]]
  (when community-id
    (let [accounts                      (utils/sorted-non-watch-only-accounts db)
          selected-permission-addresses (get-in db
                                                [:communities community-id
                                                 :selected-permission-addresses])
          selected-accounts             (filter #(contains? selected-permission-addresses (:address %))
                                                accounts)
          current-airdrop-address       (get-in db [:communities community-id :airdrop-address])
          share-all-addresses?          (get-in db [:communities community-id :share-all-addresses?])]
      {:db (update-in db
                      [:communities community-id]
                      assoc
                      :previous-share-all-addresses? share-all-addresses?
                      :previous-permission-addresses selected-permission-addresses
                      :airdrop-address               (if (contains? selected-permission-addresses
                                                                    current-airdrop-address)
                                                       current-airdrop-address
                                                       (:address (first selected-accounts))))})))

(rf/reg-event-fx :communities/update-previous-permission-addresses
 update-previous-permission-addresses)

(defn toggle-selected-permission-address
  [{:keys [db]} [address community-id]]
  (let [selected-permission-addresses
        (get-in db [:communities community-id :selected-permission-addresses])
        updated-selected-permission-addresses
        (if (contains? selected-permission-addresses address)
          (disj selected-permission-addresses address)
          (conj selected-permission-addresses address))]
    {:db (assoc-in db
          [:communities community-id :selected-permission-addresses]
          updated-selected-permission-addresses)
     :fx [(when community-id
            [:dispatch
             [:communities/check-permissions-to-join-community community-id
              updated-selected-permission-addresses :based-on-client-selection]])]}))

(rf/reg-event-fx :communities/toggle-selected-permission-address
 toggle-selected-permission-address)

(defn toggle-share-all-addresses
  [{:keys [db]} [community-id]]
  (let [share-all-addresses?      (get-in db [:communities community-id :share-all-addresses?])
        next-share-all-addresses? (not share-all-addresses?)
        accounts                  (utils/sorted-non-watch-only-accounts db)
        addresses                 (set (map :address accounts))]
    {:db (update-in db
                    [:communities community-id]
                    assoc
                    :share-all-addresses?          next-share-all-addresses?
                    :selected-permission-addresses addresses)
     :fx [(when (and community-id next-share-all-addresses?)
            [:dispatch
             [:communities/check-permissions-to-join-community community-id
              addresses :based-on-client-selection]])]}))

(rf/reg-event-fx :communities/toggle-share-all-addresses
 toggle-share-all-addresses)

(rf/reg-event-fx :communities/reset-selected-permission-addresses
 (fn [{:keys [db]} [community-id]]
   (when community-id
     {:db (update-in db
                     [:communities community-id]
                     assoc
                     :selected-permission-addresses
                     (get-in db [:communities community-id :previous-permission-addresses])
                     :share-all-addresses?
                     (get-in db [:communities community-id :previous-share-all-addresses?]))
      :fx [[:dispatch [:communities/check-permissions-to-join-community community-id]]]})))

(rf/reg-event-fx :communities/set-airdrop-address
 (fn [{:keys [db]} [address community-id]]
   {:db (assoc-in db [:communities community-id :airdrop-address] address)}))

(defn community-fetched
  [{:keys [db]} [community-id community]]
  (when community
    {:db (update db :communities/fetching-communities dissoc community-id)
     :fx [[:dispatch [:communities/handle-community community]]
          [:dispatch [:communities/update-last-opened-at community-id]]
          [:dispatch
           [:chat.ui/cache-link-preview-data (link-preview.events/community-link community-id)
            community]]]}))

(rf/reg-event-fx :chat.ui/community-fetched community-fetched)

(defn community-failed-to-fetch
  [{:keys [db]} [community-id]]
  {:db (update db :communities/fetching-communities dissoc community-id)})

(rf/reg-event-fx :chat.ui/community-failed-to-fetch community-failed-to-fetch)

(defn fetch-community
  [{:keys [db]} [{:keys [community-id update-last-opened-at?]}]]
  (when (and community-id (not (get-in db [:communities/fetching-communities community-id])))
    {:db            (assoc-in db [:communities/fetching-communities community-id] true)
     :json-rpc/call [{:method     "wakuext_fetchCommunity"
                      :params     [{:CommunityKey    community-id
                                    :TryDatabase     true
                                    :WaitForResponse true}]
                      :on-success (fn [community]
                                    (when update-last-opened-at?
                                      (rf/dispatch [:communities/update-last-opened-at community-id]))
                                    (rf/dispatch [:chat.ui/community-fetched community-id community]))
                      :on-error   (fn [err]
                                    (rf/dispatch [:chat.ui/community-failed-to-fetch community-id])
                                    (log/error {:message
                                                "Failed to request community info from mailserver"
                                                :error err}))}]}))

(schema/=> fetch-community
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema
      [:vector
       [:map {:closed true}
        [:community-id {:optional true} :string]
        [:update-last-opened-at? {:optional true} [:maybe :boolean]]]]]]]
   [:maybe
    [:map
     [:db map?]
     [:json-rpc/call :schema.common/rpc-call]]]])

(rf/reg-event-fx :communities/fetch-community fetch-community)

(defn spectate-community-success
  [{:keys [db]} [{:keys [communities]}]]
  (when-let [community (first communities)]
    {:db (-> db
             (assoc-in [:communities (:id community) :spectated] true)
             (assoc-in [:communities (:id community) :spectating] false))
     :fx [[:dispatch [:communities/handle-community community]]
          [:dispatch [::mailserver/request-messages]]]}))

(rf/reg-event-fx :chat.ui/spectate-community-success spectate-community-success)

(defn spectate-community-failed
  [{:keys [db]} [community-id]]
  {:db (assoc-in db [:communities community-id :spectating] false)})

(rf/reg-event-fx :chat.ui/spectate-community-failed spectate-community-failed)

(defn spectate-community
  [{:keys [db]} [community-id]]
  (let [{:keys [spectated spectating joined]} (get-in db [:communities community-id])]
    (when (and (not joined) (not spectated) (not spectating))
      {:db            (assoc-in db [:communities community-id :spectating] true)
       :json-rpc/call [{:method     "wakuext_spectateCommunity"
                        :params     [community-id]
                        :on-success [:chat.ui/spectate-community-success]
                        :on-error   (fn [err]
                                      (log/error {:message
                                                  "Failed to spectate community"
                                                  :error err})
                                      (rf/dispatch [:chat.ui/spectate-community-failed
                                                    community-id]))}]})))

(schema/=> spectate-community
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema [:catn [:community-id [:? :string]]]]]]
   [:maybe
    [:map
     [:db map?]
     [:json-rpc/call :schema.common/rpc-call]]]])

(rf/reg-event-fx :chat.ui/spectate-community spectate-community)

(defn navigate-to-serialized-community
  [community-id]
  {:serialization/deserialize-and-compress-key
   {:serialized-key community-id
    :on-success     #(rf/dispatch [:communities/navigate-to-community-overview %])
    :on-error       #(log/error {:message      "Failed to decompress community-id"
                                 :error        %
                                 :community-id community-id})}})

(rf/defn navigate-to-community-overview
  [{:keys [db] :as cofx} [community-id]]
  (let [current-view-id (:view-id db)]
    (if (string/starts-with? community-id constants/serialization-key)
      (navigate-to-serialized-community community-id)
      (rf/merge
       cofx
       {:fx [[:dispatch
              [:communities/fetch-community
               {:community-id           community-id
                :update-last-opened-at? true}]]
             [:dispatch [:navigate-to :community-overview community-id]]
             (when (get-in db [:communities community-id :joined])
               [:dispatch
                [:activity-center.notifications/dismiss-community-overview community-id]])]}
       (when-not (or (= current-view-id :shell) (= current-view-id :communities-stack))
         (navigation/pop-to-root :shell-stack))))))

(rf/reg-event-fx :communities/navigate-to-community-overview navigate-to-community-overview)

(defn navigate-to-community-chat
  [{:keys [db]} [chat-id pop-to-root? community-id]]
  (let [community-id (or community-id (get-in db [:chats chat-id :community-id]))]
    (merge
     {:fx [(when community-id
             [:dispatch
              [:communities/fetch-community
               {:community-id           community-id
                :update-last-opened-at? true}]])
           (if pop-to-root?
             [:dispatch [:chat/pop-to-root-and-navigate-to-chat chat-id]]
             [:dispatch [:chat/navigate-to-chat chat-id]])]}
     (when-not (get-in db [:chats chat-id :community-id])
       {:db (assoc-in db [:chats chat-id :community-id] community-id)}))))

(rf/reg-event-fx :communities/navigate-to-community-chat navigate-to-community-chat)

(defn get-revealed-accounts
  [{:keys [db]} [community-id on-success]]
  (let [{:keys [joined fetching-revealed-accounts]
         :as   community} (get-in db [:communities community-id])
        pending?          (get-in db [:communities/my-pending-requests-to-join community-id])]
    (when (and community (or pending? joined) (not fetching-revealed-accounts))
      {:db (assoc-in db [:communities community-id :fetching-revealed-accounts] true)
       :json-rpc/call
       [{:method      "wakuext_getRevealedAccounts"
         :params      [community-id (get-in db [:profile/profile :public-key])]
         :js-response true
         :on-success  [:communities/get-revealed-accounts-success community-id on-success]
         :on-error    (fn [err]
                        (log/error {:message      "failed to fetch revealed accounts"
                                    :community-id community-id
                                    :err          err})
                        (rf/dispatch [:communities/get-revealed-accounts-failed community-id]))}]})))

(rf/reg-event-fx :communities/get-revealed-accounts get-revealed-accounts)

(schema/=> get-revealed-accounts
  [:=>
   [:catn
    [:cofx :schema.re-frame/cofx]
    [:args
     [:schema
      [:catn
       [:community-id [:? :string]]
       [:on-success [:? :schema.re-frame/event]]]]]]
   [:maybe
    [:map
     [:db map?]
     [:json-rpc/call :schema.common/rpc-call]]]])

(rf/reg-event-fx :communities/get-revealed-accounts-success
 (fn [{:keys [db]} [community-id on-success revealed-accounts-js]]
   (when-let [community (get-in db [:communities community-id])]
     (let [revealed-accounts
           (reduce
            (fn [acc {:keys [address] :as revealed-account}]
              (assoc acc address revealed-account))
            {}
            (data-store.communities/<-revealed-accounts-rpc revealed-accounts-js))

           community-with-revealed-accounts
           (-> community
               (assoc :revealed-accounts revealed-accounts)
               (dissoc :fetching-revealed-accounts))]
       {:db (assoc-in db [:communities community-id] community-with-revealed-accounts)
        :fx [(when (vector? on-success)
               [:dispatch (conj on-success revealed-accounts)])]}))))

(rf/reg-event-fx :communities/get-revealed-accounts-failed
 (fn [{:keys [db]} [community-id]]
   (when (get-in db [:communities community-id])
     {:db (update-in db [:communities community-id] dissoc :fetching-revealed-accounts)})))

(rf/reg-event-fx :communities/update-last-opened-at
 (fn [_ [community-id]]
   {:json-rpc/call [{:method     "wakuext_communityUpdateLastOpenedAt"
                     :params     [community-id]
                     :on-success #(rf/dispatch [:communities/update-last-opened-at-success community-id
                                                %])
                     :on-error   #(log/error (str "failed to update last opened at for community "
                                                  %))}]}))

(rf/reg-event-fx :communities/update-last-opened-at-success
 (fn [{:keys [db]} [community-id last-opened-at]]
   {:db (assoc-in db [:communities community-id :last-opened-at] last-opened-at)}))
