(ns todoclj.core
  (:use     net.cgrand.moustache
            [hiccup.core]
            [ring.adapter.jetty :only [run-jetty]]
            ring.middleware.reload
            ring.middleware.params
            ring.middleware.stacktrace
            ring.util.response
            clojure.set)
  (:require [clojure.java.jdbc :as sql]
            [clojure.string    :as str])
  (:import (java.net URI)))
(declare my-app dev-app)

(defn -main
  ([dev] (doto (Thread. #(run-jetty dev-app {:port 8000})) .start))
  ([] (doto (Thread. #(run-jetty my-app {:port 8000})) .start)))
(defn one-to-many [xs name ys f]
  (for [x xs :let [ys (filter (partial f x) ys)]]
    (assoc x name ys)))

(defn database-resource []
  (let [url (URI. (System/getenv "DATABASE_URL"))
        host (.getHost url)
        port (if (pos? (.getPort url)) (.getPort url) 5432)
        path (.getPath url)]
    (merge
      {:subname (str "//" host ":" port path)}
      (if-let [user-info (.getUserInfo url)]
        {:user (first (str/split user-info #":"))
         :password (second (str/split user-info #":"))}))))

(def db
  (merge
    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"}
    (database-resource)))
(def my-app
  (app
   wrap-params
   []
   {:get
    (fn [req]
      (let [todos (sql/with-connection db
                    (sql/with-query-results res
                      [(str "select c.id, t.id as tid, t.task, t.done, c.name from"
                            " Todos t"
                            " right join Categories c on t.category = c.id")]
                      (into #{} res)))
            categories (into #{} (map (juxt :id :name) todos))]
        (response
         (html [:html
                [:head [:title "Hello World"]]
                [:body
                 (for [category categories]
                   [:div
                    [:p (category 1)]
                    [:form {:method "post" :action (str "/category/" (category 0) "/delete")}
                     [:input {:type "submit" :value "Delete"}]]
                    (for [todo (select #(and (= (:id %) (category 0))
                                             (:tid %)) todos)]
                      [:table {:border 1}
                       [:tr
                       [:td {:width 200} (todo :task)]
                       [:td
                        [:form {:method "post" :action (str "/todo/" (todo :tid) "/delete")}
                         [:input {:type "submit" :value "Delete"}]]]
                       [:td
                      [:form {:method "post" :action (str "/todo/" (todo :tid))}
                       [:input {:type "checkbox" :name "done" :value "done" :checked (todo :done)}]
                       [:input {:type "submit" :value "check"}]]]]])])
                 [:form {:method "post"}
                  "Category"
                  [:input {:name "category"}]
                  "Task"
                  [:input {:name "task"}]
                  [:input {:type "submit"}]]
                 [:form {:method "post" :action "/category"}
                  "New Category"
                  [:input {:name "name"}]
                  [:input {:type "submit"}]]]]))))
    :post
    (fn [req]
      (sql/with-connection db
        (sql/transaction
         (sql/with-query-results res
           [(str "select c.id from categories c"
                 " where c.name = ?"
                 " limit 1")
            (or ((req :params) "category") "abc")]
           (sql/insert-record :todos {:task     ((req :params) "task")
                                      :category (:id (first res))
                                      :done false}))))
      (redirect "/"))}
   ["todo" id]
   {:post
    (fn [req]
      (sql/with-connection db
        (sql/do-prepared "update todos set done = ? where id = ?"
                         [(= ((req :params) "done") "done") (Integer/parseInt id)]))
      (redirect "/"))}
   ["todo" id "delete"]
   {:post
    (fn [req]
      (sql/with-connection db
        (sql/do-prepared "delete from todos where todos.id = ?"
                         [(Integer/parseInt id)]))
      (redirect "/"))}
   ["category"]
   {:post
    (fn [req]
      (sql/with-connection db
        (sql/do-prepared "insert into categories(name) values (?)"
                         [((req :params) "name")]))
      (redirect "/"))}
   ["category" id "delete"]
   {:post
    (fn [req]
      (sql/with-connection db
        (sql/transaction
         (sql/do-prepared "delete from todos where category = ?"
                          [(Integer/parseInt id)])
         (sql/do-prepared "delete from categories where id = ?"
                          [(Integer/parseInt id)])))
      (redirect "/"))}))
(def dev-app
  (app
   wrap-reload
   wrap-stacktrace
   [&]
   my-app))