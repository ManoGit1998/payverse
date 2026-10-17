#!/usr/bin/env python3
"""Generates the PayVerse Grafana dashboards (JSON) from compact panel definitions. Run after editing:
    python3 infra/grafana/build_dashboards.py
Metric names are the OTLP → Prometheus translations of the Micrometer meters (e.g. payverse.events → payverse_events_total)."""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dashboards")
DS = {"type": "prometheus", "uid": "prometheus"}
HTTP = 'http_server_requests_milliseconds'
APP = 'uri!~"/actuator.*"'  # business traffic only


class Board:
    def __init__(self, uid, title, description, variables=()):
        self.uid, self.title, self.description, self.variables = uid, title, description, list(variables)
        self.panels, self.y, self.next_id = [], 0, 1

    def row(self, title):
        self.panels.append({"type": "row", "title": title, "id": self.next_id, "collapsed": False,
                            "gridPos": {"x": 0, "y": self.y, "w": 24, "h": 1}, "panels": []})
        self.next_id += 1
        self.y += 1
        return self

    def add(self, panels, height=8):
        width = 24 // len(panels)
        for i, p in enumerate(panels):
            p.update({"id": self.next_id, "datasource": DS, "gridPos": {"x": i * width, "y": self.y, "w": width, "h": height}})
            self.next_id += 1
            self.panels.append(p)
        self.y += height
        return self

    def json(self):
        return {"uid": self.uid, "title": self.title, "description": self.description, "tags": ["payverse"],
                "timezone": "browser", "refresh": "30s", "schemaVersion": 39, "editable": True,
                "time": {"from": "now-1h", "to": "now"}, "templating": {"list": self.variables}, "panels": self.panels}


def target(expr, legend):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": chr(65 + abs(hash(expr + legend)) % 26)}


def timeseries(title, targets, unit="short", description="", stack=False):
    for i, t in enumerate(targets):
        t["refId"] = chr(65 + i)
    return {"type": "timeseries", "title": title, "description": description, "targets": targets,
            "fieldConfig": {"defaults": {"unit": unit, "custom": {"fillOpacity": 10, "lineWidth": 1,
                                                                  "stacking": {"mode": "normal" if stack else "none"}}},
                            "overrides": []},
            "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}


def stat(title, expr, unit="short", description="", thresholds=None, decimals=None):
    steps = thresholds or [{"color": "green", "value": None}]
    defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}}
    if decimals is not None:
        defaults["decimals"] = decimals
    return {"type": "stat", "title": title, "description": description, "targets": [target(expr, title) | {"refId": "A"}],
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}, "colorMode": "value",
                        "graphMode": "area", "textMode": "auto"}}


def variable(name, query, label, multi=True):
    return {"name": name, "label": label, "type": "query", "datasource": DS, "query": {"query": query, "refId": "v"},
            "definition": query, "refresh": 2, "includeAll": True, "multi": multi, "allValue": ".*",
            "current": {"selected": True, "text": "All", "value": "$__all"}, "sort": 1}


def events_rate(event_type, per="1m"):
    return f'sum(rate(payverse_events_total{{event_type="{event_type}"}}[$__rate_interval])) * 60'


def services_board():
    svc = 'service_name=~"$service"'
    b = Board("payverse-services", "PayVerse — Services", "RED metrics, JVM, connection pools and Kafka consumer lag per service.",
              [variable("service", f"label_values({HTTP}_count, service_name)", "Service")])
    b.row("Traffic (excluding actuator)").add([
        timeseries("Requests / s", [target(f'sum by (service_name) (rate({HTTP}_count{{{svc},{APP}}}[$__rate_interval]))', "{{service_name}}")], "reqps"),
        timeseries("Server errors (5xx) %", [target(
            f'100 * sum by (service_name) (rate({HTTP}_count{{{svc},{APP},status=~"5.."}}[$__rate_interval]))'
            f' / sum by (service_name) (rate({HTTP}_count{{{svc},{APP}}}[$__rate_interval]))', "{{service_name}}")], "percent"),
        timeseries("p95 latency", [target(
            f'histogram_quantile(0.95, sum by (le, service_name) (rate({HTTP}_bucket{{{svc},{APP}}}[$__rate_interval])))', "{{service_name}}")], "ms"),
    ]).add([
        timeseries("Client errors by code (4xx / s)", [target(
            f'sum by (status, service_name) (rate({HTTP}_count{{{svc},{APP},status=~"4.."}}[$__rate_interval]))', "{{service_name}} {{status}}")], "reqps",
            "422 = business refusals (limits, PIN, balance), 401/403 = auth, 429 = rate limited", stack=True),
        timeseries("Slowest endpoints (p95)", [target(
            f'topk(10, histogram_quantile(0.95, sum by (le, service_name, uri, method) (rate({HTTP}_bucket{{{svc},{APP}}}[$__rate_interval]))))',
            "{{service_name}} {{method}} {{uri}}")], "ms"),
    ])
    b.row("JVM and resources").add([
        timeseries("Heap used", [target(f'sum by (service_name) (jvm_memory_used_bytes{{{svc},area="heap"}})', "{{service_name}}")], "bytes"),
        timeseries("CPU", [target(f'max by (service_name) (process_cpu_usage{{{svc}}})', "{{service_name}}")], "percentunit"),
        timeseries("GC pause time / s", [target(f'sum by (service_name) (rate(jvm_gc_pause_milliseconds_sum{{{svc}}}[$__rate_interval]))', "{{service_name}}")], "ms"),
        timeseries("Live threads", [target(f'sum by (service_name) (jvm_threads_live{{{svc}}})', "{{service_name}}")]),
    ])
    b.row("Database and Kafka").add([
        timeseries("DB connections in use", [target(f'sum by (service_name) (hikaricp_connections_active{{{svc}}})', "{{service_name}}")]),
        timeseries("Threads waiting for a DB connection", [target(f'sum by (service_name) (hikaricp_connections_pending{{{svc}}})', "{{service_name}}")],
                   description="Anything above 0 for long means the pool (10–15) is too small or queries are slow"),
        timeseries("Kafka consumer lag (max records)", [target(
            f'max by (service_name, topic) (kafka_consumer_fetch_manager_records_lag_max{{{svc}}})', "{{service_name}} ← {{topic}}")]),
        timeseries("Outbox backlog", [target(f'max by (service_name) (payverse_outbox_pending{{{svc}}})', "{{service_name}}"),
                                      target(f'max by (service_name) (payverse_outbox_oldest_age_seconds{{{svc}}})', "{{service_name}} oldest (s)")],
                   description="Committed events not yet published to Kafka; should drain within a second"),
    ])
    return b


