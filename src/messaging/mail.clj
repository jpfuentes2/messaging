(ns messaging.mail
  (:require
   [aleph.http :as http]
   [cheshire.core :as json]
   [byte-streams :as bs]
   [taoensso.timbre :as log]
   [environ.core :refer [env]]
   [clojure.string :as string]
   [messaging.shared :as shared]
   [messaging.settings :as settings]
   [messaging.phoenix :as phoenix]
   [clojchimp.client :as mailchimp]
   [gws.mandrill.client :as client]
   [gws.mandrill.api.messages :as messages]
   [gws.mandrill.api.templates :as templates]
   [helpers.activities-transforms :as at]))

;; mandrill client
(defn client []
  (let [mkey (settings/get :mandrill_key)]
    (when (and (not= @shared/environment shared/staging) (empty? mkey))
      (throw (ex-info "Mandrill key is not defined" {})))
    (client/create mkey)))

;; mailchimp client
(defn mclient []
  (let [mkey (settings/get :mailchimp_key)]
    (when (and (not= @shared/environment shared/staging) (empty? mkey))
      (throw (ex-info "Mailchimp key is not defined" {})))
    (mailchimp/create-client "fox-messaging" mkey)))

(defn make-tpl-vars
  "Convert clojure map to mandtrill template vars"
  [vars]
  {:pre  [(map? vars)]}
  (for [[k v] vars]
    {:name k :content v}))

(defn gen-msg
  "Generate ready to send email message for Mandrill.
  Params:
  recepient(s)
  template vars
  Additional key-values for mandrill message which will be passed to Mandrill API as is
  "
  [[{customer-email :email customer-name :name :as rcpt} & _ :as recipients]
   vars
   {:keys [subject text html] :as opts}]
  {:pre [(every? not-empty [recipients customer-email])]}
  (let [additional-vars (some->
                         (settings/get :additional_merge_vars)
                         (json/parse-string true))
        base-vars {:shop_base_url (settings/get :shop_base_url)
                   :company_name (settings/get :retailer_name)
                   :email_subject subject
                   :update_profile_link (settings/get :update_customer_profile_link)
                   :customer_name customer-name}
        base-merge-vars (if additional-vars
                          (merge base-vars additional-vars)
                          base-vars)]
    (merge opts {:to recipients
                 :global_merge_vars (make-tpl-vars (merge base-merge-vars vars))
                 :merge_language "handlebars"
                 :auto_text true

                 :from_email (settings/get :from_email)
                 :subject subject})))

(defn send-template!
  [slug template]
  (messages/send-template (client)
                          {:template_name slug
                           :template_content []
                           :message template}))

