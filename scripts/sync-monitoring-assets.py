#!/usr/bin/env python3
"""Copy the monitoring assets in docs/monitoring/ to where Kubernetes needs them.

docs/monitoring/ holds the one editable copy of the alert rules and the Grafana
dashboards. Two delivery paths cannot read it:

  - Helm only reads files inside the chart, so the PrometheusRule template reads
    helm/eddi/files/eddi-alerts.yml.
  - kustomize refuses files outside the kustomization root, so the monitoring
    component's configMapGenerator reads k8s/overlays/monitoring/eddi-alerts.yml
    and k8s/overlays/monitoring/dashboards/*.json.

This script writes those copies. The alert rules are copied byte for byte. The
dashboards are re-serialised without whitespace: a ConfigMap applied with
client-side `kubectl apply` must fit the 256 KiB last-applied-configuration
annotation. The Full Metrics Reference no longer fits even minified, so its
Kubernetes copy is SPLIT into two dashboards (see `split_dashboard`), each in a
ConfigMap of its own. docs/monitoring/ keeps the single dashboard; Compose and a
manual import use that.

Usage (from the repository root):

  python scripts/sync-monitoring-assets.py           write the copies
  python scripts/sync-monitoring-assets.py --check   exit 1 if any copy is stale

DeploymentManifestsTest runs the same comparison, so a stale copy fails the build.
"""

import json
import sys
from pathlib import Path

SOURCE = Path("docs/monitoring")
ALERTS = "eddi-alerts.yml"
DASHBOARDS = ("eddi-operations-dashboard.json", "eddi-grafana-dashboard.json")
# Copied to Kubernetes in two parts; see split_dashboard.
SPLIT_DASHBOARD = "eddi-full-metrics-dashboard.json"

K8S = Path("k8s/overlays/monitoring")
HELM_FILES = Path("helm/eddi/files")


def serialise(data: dict) -> bytes:
    return (json.dumps(data, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def minified(path: Path) -> bytes:
    return serialise(json.loads(path.read_text(encoding="utf-8")))


def part_name(index: int) -> str:
    return SPLIT_DASHBOARD.replace(".json", f"-{index}.json")


def split_dashboard(data: dict) -> list:
    """Split a dashboard into two at the row boundary that best balances their size.

    Only a top-level row starts a part, so a row and the panels it holds stay
    together. Part 1 keeps the source uid, so links and bookmarks to the
    dashboard still land; part 2 gets `<uid>-2`. Each part links to the other.
    Everything else is copied unchanged, so concatenating the parts' panels gives
    back the source's panels exactly — DeploymentManifestsTest checks that.
    """
    panels = data["panels"]
    cuts = [i for i, p in enumerate(panels) if p.get("type") == "row" and i > 0]
    sizes = [len(serialise({"p": p})) for p in panels]
    cut = min(cuts, key=lambda i: abs(sum(sizes[:i]) - sum(sizes[i:])))
    uid, title = data["uid"], data["title"]
    uids = [uid, f"{uid}-2"]
    parts = []
    for index, chunk in enumerate((panels[:cut], panels[cut:]), start=1):
        part = dict(data)
        part["panels"] = chunk
        part["uid"] = uids[index - 1]
        part["title"] = f"{title} ({index}/2)"
        other = 2 if index == 1 else 1
        part["links"] = list(data.get("links") or []) + [{
            "title": f"{title} ({other}/2)",
            "type": "link",
            "url": f"/d/{uids[other - 1]}",
            "icon": "dashboard",
            "keepTime": True,
            "includeVars": True,
        }]
        parts.append(part)
    return parts


def targets() -> dict:
    alerts = (SOURCE / ALERTS).read_bytes()
    wanted = {
        HELM_FILES / ALERTS: alerts,
        K8S / ALERTS: alerts,
    }
    for name in DASHBOARDS:
        wanted[K8S / "dashboards" / name] = minified(SOURCE / name)
    source = json.loads((SOURCE / SPLIT_DASHBOARD).read_text(encoding="utf-8"))
    for index, part in enumerate(split_dashboard(source), start=1):
        wanted[K8S / "dashboards" / part_name(index)] = serialise(part)
    return wanted


def lf(content: bytes) -> bytes:
    return content.replace(b"\r\n", b"\n")


def main(argv: list) -> int:
    if not (SOURCE / ALERTS).is_file():
        print("Run from the repository root.", file=sys.stderr)
        return 2
    check = "--check" in argv
    stale = []
    for path, content in targets().items():
        current = path.read_bytes() if path.is_file() else None
        # Line endings are not content: a Windows checkout with core.autocrlf has
        # CRLF in both the source and the copies.
        if current is not None and lf(current) == lf(content):
            continue
        if check:
            stale.append(str(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            print(f"wrote {path}")
    if stale:
        print("Stale monitoring copies (run python scripts/sync-monitoring-assets.py):\n  " + "\n  ".join(stale),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
