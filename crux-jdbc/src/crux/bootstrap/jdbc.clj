(ns crux.bootstrap.jdbc
  (:require [clojure.spec.alpha :as s]
            [crux.bootstrap :as b]
            [crux.lru :as lru]
            [crux.tx :as tx]
            [crux.io :as cio]
            [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [crux.tx.polling :as p]
            [crux.jdbc :as j])
  (:import crux.api.ICruxAPI
           crux.bootstrap.CruxNode
           crux.jdbc.JDBCEventLogConsumer))

(s/def ::options (s/keys :req-un [::dbtype
                                  ::dbname]))

(defn- setup-schema! [ds dbtype]
  (jdbc/execute! ds [(condp contains? dbtype
                       #{"h2"}
                       "create table if not exists tx_events (
  event_offset int auto_increment PRIMARY KEY, event_key VARCHAR,
  tx_time datetime default CURRENT_TIMESTAMP, topic VARCHAR NOT NULL,
  v BINARY NOT NULL)"

                       #{"postgresql" "pgsql"}
                       "create table tx_events (
  event_offset serial PRIMARY KEY, event_key VARCHAR,
  tx_time timestamp default CURRENT_TIMESTAMP, topic VARCHAR NOT NULL,
  v bytea NOT NULL)")]))

(s/def ::options (s/keys :req-un [::dbtype
                                  ::dbname
                                  ::blow-up]))

(defn- start-jdbc-ds [_ {:keys [dbtype] :as options}]
  (let [ds (jdbc/get-datasource options)]
    (setup-schema! ds dbtype)
    ds))

(defn- start-tx-log [{:keys [ds]} {:keys [dbtype]}]
  (j/map->JdbcTxLog {:ds ds :dbtype dbtype}))

(defn- start-event-log-consumer [{:keys [indexer ds]} _]
  (p/start-event-log-consumer indexer (JDBCEventLogConsumer. ds)))

(defmethod b/define-module ::ds [_] [start-jdbc-ds nil ::options])
(defmethod b/define-module ::tx-log [_] [start-tx-log [:ds]])
(defmethod b/define-module ::event-log-consumer [_] [start-event-log-consumer [:indexer :ds]])

(def node-config (merge b/base-node-config
                        {:ds ::ds
                         :tx-log ::tx-log
                         :event-log-consumer ::event-log-consumer}))

(defn ^ICruxAPI start-jdbc-node [options]
  (s/assert ::options options)
  (b/start-node node-config (merge b/default-options options)))
