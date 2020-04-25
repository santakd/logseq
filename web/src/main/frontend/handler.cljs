(ns frontend.handler
  (:refer-clojure :exclude [clone load-file])
  (:require [frontend.git :as git]
            [frontend.fs :as fs]
            [frontend.state :as state]
            [frontend.db :as db]
            [frontend.storage :as storage]
            [frontend.search :as search]
            [frontend.util :as util]
            [frontend.config :as config]
            [frontend.diff :as diff]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [reitit.frontend.easy :as rfe]
            [goog.crypt.base64 :as b64]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [datascript.core :as d]
            [dommy.core :as dom]
            [frontend.utf8 :as utf8]
            [frontend.image :as image]
            [clojure.set :as set]
            [cljs-bean.core :as bean]
            [frontend.format :as format]
            [frontend.format.protocol :as protocol])
  (:import [goog.events EventHandler]
           [goog.format EmailAddress]))

;; TODO: replace all util/p-handle with p/let
;; TODO: separate git status for push-failed, pull-failed, etc
(defn set-state-kv!
  [key value]
  (swap! state/state assoc key value))

(defn get-github-token
  []
  (get-in @state/state [:me :access-token]))

(defn load-file
  [repo-url path]
  (fs/read-file (git/get-repo-dir repo-url) path))

