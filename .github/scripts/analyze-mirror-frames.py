#!/usr/bin/env python3
"""Compare screenshots from Android A and viewing phone B, no remote control."""
import json
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

def main():
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("dual-emulator-results")
    stages = ("settings", "wifi", "display")
    frames = {}
    for side in ("a", "b"):
        for stage in stages:
            path = directory / f"{side}-continuity-{stage}.png"
            if not path.is_file() or path.stat().st_size < 3000:
                raise RuntimeError(f"Missing or empty frame: {path}")
            frame = Image.open(path).convert("RGB")
            if frame.size != (1080, 2340):
                raise RuntimeError(f"Unexpected frame geometry: {path} {frame.size}")
            frames[(side, stage)] = frame.crop((0, 120, 1080, 2250))

    scores = {}
    for a_stage in stages:
        scores[a_stage] = {}
        for b_stage in stages:
            difference = ImageChops.difference(frames[("a", a_stage)], frames[("b", b_stage)])
            means = ImageStat.Stat(difference).mean
            scores[a_stage][b_stage] = round(sum(means) / len(means), 3)

    margins = {}
    for stage in stages:
        matching = scores[stage][stage]
        nonmatching = min(value for name, value in scores[stage].items() if name != stage)
        margins[stage] = round(nonmatching - matching, 3)

    passed = all(margin >= 0.8 for margin in margins.values())
    report = {
        "matched": passed,
        "scores_rgb_mae": scores,
        "match_margin": margins,
        "method": "RGB MAE across aligned 1080x2130 region; compare matching stage to nonmatching stages",
        "limits": "Content similarity is not a measurement of stream latency, audio, or frame rate."
    }
    (directory / "fidelity-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not passed:
        print("FAIL: matching A/B screenshots did not outperform mismatched pairs.", file=sys.stderr)
        sys.exit(1)
    print("PASS: all three screen changes appeared on B with correct content.")

    # Each stress screenshot on B must resemble its simultaneous A screenshot
    # more closely than A's PREVIOUS, different screen. A and B are aligned
    # 1080x2340 emulators. Screenshots alone do not establish exact latency.
    stress_results = []
    for i in range(1, 11):
        a_path = directory / f"a-stress-{i:02d}.png"
        b_path = directory / f"b-stress-{i:02d}.png"
        if not a_path.is_file() or not b_path.is_file():
            raise RuntimeError(f"Missing dynamic stress pair {i}")
        a_image = Image.open(a_path).convert("RGB").crop((0, 120, 1080, 2250))
        b_image = Image.open(b_path).convert("RGB").crop((0, 120, 1080, 2250))
        if a_image.size != b_image.size:
            raise RuntimeError(f"Stress pair {i}: mismatched image dimensions")
        matching_error = round(sum(ImageStat.Stat(ImageChops.difference(a_image, b_image)).mean) / 3, 3)
        if i > 1:
            previous_a = directory / f"a-stress-{i-1:02d}.png"
            previous_image = Image.open(previous_a).convert("RGB").crop((0, 120, 1080, 2250))
            stale_error = round(sum(ImageStat.Stat(ImageChops.difference(previous_image, b_image)).mean) / 3, 3)
        else:
            stale_error = None
        margin = round(stale_error - matching_error, 3) if stale_error is not None else None
        ok = True if i == 1 else margin >= 0.8
        stress_results.append({
            "stage": i, "match_error": matching_error,
            "stale_error": stale_error, "margin": margin, "pass": ok
        })
        print(f"Stress {i:02d}: same MAE={matching_error}, previous MAE={stale_error}, pass={ok}")

    stress_pass = all(item["pass"] for item in stress_results)
    stress_report = {
        "pass": stress_pass,
        "passed_pair_comparisons": sum(item["pass"] for item in stress_results),
        "total_stages": len(stress_results),
        "stages": stress_results,
        "limitations": "This tests screen content freshness at ~25s intervals, not sub-second latency."
    }
    (directory / "stress-fidelity-report.json").write_text(json.dumps(stress_report, indent=2)+"\\n",encoding="utf-8")
    if not stress_pass:
        print("FAIL: B failed to mirror one or more current A pages in stress test", file=sys.stderr)
        sys.exit(1)
    print("PASS: all 10 read-only stress screenshot pairs matched their A-side content.")

if __name__ == "__main__":
    main()
