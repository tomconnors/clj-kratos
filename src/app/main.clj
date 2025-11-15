(ns app.main
  (:require
   [charred.api :as json]
   [clojure.pprint :as pp]
   [dev.onionpancakes.chassis.core :as chassis]
   [mount.core :as mount]
   [org.httpkit.client :as http.client]
   [org.httpkit.server :as http.server]
   [reitit.ring :as ring]
   [ring.middleware.defaults :as rmd]
   [ring.util.response :as rur]
   [shadow.css :refer [css]]
   ))

(defn- html-response
  ([body] (html-response body 200))
  ([body status] (-> (rur/response body)
                     (assoc :status status)
                     (assoc-in [:headers "Content-Type"] "text/html"))))

(defn- json-response
  ([body] (json-response body 200))
  ([body status] (-> (rur/response (if (string? body)
                                     body
                                     (json/write-json-str body)))
                     (assoc :status status)
                     (assoc-in [:headers "Content-Type"] "application/json"))))

(defn- get-json [url cookies]
  (let [res @(http.client/get url
                              {:headers {"Accept" "application/json"
                                         "Cookie" cookies}})]
    (if (:error res)
      (throw (ex-info "Failed to get json resource." {:url url} (:error res)))
      (-> res (update :body json/read-json)))))

(defn- css-link []
  [:link {:rel "stylesheet" :href "/css/gen/main.css"}])

(defn render-data [x]
  [:div
   [:pre
    (with-out-str (pp/pprint x))]])

(def kratos-public
  "Kratos http endpoint for auth flows."
  "http://localhost:4433")

(defn flow-url:create
  "Get the URL to create a new flow of some type."
  [flow-type]
  (str kratos-public "/self-service/" (name flow-type) "/browser"))

(defn flow-url:get
  "Get the URL to get an existing flow"
  [flow-type flow-id]
  (str kratos-public "/self-service/" (name flow-type) "/flows?id=" flow-id))

(defn flow-url:get-or-create
  "Get the URL for a flow of some type, creating a new one unless `req`'s query params
  have 'flow', the id of an existing flow."
  [flow-type req]
  (if-let [id (-> req :query-params (get "flow"))]
    (flow-url:get flow-type id)
    (flow-url:create flow-type)))

(defn whoami-url []
  (str kratos-public "/sessions/whoami"))

(defn fetch-session
  "Get a user's session from Kratos."
  [cookies]
  (when cookies
    (try
      (let [response (get-json (whoami-url) cookies)]
        (when-not (get-in response [:body "error"])
          (:body response)))
      (catch Exception _ nil))))

(defn wrap-auth
  "Ring middleware to assoc user's session, if there is one, onto the request map,
  under the :session key."
  [handler]
  (fn [req]
    (let [session (fetch-session (get-in req [:headers "cookie"]))
          req (if session
                (assoc req :session session)
                req)]
      (handler req))))

(defn redirect-if-authenticated
  "Middleware to redirect requests if the user is already authenticated."
  [handler redirect-path]
  (fn [req]
    (tap> {:redirect-if-authenticated req})
    (if (:session req)
      (rur/redirect redirect-path)
      (handler req))))

(defn redirect-if-unauthenticated
  "Middleware to redirect requests if the user is not authenticated."
  [handler redirect-path]
  (fn [req]
    (if (:session req)
      (handler req)
      (rur/redirect redirect-path))))

;; Note: this is not a complete impl of ory's rendering data model.

(def message-type->class
  {"info" (css {:color "black"})
   "error" (css {:color "red"})
   "success" (css {:color "green"})})

(defn render-kratos-message [{:strs [text type]}]
  [:p {:class (get message-type->class type)}
   text])

(defn render-kratos-messages [messages]
  (when (seq messages)
    [:div (mapv render-kratos-message messages)]))

(defmulti render-kratos-node (fn [{:strs [type]}] type))

(defmethod render-kratos-node "text"
  [{:strs [attributes meta messages]}]
  (let [{:strs [label]} meta
        {:strs [text]} attributes
        {:strs [context id text type]} text]
    [[:p {:id id
          :class (get message-type->class type)}
      text]
     (render-kratos-messages messages)]))

