#!/usr/bin/env python3
"""足压16点仿真数据流脚本（JSONL输出）."""

from __future__ import annotations

import argparse
import json
import random
import time
from typing import List


def clamp(v: int, low: int = 0, high: int = 100) -> int:
    return max(low, min(high, v))


def add_noise(values: List[int], noise: int) -> List[int]:
    return [clamp(v + random.randint(-noise, noise)) for v in values]


def pattern(mode: str, step: int, single_id: int) -> List[int]:
    if mode == "stand":
        base = [58, 62, 57, 60, 22, 18, 20, 24, 48, 52, 54, 50, 43, 22, 18, 15]
        return add_noise(base, 5)
    if mode == "forefoot":
        base = [25, 22, 24, 20, 18, 14, 15, 16, 70, 76, 72, 78, 74, 45, 52, 60]
        return add_noise(base, 6)
    if mode == "heel":
        base = [78, 82, 79, 75, 32, 25, 28, 30, 28, 22, 20, 24, 18, 8, 5, 3]
        return add_noise(base, 6)
    if mode == "single":
        return [90 if i == single_id - 1 else random.randint(0, 12) for i in range(16)]

    phase = step % 20
    if phase <= 4:
        base = [75, 80, 70, 78, 40, 28, 24, 20, 15, 12, 10, 15, 8, 0, 0, 0]
    elif phase <= 9:
        base = [45, 42, 48, 40, 35, 38, 36, 34, 40, 45, 42, 38, 35, 20, 15, 10]
    elif phase <= 14:
        base = [18, 15, 20, 16, 12, 14, 12, 10, 70, 75, 78, 82, 72, 50, 54, 62]
    else:
        base = [0, 0, 0, 0, 0, 0, 0, 0, 10, 14, 16, 18, 12, 8, 5, 3]
    return add_noise(base, 6)


def main() -> None:
    parser = argparse.ArgumentParser(description="16点足压仿真数据脚本")
    parser.add_argument("--device-id", default="MOCK_BLE_001")
    parser.add_argument("--mode", choices=["stand", "walk", "forefoot", "heel", "single"], default="stand")
    parser.add_argument("--hz", type=int, default=10, help="刷新频率，默认10Hz")
    parser.add_argument("--single-id", type=int, default=1, help="single模式点位(1-16)")
    parser.add_argument("--frames", type=int, default=0, help="输出帧数，0为无限")
    args = parser.parse_args()

    interval = 1.0 / max(1, args.hz)
    step = 0
    seq = 0

    while True:
        values = pattern(args.mode, step, max(1, min(16, args.single_id)))
        frame = {
            "ts": int(time.time() * 1000),
            "seq": seq,
            "deviceId": args.device_id,
            "mode": args.mode,
            "points": [{"id": i + 1, "force": values[i]} for i in range(16)],
        }
        print(json.dumps(frame, ensure_ascii=False), flush=True)

        step += 1
        seq += 1
        if args.frames > 0 and seq >= args.frames:
            break
        time.sleep(interval)


if __name__ == "__main__":
    main()
