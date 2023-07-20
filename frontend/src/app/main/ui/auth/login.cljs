;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.login
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.errors :as err]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.button-link :as bl]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(def show-alt-login-buttons?
  (some (partial contains? cf/flags)
        [:login-with-google
         :login-with-github
         :login-with-gitlab
         :login-with-oidc]))

(defn- login-with-oidc
  [event provider params]
  (dom/prevent-default event)
  (->> (rp/cmd! :login-with-oidc (assoc params :provider provider))
       (rx/subs (fn [{:keys [redirect-uri] :as rsp}]
                  (if redirect-uri
                    (.replace js/location redirect-uri)
                    (log/error :hint "unexpected response from OIDC method"
                               :resp (pr-str rsp))))
                (fn [{:keys [type code] :as error}]
                  (cond
                    (and (= type :restriction)
                         (= code :provider-not-configured))
                    (st/emit! (dm/error (tr "errors.auth-provider-not-configured")))

                    :else
                    (st/emit! (dm/error (tr "errors.generic"))))))))

(defn- login-with-ldap
  [event params]
  (dom/prevent-default event)
  (dom/stop-propagation event)
  (let [{:keys [on-error]} (meta params)]
    (->> (rp/cmd! :login-with-ldap params)
         (rx/subs (fn [profile]
                    (if-let [token (:invitation-token profile)]
                      (st/emit! (rt/nav :auth-verify-token {} {:token token}))
                      (st/emit! (du/login-from-token {:profile profile}))))
                  (fn [{:keys [type code] :as error}]
                    (cond
                      (and (= type :restriction)
                           (= code :ldap-not-initialized))
                      (st/emit! (dm/error (tr "errors.ldap-disabled")))

                      (fn? on-error)
                      (on-error error)

                      :else
                      (st/emit! (dm/error (tr "errors.generic")))))))))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)
(s/def ::invitation-token ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email]
          :opt-un [::password ::invitation-token]))

(defn handle-error-messages
  [errors _data]
  (d/update-when errors :email
                 (fn [{:keys [code] :as error}]
                   (cond-> error
                     (= code ::us/email)
                     (assoc :message (tr "errors.email-invalid"))))))

(mf/defc login-form
  [{:keys [params on-success] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))

        totp*   (mf/use-state false)
        totp?   (deref totp*)

        error   (mf/use-state false)
        form    (fm/use-form :spec ::login-form
                             :validators [handle-error-messages]
                             :initial initial)

        on-error
        (mf/use-fn
         (fn [form cause]
           (when (map? cause)
             (err/print-trace! cause)
             (err/print-data! cause)
             (err/print-explain! cause))

           (cond

             (and (= :totp (:code cause))
                  (= :negotiation (:type cause)))
             (reset! totp* true)

             (and (= :invalid-totp (:code cause))
                  (= :negotiation (:type cause)))
             (do
               ;; (reset! error (tr "errors.invalid-totp"))
               (swap! form (fn [form]
                             (-> form
                                 (update :errors assoc :totp {:message (tr "errors.invalid-totp")})
                                 (update :touched assoc :totp true)))))


             (and (= :restriction (:type cause))
                  (= :passkey-disabled (:code cause)))
             (reset! error (tr "errors.wrong-credentials"))

             (and (= :restriction (:type cause))
                  (= :profile-blocked (:code cause)))
             (reset! error (tr "errors.profile-blocked"))

             (and (= :restriction (:type cause))
                  (= :admin-only-profile (:code cause)))
             (reset! error (tr "errors.profile-blocked"))

             (and (= :validation (:type cause))
                  (= :wrong-credentials (:code cause)))
             (reset! error (tr "errors.wrong-credentials"))

             (and (= :validation (:type cause))
                  (= :account-without-password (:code cause)))
             (reset! error (tr "errors.wrong-credentials"))

             :else
             (reset! error (tr "errors.generic")))))

        on-success-default
        (mf/use-fn
         (fn [data]
           (when-let [token (:invitation-token data)]
             (st/emit! (rt/nav :auth-verify-token {} {:token token})))))

        on-success
        (mf/use-fn
         (mf/deps on-success)
         (fn [data]
           (if (fn? on-success)
             (on-success data)
             (on-success-default data))))

        on-submit
        (mf/use-fn
         (fn [form event]
           (let [event      (dom/event->native-event event)
                 submitter  (unchecked-get event "submitter")
                 submitter  (dom/get-data submitter "role")
                 on-error   (partial on-error form)
                 on-success (partial on-success form)]

             (case submitter
               "login-with-passkey"
               (let [params (with-meta (:clean-data @form)
                              {:on-error on-error
                               :on-success on-success})]
                 (st/emit! (du/login-with-passkey params)))

               "login-with-password"
               (let [params (with-meta (:clean-data @form)
                              {:on-error on-error
                               :on-success on-success})]
                 (st/emit! (du/login-with-password params)))

               nil))))

        on-submit-ldap
        (mf/use-callback
         (mf/deps form)
         (fn [event]
           (reset! error nil)
           (let [params (:clean-data @form)]
             (login-with-ldap event (with-meta params
                                      {:on-error on-error
                                       :on-success on-success})))))]
    [:*
     (when-let [message @error]
       [:& msgs/inline-banner
        {:type :warning
         :content message
         :on-close #(reset! error nil)
         :data-test "login-banner"
         :role "alert"}])

     [:& fm/form {:on-submit on-submit :form form}
      [:div.fields-row
       [:& fm/input
        {:name :email
         :type "email"
         :help-icon i/at
         :label (tr "auth.email")}]]

      [:div.fields-row
       [:& fm/input
        {:type "password"
         :name :password
         :help-icon i/eye
         :label (tr "auth.password")}]]

      (when totp?
        [:div.fields-row
         [:& fm/input
          {:type "text"
           :name :totp
           :label (tr "auth.totp")}]])

      [:div.buttons-stack
       (when (or (contains? cf/flags :login)
                 (contains? cf/flags :login-with-password))
         [:> fm/submit-button*
          {:label (tr "auth.login-submit")
           :data-role "login-with-password"
           :data-test "login-submit"}])

       (when (contains? cf/flags :login-with-ldap)
         [:> fm/submit-button*
          {:label (tr "auth.login-with-ldap-submit")
           :data-role "login-with-ldap"
           :on-click on-submit-ldap}])]

      [:section.passkey
       [:> fm/submit-button*
        {:data-role "login-with-passkey"
         :class "btn-passkey-auth"}
        [:img {:src "/images/passkey.png"}]]]


      ]]))