(defmethod render-kratos-node "div"
  [{:strs [attributes meta messages]}]
  (let [{:strs [label]} meta
        {:strs [class data id]} attributes]
    [[:div (merge {:id id
                   :class [class]
                   :data-data (str data)}
                  #_data)]
     (render-kratos-messages messages)]))

(defmethod render-kratos-node "script"
  [{:strs [attributes meta messages]}]
  ;; Scripts are not needed for basic signin, as far as I can tell.
  [:div "TODO: script rendering"])

(defmulti render-dom-input (fn [{:strs [type]} _] type))

(defn- texty-input [attributes meta]
  (let [{:strs [type autocomplete disabled maxlength name pattern required value]} attributes
        {:strs [label]} meta
        id (str name)]
    [:div {:class (css :mt-4 ;; :flex :flex-col :items-center
                       ["& label" {:display "block"}])}
     [:label {:for id} (get label "text") #_(get label "text")]
     [:input {:id id
              :type type
              :autocomplete autocomplete
              :disabled disabled
              :maxlength maxlength
              :name name
              :pattern pattern
              ;; :required required
              :value value}]]))

(defmethod render-dom-input "text" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "password" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "number" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "email" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "tel" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "datetime-local" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "date" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "url" [attrs meta]
  (texty-input attrs meta))

(defmethod render-dom-input "checkbox" [attrs meta]
  (let [{:strs [type disabled name required value]} attrs
        {:strs [label]} meta
        id (str name)]
    [:div {:class (css :mt-4 :flex :items-center)}
     [:input {:type type
              :disabled disabled
              :name name
              ;; :required required
              :checked value
              :id id}]
     [:label {:for id} (get label "text")]]))

(defmethod render-dom-input "hidden" [{:strs [disabled name required value]} meta]
  [:input {:type "hidden"
           :value value
           ;; :required required
           :name name
           :disabled disabled}])

(defmethod render-kratos-node "input"
  [{:strs [attributes meta messages]}]
  (let [{:strs [label]} meta
        {:strs [disabled name required type value]} attributes]
    [(cond
       (or (= type "submit") (= type "button"))
       [:button {:class (css :mt-4 {:display "block"})
                 :disabled disabled
                 :name name
                 ;; :required required
                 :value value}
        (str (get-in meta ["label" "text"]))]
       :else
       (render-dom-input attributes meta))
     (render-kratos-messages messages)]))

(defmethod render-kratos-node "a"
  [{:strs [attributes meta messages]}]
  (let [{:strs [label]} meta
        {:strs [href id title]} attributes]
    [[:a {:href href :id id} (get title "text")]
     (render-kratos-messages messages)]))

(defmethod render-kratos-node "img"
  [{:strs [attributes meta messages]}]
  (let [{:strs [label]} meta
        {:strs [height id src width]} attributes]
    [[:img {:height height :width width :id id :src src}]
     (render-kratos-messages messages)]))

(defn render-kratos-form [{:strs [method action nodes messages] :as ui}]
  [:form {:method method :action action}
   (render-kratos-messages messages)
   (mapv render-kratos-node nodes)])

(defn add-kratos-cookies-to-response [response flow]
  (let [kratos-cookies (-> flow :headers :set-cookie)]
    (assoc-in response [:headers "Set-Cookie"]
              (if (string? kratos-cookies)
                [kratos-cookies]
                kratos-cookies))))

(defn login-page [req]
  (let [flow (get-json (flow-url:get-or-create "login" req)
                       (get-in req [:headers "cookie"]))
        ui (get-in flow [:body "ui"])]
    (-> (html-response
         (chassis/html
          [chassis/doctype-html5
           [:head (css-link)]
           [:body
            [:h1 "Login"]
            (render-kratos-form ui)
            [:h1 "Don't have an account?"]
            [:a {:href "/auth/registration"} "Sign Up"]
            [:h1 "Forgot your password?"]
            [:a {:href "/auth/recovery"} "Recover Account"]
            (render-data flow)]]))
        (add-kratos-cookies-to-response flow))))

(defn recovery-page [req]
  (let [flow (get-json (flow-url:get-or-create "recovery" req)
                       (get-in req [:headers "cookie"]))
        ui (get-in flow [:body "ui"])]
    (-> (html-response
         (chassis/html
          [chassis/doctype-html5
           [:head (css-link)]
           [:body
            [:h1 "Recover Password"]
            (render-kratos-form ui)
            [:h1 "Don't have an account?"]
            [:a {:href "/auth/registration"} "Sign Up"]
            (render-data flow)]]))
        (add-kratos-cookies-to-response flow))))

(defn settings-page [req]
  (let [flow (get-json (flow-url:get-or-create "settings" req)
                       (get-in req [:headers "cookie"]))
        ui (get-in flow [:body "ui"])]
    (-> (html-response
         (chassis/html
          [chassis/doctype-html5
           [:head (css-link)]
           [:body
            [:h1 "Account Settings"]
            (render-kratos-form ui)
            (render-data flow)]]))
        (add-kratos-cookies-to-response flow))))

(defn profile-page [req]
  (html-response
   (chassis/html
    [chassis/doctype-html5
     [:head (css-link)]
     [:body
      [:h1 "This page is only accessible when you're logged in."]]])))

(defn home-page [req]
  (let [{:keys [session]} req
        html (if session
               (let [logout (get-json (flow-url:get-or-create "logout" req)
                                      (get-in req [:headers "cookie"]))
                     logout-url (get-in logout [:body "logout_url"])]
                 [[:h1 "You are logged in and this is your session:"]
                  [:div
                   [:pre (with-out-str (clojure.pprint/pprint session))]]
                  [:div
                   [:a {:href "/profile"} "View Your Profile"]]
                  [:div
                   [:a {:href logout-url} "Log Out"]]])
               [[:h1 "You are not logged in."]
                [:div
                 [:a {:href "/auth/login"} "Sign In"]]
                [:div
                 [:a {:href "/auth/registration"} "Sign Up"]]])]
    (html-response
     (chassis/html
      [chassis/doctype-html5
       [:body
        html]]))))

(defn sign-up-page [req]
  (let [flow (get-json (flow-url:get-or-create "registration" req)
                       (get-in req [:headers "cookie"]))
        ui (get-in flow [:body "ui"])]
    (-> (html-response
         (chassis/html
          [chassis/doctype-html5
           [:head (css-link)]
           [:body
            [:h1 "Sign Up"]
            (render-kratos-form ui)
            [:h1 "Already have an account?"]
            [:a {:href "/auth/login"} "Sign In"]
            (render-data flow)]]))
        (add-kratos-cookies-to-response flow))))

(defn verification-page [req]
  (let [flow (get-json (flow-url:get-or-create "verification" req)
                       (get-in req [:headers "cookie"]))
        ui (get-in flow [:body "ui"])]
    (-> (html-response
         (chassis/html
          [chassis/doctype-html5
           [:head (css-link)]
           [:body
            [:h1 "Verify"]
            (render-kratos-form ui)
            (render-data flow)]]))
        (add-kratos-cookies-to-response flow))))

(defn auth-error [req]
  (if-let [error-id (get-in req [:query-params "id"])]
    (let [error-info (get-json (str kratos-public "/self-service/errors?id=" error-id)
                               (get-in req [:headers "cookie"]))]
      (html-response
       (chassis/html
        [chassis/doctype-html5
         [:head (css-link)]
         [:body
          (render-data error-info)]])))
    (html-response
     (chassis/html
      [chassis/doctype-html5
       [:body "Something went wrong."]]))))

(defn login-hook [req]
  (let [body (-> req :body (json/read-json))]
    (tap> {:login-hook req
           :body body}))
  (json-response {:ok true}))

(defn save-user! [user-info]
  (tap>
   [:save-user
    {:msg "This would be a reasonable place to save the new user to the app DB."
     :user-info user-info}]))

(defn pre-registration-hook [req]
  (let [my-id (random-uuid)
        body (-> req :body (json/read-json))]
    (tap>
     [:registration-hook
      {:request req
       :phase "before"
       :my-id my-id
       :body body}])
    ;; Send an external ID to kratos.
    ;; This way we can get our IDs directly from the session without
    ;; an extra lookup step.
    (json-response {:identity {:external_id my-id}})))

(defn post-registration-hook [req]
  (let [body (-> req :body (json/read-json))
        my-id (get-in body ["ctx" "identity" "external_id"])]
    (tap>
     [:registration-hook
      {:request req
       :phase "after"
       :my-id my-id
       :body body}])
    (save-user! {:our-id my-id
                 :kratos-id (get-in body ["ctx" "identity" "id"])})
    {:status 204}))

(def router
  (ring/router
   [["/" {:get home-page
          :middleware [wrap-auth]}]
    ["/auth/login" {:get login-page
                    :middleware [wrap-auth
                                 #(redirect-if-authenticated % "/")]}]
    ["/auth/registration" {:get sign-up-page
                           :middleware [wrap-auth
                                        #(redirect-if-authenticated % "/")]}]
    ["/auth/verification" {:get verification-page
                           :middleware []}]
    ["/auth/recovery" {:get recovery-page
                       :middleware [wrap-auth #(redirect-if-authenticated % "/")]}]
    ["/auth/settings" {:get settings-page
                       :middleware [wrap-auth #(redirect-if-unauthenticated % "/auth/login")]}]
    ["/auth/hook/login" {:post login-hook}]
    ["/auth/hook/registration/before-identity" {:post pre-registration-hook}]
    ["/auth/hook/registration/after-identity"  {:post post-registration-hook}]
    ["/auth/error" {:get auth-error}]
    ["/profile" {:get profile-page
                 :middleware [wrap-auth
                              #(redirect-if-unauthenticated % "/auth/login")]}]
    ["/css/*" (ring/create-file-handler {:root "resources/public/css"})]]))

(def app (-> (ring/ring-handler router (ring/redirect-trailing-slash-handler))
             (rmd/wrap-defaults (-> rmd/site-defaults
                                    ;; We don't need this stuff from ring.middleware.defaults
                                    ;; because Kratos handles it:
                                    (assoc-in [:security :anti-forgery] false)
                                    (assoc-in [:session] false)))))

(defn start! []
  (http.server/run-server app {:port 43000
                               :legacy-return-value? false}))

(defn stop! [server]
  (http.server/server-stop! server))

(mount/defstate server
  :start (start!)
  :stop  (stop! server))

(comment
  (mount/start #'server)
  (http.server/server-port server)
  (mount/stop #'server))

(comment
  (require '[portal.api :as portal])
  (portal/open)
  (add-tap #'portal/submit)
  (tap> "shit"))