(defn trim-slash [str]
  (string/replace str #"/$" ""))

(defn extract-password-link
  [activity email url-action is-admin]
  (let [reset-code (get-in activity [:data "code"])
        [base-url reset-pw-endpoint] (if is-admin
                                       [(trim-slash (settings/get :admin_base_url))
                                        (format "%s?token=%s&email=%s" url-action reset-code email)]
                                       [(trim-slash (settings/get :shop_base_url))
                                        (format (settings/get :reset_password_link_format) reset-code)])
        reset-password-link (format "%s/%s" base-url reset-pw-endpoint)]

    {:reset_password_link reset-password-link
     :reset_code reset-code}))

(defn dispatch-activity
  [activity]
  (keyword (:kind activity)))

(defmulti handle-activity dispatch-activity)
(defmethod handle-activity :default [act] nil)

(defmethod handle-activity :order_checkout_completed
  [activity]
  (let [data (:data activity)
        order (get data "order")
        email (get-in order ["customer" "email"])
        customer-name (get-in order ["customer" "name"] "")
        order-ref (get order "referenceNumber")
        rcpt {:email email :name customer-name}
        rcpts (if (settings/get :send_order_checkout_completed_bcc)
                [rcpt {:email (settings/get :from_email) :type "bcc"}]
                [rcpt])
        msg (gen-msg rcpts
                     {:items (let [skus (get-in order ["lineItems" "skus"])]
                               (map at/sku->item skus))
                      :totals (at/format-prices (get order "totals"))
                      :placed_at (at/date-simple-format (get order "placedAt"))
                      :shipping_method (get-in order ["shippingMethod" "name"])
                      :shipping_address (get order "shippingAddress")
                      :billing_address (get order "billingAddress")
                      :billing_info (get order "billingCreditCardInfo")
                      :order_ref order-ref}

                     {:subject (settings/get :order_checkout_subject)})]

    (send-template! (settings/get :order_confirmation_template) msg)))

(defmethod handle-activity :shipment_shipped
  [activity]
  (let [data (:data activity)
        order-ref (get data "orderRefNum")
        order (get (phoenix/get-order-info order-ref) "result")
        email (get-in order ["customer" "email"])
        customer-name (get-in order ["customer" "name"] "")
        tracking-number (get data "trackingNumber")
        tracking-template (get-in data ["shippingMethod" "carrier" "trackingTemplate"])
        msg (gen-msg [{:email email :name customer-name}]
                     {:items (let [skus (get-in order ["lineItems" "skus"])]
                               (map at/sku->item skus))
                      :totals (at/format-prices (get order "totals"))
                      :placed_at (at/date-simple-format (get order "placedAt"))
                      :shipping_method (get-in order ["shippingMethod" "name"])
                      :shipping_address (get order "shippingAddress")
                      :billing_address (get order "billingAddress")
                      :billing_info (get order "billingCreditCardInfo")
                      :tracking_number tracking-number
                      :tracking_url (str tracking-template tracking-number)
                      :estimated_arrival (get data "estimatedArrival")
                      :order_ref order-ref}

                     {:subject (settings/get :order_shipped_subject)})]
    (send-template! (settings/get :order_shipped_template) msg)))

(defmethod handle-activity :order_state_changed
  [activity]
  (let [data (:data activity)
        order (get data "order")
        email (get-in order ["customer" "email"])
        customer-name (get-in order ["customer" "name"] "")
        order-ref (get-in order ["referenceNumber"])
        new-state (get-in order ["orderState"])
        msg (gen-msg [{:email email :name customer-name}]
                     {:items (let [skus (get-in order ["lineItems" "skus"])]
                               (map at/sku->item skus))
                      :totals (at/format-prices (get order "totals"))
                      :placed_at (at/date-simple-format (get order "placedAt"))
                      :shipping_method (get-in order ["shippingMethod" "name"])
                      :shipping_address (get order "shippingAddress")
                      :billing_address (get order "billingAddress")
                      :billing_info (get order "billingCreditCardInfo")
                      :order_ref order-ref}

                     {:subject (settings/get :order_canceled_subject)})]
    (when (= "canceled" new-state)
      (send-template! (settings/get :order_canceled_template) msg))))

(defmethod handle-activity :user_remind_password
  [activity]
  (let [data (:data activity)
        email (get-in data ["user" "email"])
        isAdmin (get-in data ["isAdmin"])
        reset-pw (extract-password-link activity email "reset-password" isAdmin)
        customer-name (get-in activity [:data "user" "name"])]
    (send-template! (settings/get :customer_remind_password_template)
                    (gen-msg [{:email email :name customer-name}]
                             reset-pw
                             {:subject (settings/get :customer_remind_password_subject)}))))

(defmethod handle-activity :gift_card_created
  [activity]
  (let [data (:data activity)
        message (get-in data ["giftCard" "message"])
        giftCard (get data "giftCard")
        recipientEmail (get-in data ["giftCard" "recipientEmail"])
        recipientName (get-in data ["giftCard" "recipientName"] "")
        senderName (get-in data ["giftCard" "senderName"] "")
        giftCardCode (get-in data ["giftCard" "code"])]

    (when (every? seq [recipientEmail giftCardCode])
      (send-template! (settings/get :gift_card_customer_template)
                      (gen-msg [{:email recipientEmail :name recipientName}]
                               {:balance (at/format-price-int (get giftCard "availableBalance"))
                                :message message
                                :sender_name senderName
                                :recipient_name recipientName
                                :gift_card_number giftCardCode}
                               {:subject (settings/get :gift_card_customer_subject)})))))

(defmethod handle-activity :send_simple_mail
  [activity]
  (let [email (get-in activity [:data "email"])
        customer-name (get-in activity [:data "name"] "")
        msg (gen-msg [{:email email :name customer-name}]
                     {}
                     (merge {:text (get-in activity [:data "text"])
                             :html (get-in activity [:data "html"])
                             :subject (get-in activity [:data "subject"])}

                            (get-in activity [:data "opts"])))]
    (messages/send (client) {:message msg})))

(defn handle-new-customer
  [activity]
  (let [email (get-in activity [:data "user" "email"])
        customer-name (get-in activity [:data "user" "name"] "")
        customer-id (get-in activity [:data "user" "id"])]
    (when (settings/add-new-customers-to-mailchimp?)
      (try
        (mailchimp/create-member-for-list
         (mclient)
         (settings/get :mailchimp_customers_list_id)
         {:email_type "html"
          :email_address email
          :merge_fields {"NAME" customer-name}
          :status "subscribed"})
        (catch Exception e (prn "Can't add user to list" e)))) (send-template! (settings/get :customer_created_template)
                                                                               (gen-msg [{:email email :name customer-name}]
                                                                                        {}
                                                                                        {:subject (settings/get :customer_registration_subject)}))))

(defmethod handle-activity :customer_registered
  [activity]
  (handle-new-customer activity))

(defmethod handle-activity :customer_created
  [activity]
  (handle-new-customer activity))

(defmethod handle-activity :store_admin_created
  [activity]
  (let [data (:data activity)
        email (get-in data ["storeAdmin" "email"])
        new-admin-name (get-in data ["storeAdmin" "name"])
        store-admin-name (get-in data ["admin" "name"])
        reset-pw (extract-password-link activity email "signup" true)
        msg (gen-msg [{:email email :name new-admin-name}]
                     (merge
                      {:user_being_invited new-admin-name
                       :name_of_retailer (settings/get :retailer_name)
                       :user_that_invited_you store-admin-name}
                      reset-pw)

                     {:subject (settings/get :admin_invitation_subject)})]

    (send-template! (settings/get :admin_invitation_template) msg)))
