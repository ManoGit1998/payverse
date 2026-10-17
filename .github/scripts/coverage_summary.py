#!/usr/bin/env python3
"""Writes a Markdown table of JaCoCo line coverage per module and layer to $GITHUB_STEP_SUMMARY (or stdout).
The build itself enforces the gate (>= 80% on service, entity and model packages); this only reports it."""
import csv
import glob
import os

rows = []
for report in sorted(glob.glob("**/target/site/jacoco/jacoco.csv", recursive=True)):
    module = report.split("/target/")[0]
    layers = {}
    for r in csv.DictReader(open(report)):
        layer = r["PACKAGE"].split(".")[-1] if r["PACKAGE"].count(".") >= 3 else "(root)"
        missed, covered = layers.get(layer, (0, 0))
        layers[layer] = (missed + int(r["LINE_MISSED"]), covered + int(r["LINE_COVERED"]))
    for layer in ("service", "entity", "model"):
        if layer in layers:
            missed, covered = layers[layer]
            if missed + covered:
                rows.append((module, layer, 100.0 * covered / (missed + covered)))

lines = ["## Line coverage (gated layers)", "", "| Module | Layer | Coverage |", "|---|---|---|"]
lines += [f"| {m} | {l} | {'✅' if c >= 80 else '❌'} {c:.1f}% |" for m, l, c in rows]
out = os.environ.get("GITHUB_STEP_SUMMARY")
with open(out, "a") if out else open(os.devnull, "w") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