def business_board():
    b = Board("payverse-business", "PayVerse — Payments", "Money movement, risk, rewards, notifications and reconciliation — from domain events.")
    total = lambda et: f'sum(increase(payverse_events_total{{event_type="{et}"}}[$__range]))'
    value = lambda et: f'sum(increase(payverse_events_amount_inr_sum{{event_type="{et}"}}[$__range]))'
    red = [{"color": "green", "value": None}, {"color": "red", "value": 1}]
    b.row("Today at a glance (selected range)").add([
        stat("Transfers completed", total("transaction.completed")),
        stat("Transfer value", value("transaction.completed"), "currencyINR", decimals=0),
        stat("Transfers failed", total("transaction.failed"), thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 1}]),
        stat("Money added", value("wallet.money-added"), "currencyINR", decimals=0),
        stat("Bills paid", total("bill.payment-completed")),
        stat("Cashback paid", value("rewards.cashback-granted"), "currencyINR", decimals=0),
    ], height=5).add([
        stat("Blocked by risk", 'sum(increase(payverse_risk_decisions_total{decision="BLOCK"}[$__range]))', thresholds=red),
        stat("Refunds (reversed)", total("transaction.reversed")),
        stat("Recon mismatches", total("recon.mismatch-detected"), thresholds=red),
        stat("Notifications DEAD", 'sum(increase(payverse_notifications_total{outcome="DEAD"}[$__range]))', thresholds=red),
        stat("Outbox backlog (max)", 'max(payverse_outbox_pending)', thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 100}]),
        stat("Oldest unpublished event", 'max(payverse_outbox_oldest_age_seconds)', "s",
             thresholds=[{"color": "green", "value": None}, {"color": "red", "value": 30}]),
    ], height=5)
    b.row("Money movement (per minute)").add([
        timeseries("Transfers", [target(events_rate("transaction.completed"), "completed"), target(events_rate("transaction.failed"), "failed"),
                                 target(events_rate("transaction.reversed"), "refunded")]),
        timeseries("Value moved (INR / min)", [target(
            'sum by (event_type) (rate(payverse_events_amount_inr_sum{event_type=~"transaction.completed|wallet.money-added|wallet.money-withdrawn|bill.payment-completed"}[$__rate_interval])) * 60',
            "{{event_type}}")], "currencyINR"),
        timeseries("Bills and wallet", [target(events_rate("bill.payment-completed"), "bill paid"), target(events_rate("bill.payment-failed"), "bill failed"),
                                        target(events_rate("wallet.money-added"), "money added"), target(events_rate("wallet.money-withdrawn"), "withdrawn")]),
    ]).add([
        timeseries("Transfer size p50 / p95 (INR)", [
            target('histogram_quantile(0.5, sum by (le) (rate(payverse_events_amount_inr_bucket{event_type="transaction.completed"}[$__rate_interval])))', "p50"),
            target('histogram_quantile(0.95, sum by (le) (rate(payverse_events_amount_inr_bucket{event_type="transaction.completed"}[$__rate_interval])))', "p95")],
            "currencyINR"),
        timeseries("All domain events / min", [target('sum by (event_type) (rate(payverse_events_total[$__rate_interval])) * 60', "{{event_type}}")],
                   stack=True),
    ])
    b.row("Risk, rewards and notifications").add([
        timeseries("Risk decisions / min", [target('sum by (decision) (rate(payverse_risk_decisions_total[$__rate_interval])) * 60', "{{decision}}")],
                   stack=True),
        timeseries("Risk rules triggered / min", [target('sum by (rule) (rate(payverse_risk_rule_hits_total[$__rate_interval])) * 60', "{{rule}}")]),
        timeseries("Rewards / min", [target(events_rate("rewards.scratch-card-issued"), "scratch cards issued"),
                                     target(events_rate("rewards.cashback-granted"), "cashback credited")]),
        timeseries("Notifications / min", [target('sum by (channel, outcome) (rate(payverse_notifications_total[$__rate_interval])) * 60',
                                                  "{{channel}} {{outcome}}")]),
    ])
    return b


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for board in (services_board(), business_board()):
        path = os.path.join(OUT, board.uid + ".json")
        with open(path, "w") as f:
            json.dump(board.json(), f, indent=2)
            f.write("\n")
        print("wrote", os.path.relpath(path))
