#!/usr/bin/env python3
import argparse
import csv
import json
from pathlib import Path


def ns_to_ops_ms(ns: float) -> float:
    return 1_000_000.0 / ns


def load_estimate(path: Path, key: str) -> dict:
    data = json.loads(path.read_text())
    return data[key]


def parse_rows(bench_id: str) -> int:
    marker = "rows_"
    idx = bench_id.rfind(marker)
    if idx < 0:
        return -1
    s = bench_id[idx + len(marker) :]
    try:
        return int(s)
    except ValueError:
        return -1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, help="criterion root directory")
    parser.add_argument("--out", required=True, help="output directory")
    args = parser.parse_args()

    root = Path(args.root)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    rows = []
    for est in sorted(root.glob("**/new/estimates.json")):
        rel = est.relative_to(root)
        parts = rel.parts
        if len(parts) < 3:
            continue
        bench_group = parts[0]
        bench_case = parts[1]
        bench_id = f"{bench_group}/{bench_case}"

        slope = load_estimate(est, "slope")
        time_low_ns = float(slope["confidence_interval"]["lower_bound"])
        time_mid_ns = float(slope["point_estimate"])
        time_high_ns = float(slope["confidence_interval"]["upper_bound"])

        rows.append(
            {
                "bench": bench_group,
                "case": bench_case,
                "rows": parse_rows(bench_case),
                "time_low_ns": time_low_ns,
                "time_mid_ns": time_mid_ns,
                "time_high_ns": time_high_ns,
                "ops_ms_low": ns_to_ops_ms(time_high_ns),
                "ops_ms_mid": ns_to_ops_ms(time_mid_ns),
                "ops_ms_high": ns_to_ops_ms(time_low_ns),
                "criterion_id": bench_id,
            }
        )

    json_path = out / "summary.json"
    csv_path = out / "summary.csv"

    json_path.write_text(json.dumps(rows, indent=2))

    fieldnames = [
        "bench",
        "case",
        "rows",
        "time_low_ns",
        "time_mid_ns",
        "time_high_ns",
        "ops_ms_low",
        "ops_ms_mid",
        "ops_ms_high",
        "criterion_id",
    ]
    with csv_path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
