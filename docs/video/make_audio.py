"""Hebrew narration for the video: one clip per scene (Microsoft neural voice via edge-tts),
then a single narration track where every scene lasts exactly as long as its clip + a pause.
Usage: python make_audio.py <out_dir>   →  durations.json, narration.wav
"""
import asyncio
import json
import os
import subprocess
import sys

import edge_tts

HERE = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
scenes = json.load(open(os.path.join(HERE, "script.json"), encoding="utf-8"))
VOICE = os.environ.get("VOICE", "he-IL-AvriNeural")


def duration(p):
    return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p]).decode().strip())


async def main():
    durs = []
    parts = []
    for i, s in enumerate(scenes):
        mp3 = os.path.join(out, f"s{i}.mp3")
        await edge_tts.Communicate(s["say"], VOICE, rate="+4%").save(mp3)
        d = duration(mp3)
        lead = 0.5 if i else 1.0
        ms = int((lead + d + 0.8) * 1000)
        if s["id"] == "outro":
            ms += 2500
        durs.append({"id": s["id"], "ms": ms})
        wav = os.path.join(out, f"s{i}.wav")
        # silence before, the clip, silence until the scene ends
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", mp3, "-af",
                        f"adelay={int(lead * 1000)}|{int(lead * 1000)},apad", "-t", f"{ms / 1000:.3f}",
                        "-ar", "44100", "-ac", "2", wav], check=True)
        parts.append(wav)
        print(s["id"], round(d, 1), "s")
    lst = os.path.join(out, "list.txt")
    with open(lst, "w") as f:
        for p in parts:
            f.write(f"file '{p}'\n")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy",
                    os.path.join(out, "narration.wav")], check=True)
    json.dump(durs, open(os.path.join(out, "durations.json"), "w"))
    print("total", sum(d["ms"] for d in durs) / 1000, "s")


asyncio.run(main())
