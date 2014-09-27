(ns acha
  (:require
    [acha.react :as r :include-macros true]
    [sablono.core :as s :include-macros true]
    [goog.events :as events]
    [goog.history.EventType :as EventType]
    [datascript :as d]
    [acha.util :as u]
    [clojure.string :as str]
    [cognitect.transit :as transit])
  (:import
    goog.history.Html5History
    goog.net.XhrIo))



(enable-console-print!)


;; DB

(def conn (d/create-conn))

;; Utils

(defn- ajax [url callback & [method]]
  (.send goog.net.XhrIo url
    (fn [reply]
      (-> (.-target reply)
          (.getResponseText)
;;           (->> (.parse js/JSON))
;;           (js->clj :keywordize-keys true)
          (->> (transit/read (transit/reader :json)))
          (callback)))
    (or method "GET")))

(defn map-by [f xs]
  (reduce (fn [acc x] (assoc acc (f x) x)) {} xs))

(defn repo-name [url]
  (let [[_ m] (re-matches #".*/([^/]+)" url)]
    (if (and m (re-matches #".*\.git" m))
      (subs m 0 (- (count m) 4))
      m)))

;; Navigation

(def ^:private history (Html5History.))

(def ^:dynamic *title-suffix* "Acha-acha")

(defn set-title! [title]
  (set! (.-title js/document) (if title (str title " — " *title-suffix*) *title-suffix*)))

(defn go! [& path]
  (.setToken history (apply str path)))


;; Rendering

(r/defc header []
  (s/html
    [:.header
      [:h1.a {:on-click (fn [_] (go! "")) } "Acha-acha"]
      [:h2 "Enterprise Git Achievement solution. Web scale. In the cloud"]]))

(r/defc repo [repo]
  (s/html
    [:.repo.a {:on-click (fn [_] (go! "/repos/" (:repo/id repo)))}
      [:.repo__name
        (:repo/name repo)
        [:span.id (:repo/id repo)]
        (when (= :added (:repo/status repo)) [:span {:className "tag repo__added"} "Added"])]
      [:.repo__url (:repo/url repo)]     
      ]))

(defn add-repo []
  (let [el  (.getElementById js/document "add_repo__input")
        url (str/trim (.-value el))]
    (when-not (str/blank? url)
      (ajax (str "/api/add-repo/?url=" (js/encodeURIComponent url))
        (fn [data]
          (if (= :added (:repo/status data))
            (d/transact! conn [{:repo/id     (get-in data [:repo :id])
                                :repo/url    (get-in data [:repo :url])
                                :repo/name   (repo-name (get-in data [:repo :url]))
                                :repo/status :added}])
            (println "Repo already exist" data)))
        "POST")
      (set! (.-value el) "")
      (.focus el))))

(r/defc repo-pane [repos]
  (s/html
    [:.repo_pane
      [:h1 "Repos"]
      [:ul
        (map (fn [r] [:li (repo r)]) repos)]
      [:form.add_repo {:on-submit (fn [e] (add-repo) (.preventDefault e))}
        [:input {:id "add_repo__input" :type :text :placeholder "Clone URL"}]
;;         [:button]
       ]
    ]))

;; (def silhouette "https%3A%2F%2Fdl.dropboxusercontent.com%2Fu%2F561580%2Fsilhouette.png")

(r/defc user [user]
  (let [email-hash (when-let [email (:user/email user)] (js/md5 email))]
    (s/html
      [:.user.a {:on-click (fn [_] (go! "/users/" (:user/id user)))}
        [:.user__avatar
          [:img {:src (str "http://www.gravatar.com/avatar/" email-hash "?d=retro")}]]
        [:.user__name (:user/name user) [:span.id (:user/id user)]]
        [:.user__email (:user/email user)]
        [:.user__ach (:user/ach user)]])))

(r/defc users-pane [users]
  (s/html
    [:.users_pane
      [:h1 "Users"]
      [:ul
        (map (fn [u] [:li (user u)]) users)]]))

(r/defc index-page [db]
  (do
    (set-title! nil)
    (s/html
      [:.window
        (header)
        (users-pane (->> (u/qes-by db :user/id) (sort-by :user/ach) reverse))
        (repo-pane  (u/qes-by db :repo/name))])))

(r/defc repo-page [db id]
  (let [repo (u/qe-by db :repo/id id)]
    (set-title! (:repo/name repo))
    (s/html
      [:.window
        (header)
        (repo-pane [repo])])))

(r/defc user-page [db id]
  (let [user (u/qe-by db :user/id id)]
    (set-title! (:user/name user))
    (s/html
      [:.window
        (header)
        (users-pane [user])])))

(r/defc application [db]
  (let [path      (u/q1 '[:find ?p :where [0 :path ?p]] db)
        [_ p0 p1] (str/split path #"/")]
    (cond
      (= p0 nil)     (index-page db)
      (= p0 "users") (user-page db (js/parseInt p1))
      (= p0 "repos") (repo-page db (js/parseInt p1)))))

;; Rendering

(def render-db (atom nil))

(defn request-render [db]
  (reset! render-db db))

(defn render []
  (when-let [db @render-db]
    (r/render (application db) (.-body js/document))
    (reset! render-db nil)))

(add-watch render-db :render (fn [_ _ old-val new-val]
  (when (and (nil? old-val) new-val)
    (js/requestAnimationFrame render))))

;; Start

(defn ^:export start []
  (d/listen! conn
    (fn [tx-report]
      (request-render (:db-after tx-report))))

  (doto history
    (events/listen EventType/NAVIGATE (fn [e] (d/transact! conn [[:db/add 0 :path (.-token e)]])))
    (.setUseFragment true)
    (.setPathPrefix "#")
    (.setEnabled true))
  
  (ajax "/api/users/"
    (fn [us]
      (d/transact! conn
        (map (fn [u] {:user/id    (:id u)
                      :user/name  (:name u)
                      :user/email (:email u)
                      :user/ach   (:achievements u)})
               us))))
  
  (ajax "/api/repos/"
    (fn [rs]
      (d/transact! conn
        (map (fn [r] {:repo/id   (:id r)
                      :repo/url  (:url r)
                      :repo/name (repo-name (:url r)) })
             rs)))))
