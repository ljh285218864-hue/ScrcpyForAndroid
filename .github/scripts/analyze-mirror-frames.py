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

if __name__ == "__main__":
    main()