(mf/defc login-buttons
  [{:keys [params] :as props}]
  (let [login-with-google (mf/use-fn (mf/deps params) #(login-with-oidc % :google params))
        login-with-github (mf/use-fn (mf/deps params) #(login-with-oidc % :github params))
        login-with-gitlab (mf/use-fn (mf/deps params) #(login-with-oidc % :gitlab params))
        login-with-oidc   (mf/use-fn (mf/deps params) #(login-with-oidc % :oidc params))]

    [:div.auth-buttons
     (when (contains? cf/flags :login-with-google)
       [:& bl/button-link {:on-click login-with-google
                           :icon i/brand-google
                           :label (tr "auth.login-with-google-submit")
                           :class "btn-google-auth"}])

     (when (contains? cf/flags :login-with-github)
       [:& bl/button-link {:on-click login-with-github
                           :icon i/brand-github
                           :label (tr "auth.login-with-github-submit")
                           :class "btn-github-auth"}])

     (when (contains? cf/flags :login-with-gitlab)
       [:& bl/button-link {:on-click login-with-gitlab
                           :icon i/brand-gitlab
                           :label (tr "auth.login-with-gitlab-submit")
                           :class "btn-gitlab-auth"}])

     (when (contains? cf/flags :login-with-oidc)
       [:& bl/button-link {:on-click login-with-oidc
                           :icon i/brand-openid
                           :label (tr "auth.login-with-oidc-submit")
                           :class "btn-github-auth"}])]))

(mf/defc login-button-oidc
  [{:keys [params] :as props}]
  (when (contains? cf/flags :login-with-oidc)
    [:div.link-entry.link-oidc
     [:a {:tab-index "0"
          :on-key-down (fn [event]
                        (when (k/enter? event)
                          (login-with-oidc event :oidc params)))
          :on-click #(login-with-oidc % :oidc params)}
      (tr "auth.login-with-oidc-submit")]]))

(mf/defc login-methods
  [{:keys [params on-success] :as props}]
  [:*
   (when show-alt-login-buttons?
     [:*
      [:span.separator
       [:span.line]
       [:span.text (tr "labels.continue-with")]
       [:span.line]]

      [:& login-buttons {:params params}]

      (when (or (contains? cf/flags :login)
                (contains? cf/flags :login-with-password)
                (contains? cf/flags :login-with-ldap))
        [:span.separator
         [:span.line]
         [:span.text (tr "labels.or")]
         [:span.line]])])

   (when (or (contains? cf/flags :login)
             (contains? cf/flags :login-with-password)
             (contains? cf/flags :login-with-ldap))
     [:& login-form {:params params :on-success on-success}])])

(mf/defc login-page
  {::mf/wrap-props false}
  [{:keys [params]}]
  (let [nav-to-recovery (mf/use-fn #(st/emit! (rt/nav :auth-recovery-request)))
        nav-to-register (mf/use-fn (mf/deps params) #(st/emit! (rt/nav :auth-register {} params)))
        create-demo     (mf/use-fn #(st/emit! (du/create-demo-profile)))]

    [:div.generic-form.login-form
     [:div.form-container
      [:h1 {:data-test "login-title"} (tr "auth.login-title")]

      [:& login-methods {:params params}]

      [:div.links
       (when (or (contains? cf/flags :login)
                 (contains? cf/flags :login-with-password))
         [:div.link-entry
          [:& lk/link {:on-click nav-to-recovery
                       :data-test "forgot-password"}
           (tr "auth.forgot-password")]])

       (when (contains? cf/flags :registration)
         [:div.link-entry
          [:span (tr "auth.register") " "]
          [:& lk/link {:on-click nav-to-register
                       :data-test "register-submit"}
           (tr "auth.register-submit")]])]

      (when (contains? cf/flags :demo-users)
        [:div.links.demo
         [:div.link-entry
          [:span (tr "auth.create-demo-profile") " "]
          [:& lk/link {:on-click create-demo
                       :data-test "demo-account-link"}
           (tr "auth.create-demo-account")]]])]]))