(defn- hidden?
  [path patterns]
  (some (fn [pattern]
          (or
           (= path pattern)
           (and (string/starts-with? pattern "/")
                (= (str "/" (first (string/split path #"/")))
                   pattern)))) patterns))

(defn- keep-formats
  [files formats]
  (filter
   (fn [file]
     (let [format (format/get-format file)]
       (contains? formats format)))
   files))

(defn- only-text-formats
  [files]
  (keep-formats files config/text-formats))

(defn- only-html-render-formats
  [files]
  (keep-formats files config/html-render-formats))

;; TODO: no atom version
(defn load-files
  [repo-url]
  (set-state-kv! :repo/loading-files? true)
  (let [files-atom (atom nil)]
    (-> (p/let [files (bean/->clj (git/list-files repo-url))]
          (if (contains? (set files) config/hidden-file)
            (let [patterns-content (load-file repo-url config/hidden-file)]
              (let [patterns (string/split patterns-content #"\n")]
                (reset! files-atom (remove (fn [path] (hidden? path patterns)) files))))
            (reset! files-atom files)))
        (p/finally
          (fn []
            @files-atom)))))

(defn- set-latest-commit!
  [hash]
  (set-state-kv! :git/latest-commit hash)
  (storage/set :git/latest-commit hash))

(defn- set-git-status!
  [value]
  (set-state-kv! :git/status value)
  (storage/set :git/status value))

(defn- set-git-error!
  [value]
  (set-state-kv! :git/error value)
  (if value
    (storage/set :git/error (str value))
    (storage/remove :git/error)))

(defn git-add-commit
  [repo-url file message content]
  (set-git-status! :commit)
  (git/add-commit repo-url file message
                  (fn []
                    (set-git-status! :should-push))
                  (fn [error]
                    (set-git-status! :commit-failed)
                    (set-git-error! error))))

;; journals

;; org-journal format, something like `* Tuesday, 06/04/13`
(defn default-month-journal-content
  []
  (let [{:keys [year month day]} (util/get-date)
        last-day (util/get-month-last-day)
        month-pad (if (< month 10) (str "0" month) month)]
    (->> (map
           (fn [day]
             (let [day-pad (if (< day 10) (str "0" day) day)
                   weekday (util/get-weekday (js/Date. year (dec month) day))]
               (str "* " weekday ", " month-pad "/" day-pad "/" year "\n")))
           (range 1 (inc last-day)))
         (apply str))))

(defn create-month-journal-if-not-exists
  [repo-url]
  (let [repo-dir (git/get-repo-dir repo-url)
        path (util/current-journal-path)
        file-path (str "/" path)
        default-content (default-month-journal-content)]
    (p/let [_ (-> (fs/mkdir (str repo-dir "/journals"))
                  (p/catch (fn [_e])))
            file-exists? (fs/create-if-not-exists repo-dir file-path default-content)]
      (when-not file-exists?
        (db/reset-file! repo-url path default-content)
        (git-add-commit repo-url path "create a month journal" default-content)))))

(defn load-files-contents!
  [repo-url files ok-handler]
  (let [files (only-text-formats files)]
    (p/let [contents (p/all (doall
                             (for [file files]
                               (load-file repo-url file))))]
      (ok-handler
       (zipmap files contents)))))

(defn load-repo-to-db!
  [repo-url files diffs first-clone?]
  (set-state-kv! :repo/loading-files? false)
  (set-state-kv! :repo/importing-to-db? true)
  (let [load-contents (fn [files delete-files delete-headings]
                        (load-files-contents!
                         repo-url
                         files
                         (fn [contents]
                           (let [headings-pages (db/extract-all-headings-pages contents)]
                             (db/reset-contents-and-headings! repo-url contents headings-pages delete-files delete-headings)
                             (set-state-kv! :repo/importing-to-db? false)))))]

    (if first-clone?
      (load-contents files nil nil)
      (when (seq diffs)
        (let [filter-diffs (fn [type] (->> (filter (fn [f] (= type (:type f))) diffs)
                                           (map :path)))
              remove-files (filter-diffs "remove")
              modify-files (filter-diffs "modify")
              add-files (filter-diffs "add")
              delete-files (if (seq remove-files)
                             (db/delete-files remove-files))
              delete-headings (db/delete-headings repo-url (concat remove-files modify-files))
              add-or-modify-files (util/remove-nils (concat add-files modify-files))]
          (load-contents add-or-modify-files delete-files delete-headings))))))

(defn journal-file-changed?
  [repo-url diffs]
  (contains? (set (map :path diffs))
             (db/get-current-journal-path)))

(defn load-db-and-journals!
  [repo-url diffs first-clone?]
  (when (or diffs first-clone?)
    (p/let [files (load-files repo-url)
            _ (load-repo-to-db! repo-url files diffs first-clone?)]
      (create-month-journal-if-not-exists repo-url))))

(defn show-notification!
  [content status]
  (swap! state/state assoc
         :notification/show? true
         :notification/content content
         :notification/status status)
  (when-not (= status :error)
    (js/setTimeout #(swap! state/state assoc
                           :notification/show? false
                           :notification/content nil
                           :notification/status nil)
                   5000)))

(defn clear-storage
  []
  (p/let [_idb-clear (js/window.pfs._idb.wipe)]
    (js/localStorage.clear)
    (set! (.-href js/window.location) "/")))

(defn pull
  [repo-url token]
  (let [status (:git/status @state/state)]
    (when (and
           (not (:edit? @state/state))
           ;; (nil? (:git/error @state/state))
           (or (nil? status)
               (= status :pulling)))
      (set-git-status! :pulling)
      (let [latest-commit (:git/latest-commit @state/state)]
        (p/let [result (git/fetch repo-url token)
                {:keys [fetchHead]} (bean/->clj result)
                _ (set-latest-commit! fetchHead)]
          (-> (git/merge repo-url)
              (p/then (fn [result]
                        (-> (git/checkout repo-url)
                            (p/then (fn [result]
                                      (set-git-status! nil)
                                      (when (and latest-commit fetchHead
                                                 (not= latest-commit fetchHead))
                                        (p/let [diffs (git/get-diffs repo-url latest-commit fetchHead)]
                                          (load-db-and-journals! repo-url diffs false)))))
                            (p/catch (fn [error]
                                       (set-git-status! :checkout-failed)
                                       (set-git-error! error))))))
              (p/catch (fn [error]
                         (set-git-status! :merge-failed)
                         (set-git-error! error)
                         (show-notification!
                          [:p.content
                           "Merges with conflicts are not supported yet, please "
                           [:span.text-gray-700.font-bold
                            "make sure saving all your changes elsewhere"]
                           ". After that, click "
                           [:a.font-bold {:href ""
                                          ;; TODO: discard current changes then continue to pull instead of clone again
                                          :on-click clear-storage}
                            "Pull again"]
                           " to pull the latest changes."]
                          :error)))))))))

(defn pull-current-repo
  []
  (when-let [repo (state/get-current-repo)]
    (when-let [token (get-github-token)]
      (pull repo token))))

(defn periodically-pull
  [repo-url pull-now?]
  (when-let [token (get-github-token)]
    (when pull-now? (pull repo-url token))
    (js/setInterval #(pull repo-url token)
                    (* config/auto-pull-secs 1000))))

(defn get-latest-commit
  [handler]
  (-> (p/let [commits (git/log (state/get-current-repo)
                               (get-github-token)
                               1)]
        (handler (first commits)))
      (p/catch (fn [error]
                 (prn "get latest commit failed: " error)))))

(defn set-latest-commit-if-exists! []
  (get-latest-commit
   (fn [commit]
     (when-let [hash (gobj/get commit "oid")]
       (set-latest-commit! hash)))))

;; TODO: update latest commit
(defn push
  [repo-url]
  (when (and
         (not (:edit? @state/state))
         ;; (nil? (:git/error @state/state))
         (= :should-push (:git/status @state/state)))
    (set-git-status! :pushing)
    (let [token (get-github-token)]
      (util/p-handle
       (git/push repo-url token)
       (fn []
         (prn "Push successfully!")
         (set-git-status! nil)
         (set-git-error! nil)
         (set-latest-commit-if-exists!))
       (fn [error]
         (prn "Failed to push, error: " error)
         (set-git-status! :push-failed)
         (set-git-error! error)
         (show-notification!
          [:p.content
           "Failed to push, please "
           [:span.text-gray-700.font-bold
            "make sure saving all your changes elsewhere"]
           ". After that, click "
           [:a.font-bold {:href ""
                          :on-click clear-storage}
            "Pull again"]
           " to pull the latest changes."]
          :error))))))

(defn re-render!
  []
  (when-let [comp (:root-component @state/state)]
    (when (and (not (:edit? @state/state)))
      (rum/request-render comp))))

(defn db-listen-to-tx!
  [repo db-conn]
  (d/listen! db-conn :persistence
             (fn [tx-report]
               (when-let [db (:db-after tx-report)]
                 (re-render!)
                 (js/setTimeout (fn []
                                  (db/persist repo db)) 0)))))

(defn clone
  [repo]
  (let [token (get-github-token)]
    (util/p-handle
     (do
       (set-state-kv! :git/status :cloning)
       (git/clone repo token))
     (fn []
       (set-git-status! nil)
       (state/set-current-repo! repo)
       (db/start-db-conn! (:me @state/state)
                          repo
                          db-listen-to-tx!)
       (db/mark-repo-as-cloned repo)
       (set-latest-commit-if-exists!)
       (util/post (str config/api "repos")
                  {:url repo}
                  (fn [result]
                    (swap! state/state
                           update-in [:me :repos] conj result))
                  (fn [error]
                    (prn "Something wrong!"))))
     (fn [e]
       (set-git-status! :clone-failed)
       (set-git-error! e)
       (prn "Clone failed, reason: " e)))))

(defn new-notification
  [text]
  (js/Notification. "Logseq" #js {:body text
                                  ;; :icon logo
                                  }))

(defn request-notifications
  []
  (util/p-handle (.requestPermission js/Notification)
                 (fn [result]
                   (storage/set :notification-permission-asked? true)

                   (when (= "granted" result)
                     (storage/set :notification-permission? true)))))

(defn request-notifications-if-not-asked
  []
  (when-not (storage/get :notification-permission-asked?)
    (request-notifications)))

;; notify deadline or scheduled tasks
(defn run-notify-worker!
  []
  (when (storage/get :notification-permission?)
    (let [notify-fn (fn []
                      (let [tasks (:tasks @state/state)
                            tasks (flatten (vals tasks))]
                        (doseq [{:keys [marker title] :as task} tasks]
                          (when-not (contains? #{"DONE" "CANCElED" "CANCELLED"} marker)
                            (doseq [[type {:keys [date time] :as timestamp}] (:timestamps task)]
                              (let [{:keys [year month day]} date
                                    {:keys [hour min]
                                     :or {hour 9
                                          min 0}} time
                                    now (util/get-local-date)]
                                (when (and (contains? #{"Scheduled" "Deadline"} type)
                                           (= (assoc date :hour hour :minute min) now))
                                  (let [notification-text (str type ": " (second (first title)))]
                                    (new-notification notification-text)))))))))]
      (notify-fn)
      (js/setInterval notify-fn (* 1000 60)))))

(defn clear-edit!
  []
  (swap! state/state assoc
         :edit? false
         :edit-file nil
         :edit-journal nil
         :edit-content ""))

(defn file-changed?
  [content]
  (not= (string/trim content)
        (string/trim (state/get-edit-content))))

(defn alter-file
  ([path content]
   (alter-file path (str "Update " path) content))
  ([path commit-message content]
   (alter-file path commit-message content false))
  ([path commit-message content before?]
   (let [token (get-github-token)
         repo-url (state/get-current-repo)]
     (if before?
       (db/reset-file! repo-url path content))
     (util/p-handle
      (fs/write-file (git/get-repo-dir repo-url) path content)
      (fn [_]
        (if-not before?
          (db/reset-file! repo-url path content))
        (git-add-commit repo-url path commit-message content))))))

;; TODO: utf8 encode performance
(defn check
  [heading]
  (let [{:heading/keys [repo file marker meta uuid]} heading
        pos (:pos meta)
        repo (db/entity (:db/id repo))
        file (db/entity (:db/id file))
        repo-url (:repo/url repo)
        file (:file/path file)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first marker "DONE")))]
        (alter-file file (str marker " marked as DONE") content')))))

(defn uncheck
  [heading]
  (let [{:heading/keys [repo file marker meta]} heading
        pos (:pos meta)
        repo (db/entity (:db/id repo))
        file (db/entity (:db/id file))
        repo-url (:repo/url repo)
        file (:file/path file)
        token (get-github-token)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first "DONE" "TODO")))]
        (alter-file file "DONE rollbacks to TODO." content')))))

(defn git-set-username-email!
  [{:keys [name email]}]
  (when (and name email)
    (git/set-username-email
     (git/get-repo-dir (state/get-current-repo))
     name
     email)))

(defn set-route-match!
  [route]
  (swap! state/state assoc :route-match route)
  (when-let [fragment (util/get-fragment)]
    (util/scroll-to-element fragment)))

(defn set-ref-component!
  [k ref]
  (swap! state/state assoc :ref-components k ref))

(defn set-root-component!
  [comp]
  (swap! state/state assoc :root-component comp))

(defn periodically-push-tasks
  [repo-url]
  (let [token (get-github-token)
        push (fn []
               (push repo-url))]
    (js/setInterval push
                    (* config/auto-push-secs 1000))))

(defn periodically-pull-and-push
  [repo-url {:keys [pull-now?]
             :or {pull-now? true}}]
  (when-not config/dev?
    (periodically-pull repo-url pull-now?)
    (periodically-push-tasks repo-url)))

(defn edit-journal!
  [journal]
  (swap! state/state assoc
         :edit? true
         :edit-journal journal))

(defn edit-file!
  [file]
  (swap! state/state assoc
         :edit? true
         :edit-file file))

(defn render-local-images!
  []
  (when-let [content-node (gdom/getElement "content")]
    (let [images (array-seq (gdom/getElementsByTagName "img" content-node))
          get-src (fn [image] (.getAttribute image "src"))
          local-images (filter
                        (fn [image]
                          (let [src (get-src image)]
                            (and src
                                 (not (or (string/starts-with? src "http://")
                                          (string/starts-with? src "https://"))))))
                        images)]
      (doseq [img local-images]
        (gobj/set img
                  "onerror"
                  (fn []
                    (gobj/set (gobj/get img "style")
                              "display" "none")))
        (let [path (get-src img)
              path (if (= (first path) \.)
                     (subs path 1)
                     path)]
          (util/p-handle
           (fs/read-file-2 (git/get-repo-dir (state/get-current-repo))
                           path)
           (fn [blob]
             (let [blob (js/Blob. (array blob) (clj->js {:type "image"}))
                   img-url (image/create-object-url blob)]
               (gobj/set img "src" img-url)
               (gobj/set (gobj/get img "style")
                         "display" "initial")))))))))

(defn load-more-journals!
  []
  (swap! state/state update :journals-length inc)
  (let [journals (:latest-journals @state/state)]
    (when-let [title (first (last journals))]
      (let [[m d y] (->>
                     (-> (last (string/split title #", "))
                         (string/split #"/"))
                     (map util/parse-int))
            new-date (js/Date. y (dec m) d)
            _ (.setDate new-date (dec (.getDate new-date)))
            next-day (util/journal-name new-date)
            more-journals [(db/get-journal next-day)]
            journals (concat journals more-journals)]
        (set-state-kv! :latest-journals journals)))))

(defn request-presigned-url
  [file filename mime-type url-handler]
  (cond
    (> (gobj/get file "size") (* 5 1024 1024))
    (show-notification! [:p "Sorry, we don't support any file that's larger than 5MB."] :error)

    :else
    (util/post (str config/api "presigned_url")
               {:filename filename
                :mime-type mime-type}
               (fn [{:keys [presigned-url s3-object-key] :as resp}]
                 (if presigned-url
                   (util/upload presigned-url
                                file
                                (fn [_result]
                                  ;; request cdn signed url
                                  (util/post (str config/api "signed_url")
                                             {:s3-object-key s3-object-key}
                                             (fn [{:keys [signed-url]}]
                                               (if signed-url
                                                 (do
                                                   (prn "Get a singed url: " signed-url)
                                                   (url-handler signed-url))
                                                 (prn "Something error, can't get a valid signed url.")))
                                             (fn [error]
                                               (prn "Something error, can't get a valid signed url."))))
                                (fn [error]
                                  (prn "upload failed.")
                                  (js/console.dir error)))
                   ;; TODO: notification, or re-try
                   (prn "failed to get any presigned url, resp: " resp)))
               (fn [_error]
                 ;; (prn "Get token failed, error: " error)
                 ))))

(defn set-me-if-exists!
  []
  (when js/window.user
    (when-let [me (bean/->clj js/window.user)]
      (set-state-kv! :me me)
      me)))

(defn sign-out!
  [e]
  (p/let [_idb-clear (js/window.pfs._idb.wipe)]
    (js/localStorage.clear)
    (set! (.-href js/window.location) "/logout")))

(defn set-format-js-loading!
  [format value]
  (when format
    (swap! state/state assoc-in [:format/loading format] value)))

(defn lazy-load
  [format]
  (let [format (format/normalize format)]
    (when-let [record (format/get-format-record format)]
      (when-not (protocol/loaded? record)
        (set-format-js-loading! format true)
        (protocol/lazyLoad record
                           (fn [result]
                             (set-format-js-loading! format false)))))))

(defn reset-cursor-range!
  [node]
  (when node
    (state/set-cursor-range! (util/caret-range node))))

(defn reset-cursor-pos!
  [id]
  (when-let [node (gdom/getElement (str id))]
    (let [pos (util/caret-pos node)]
      (state/set-cursor-pos! pos))))

(defn restore-cursor-pos!
  ([id markup]
   (restore-cursor-pos! id markup false))
  ([id markup dummy?]
   (when-let [node (gdom/getElement (str id))]
     (when-let [range (string/trim (state/get-cursor-range))]
       (let [pos (inc (diff/find-position markup range))
             pos (if dummy? (inc pos) pos)]
         (util/set-caret-pos! node pos))))))

(defn move-cursor-to-end [input]
  (let [n (count (.-value input))]
    (set! (.-selectionStart input) n)
    (set! (.-selectionEnd input) n)))

(defn insert-image!
  [image-url]
  ;; (let [content (state/get-edit-content)
  ;;       image (str "<img src=\"" image-url "\" />")
  ;;       new-content (str content "\n" "#+BEGIN_EXPORT html\n" image "\n#+END_EXPORT\n")
  ;;       ;; node @state/edit-node
  ;;       ]
  ;;   (state/set-edit-content! new-content)
  ;;   (set! (.-value node) new-content)
  ;;   (move-cursor-to-end node))
  )

(defn search
  [q]
  (swap! state/state assoc :search/result (search/search q)))

(defn clear-search!
  []
  (swap! state/state assoc :search/result nil))

(defn email? [v]
  (and v
       (.isValid (EmailAddress. v))))

(defn set-email!
  [email]
  (when (email? email)
    (util/post (str config/api "email")
               {:email email}
               (fn [result]
                 (db/transact! [{:me/email email}])
                 (swap! state/state assoc-in [:me :email] email))
               (fn [error]
                 (show-notification! "Email already exists!"
                                     :error)))))

(defn save-heading-if-changed!
  [{:heading/keys [uuid content meta file dummy?] :as heading} value]
  (let [value (string/trim value)]
    (if (not= (string/trim content)
              value)
      (let [file (db/entity (:db/id file))
            file-content (:file/content file)
            file-path (:file/path file)
            new-content (str value "\n")
            new-content (if dummy?
                          (str "\n" new-content)
                          new-content)
            new-file-content (utf8/insert! file-content
                                           (:pos meta)
                                           (if (or dummy? (string/blank? content))
                                             (:pos meta)
                                             (+ (:pos meta) (utf8/length (utf8/encode content))))
                                           new-content)
            new-file-content (string/trim new-file-content)]
        (alter-file file-path (str "Update " file-path) new-file-content true)))))

(defn clone-and-pull
  [repo-url]
  (p/then (clone repo-url)
          (fn []
            (git-set-username-email! (:me @state/state))
            (load-db-and-journals! repo-url nil true)
            (periodically-pull-and-push repo-url {:pull-now? false}))))


(defn remove-repo!
  [{:keys [id url] :as repo}]
  (util/delete (str config/api "repos/" id)
               (fn []
                 (db/remove-conn! url)
                 (storage/remove (db/datascript-db url))
                 (state/delete-repo! repo)
                 ;; TODO: clear indexdb
                 )
               (fn [error]
                 (prn "Delete repo failed, error: " error))))

(defn rebuild-index!
  [{:keys [id url] :as repo}]
  (db/remove-conn! url)
  (storage/remove (db/datascript-db url))
  (clone-and-pull url))

(defn redirect!
  "If `push` is truthy, previous page will be left in history."
  [{:keys [to path-params query-params push]
    :or {push true}}]
  (if push
    (rfe/push-state to path-params query-params)
    (rfe/replace-state to path-params query-params)))

(defn start!
  []
  (let [{:keys [repos] :as me} (set-me-if-exists!)]
    (db/restore! me db-listen-to-tx!)
    (doseq [{:keys [id url]} repos]
      (let [repo url]
        (if (db/cloned? repo)
          (periodically-pull-and-push repo {:pull-now? true})
          (clone-and-pull repo))))))
