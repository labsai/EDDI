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
dashboards are re-serialised without whitespace: the Full Metrics Reference is
over 400 KB pretty-printed, and a ConfigMap applied with client-side
`kubectl apply` must fit the 256 KiB last-applied-configuration annotation.

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
DASHBOARDS = ("eddi-operations-dashboard.json", "eddi-full-metrics-dashboard.json", "eddi-grafana-dashboard.json")

K8S = Path("k8s/overlays/monitoring")
HELM_FILES = Path("helm/eddi/files")


def minified(path: Path) -> bytes:
    data = json.loads(path.read_text(encoding="utf-8"))
    return (json.dumps(data, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def targets() -> dict:
    alerts = (SOURCE / ALERTS).read_bytes()
    wanted = {
        HELM_FILES / ALERTS: alerts,
        K8S / ALERTS: alerts,
    }
    for name in DASHBOARDS:
        wanted[K8S / "dashboards" / name] = minified(SOURCE / name)
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
