#!/usr/bin/env python3
"""Thin CLI over the orchestrator REST API for the /sdlc-run skill.

Does NOT do node work — the orchestrator's `agent` executor does. This only starts a run, relays
the human approval gates, and exports evidence. stdlib only.

  orch.py start   --feature qr-endpoint [--by <user>] [--workflow sdlc-autonomous]
  orch.py poll    <runId>
  orch.py approve <runId> <nodeId> --by <user> [--artifact k=v ...] [--rationale "..."]
  orch.py reject  <runId> <nodeId> --by <user> --reason "..."
  orch.py evidence <runId> --out docs/scenario-runs/004-autonomous-agent.json
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8081"
TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}


def _req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit("HTTP %s on %s %s: %s" % (e.code, method, path, e.read().decode()[:400]))
    except urllib.error.URLError as e:
        sys.exit("cannot reach orchestrator at %s (%s). Start it with "
                 "ORCHESTRATOR_EXECUTOR_MODE=agent mvn spring-boot:run" % (BASE, e.reason))


def cmd_start(a):
    run = _req("POST", "/runs", {
        "workflowDefinitionId": a.workflow,
        "autonomous": True,
        "createdBy": a.by,
        "initialContext": {"feature": a.feature},
    })
    print(run["id"])


def _summary(run):
    gates = [n for n in run["nodes"] if n["status"] == "AWAITING_APPROVAL"]
    line = "run %s  status=%s" % (run["id"], run["status"])
    for n in run["nodes"]:
        line += "\n  %-18s %s" % (n["nodeId"], n["status"])
    if gates:
        line += "\nAWAITING_APPROVAL: " + ", ".join(n["nodeId"] for n in gates)
    return line


def cmd_poll(a):
    run = _req("GET", "/runs/" + a.runId)
    print(_summary(run))
    if run["status"] in TERMINAL:
        print("\n-> terminal; run: orch.py evidence %s --out <path>" % a.runId)


def cmd_approve(a):
    artifacts = {}
    for kv in a.artifact or []:
        k, _, v = kv.partition("=")
        artifacts[k] = v
    run = _req("POST", "/runs/%s/nodes/%s/approve" % (a.runId, a.nodeId),
               {"approver": a.by, "rationale": a.rationale or "approved via /sdlc-run",
                "artifacts": artifacts})
    print(_summary(run))


def cmd_reject(a):
    run = _req("POST", "/runs/%s/nodes/%s/reject" % (a.runId, a.nodeId),
               {"approver": a.by, "rationale": a.reason})
    print(_summary(run))


def cmd_evidence(a):
    run = _req("GET", "/runs/" + a.runId)
    audit = _req("GET", "/runs/%s/audit" % a.runId)
    metrics = _req("GET", "/runs/%s/metrics" % a.runId)
    doc = {
        "scenario": a.runId,
        "workflowDefinitionId": run["workflowDefinitionId"],
        "status": run["status"],
        "createdBy": run["createdBy"],
        "startedAt": run.get("startedAt"),
        "completedAt": run.get("completedAt"),
        "context": run["context"],
        "nodes": run["nodes"],
        "metrics": metrics,
        "audit": audit,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
    print("wrote %s (%d audit events, status %s)" % (a.out, len(audit), run["status"]))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("start")
    s.add_argument("--feature", required=True)
    s.add_argument("--by", default="sdlc-run")
    s.add_argument("--workflow", default="sdlc-autonomous")
    s.set_defaults(fn=cmd_start)

    s = sub.add_parser("poll")
    s.add_argument("runId")
    s.set_defaults(fn=cmd_poll)

    s = sub.add_parser("approve")
    s.add_argument("runId")
    s.add_argument("nodeId")
    s.add_argument("--by", required=True)
    s.add_argument("--artifact", action="append")
    s.add_argument("--rationale")
    s.set_defaults(fn=cmd_approve)

    s = sub.add_parser("reject")
    s.add_argument("runId")
    s.add_argument("nodeId")
    s.add_argument("--by", required=True)
    s.add_argument("--reason", required=True)
    s.set_defaults(fn=cmd_reject)

    s = sub.add_parser("evidence")
    s.add_argument("runId")
    s.add_argument("--out", required=True)
    s.set_defaults(fn=cmd_evidence)

    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
