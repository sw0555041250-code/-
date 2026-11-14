"""Downloads the models used by the app and makes reference values with the official Python
libraries, so the Kotlin code in core/ can be checked against them (see ModelTest.kt).

Usage: python tools/make_reference.py <models_dir>
"""
import asyncio
import os
import subprocess
import sys

import numpy as np
from huggingface_hub import hf_hub_download

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# ---------- models (the same files the app downloads) ----------
WHISPER = {
    "whisper-base": "Xenova/whisper-base",
    "whisper-small": "Xenova/whisper-small",
}
for name, repo in WHISPER.items():
    for f in ["onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx", "tokenizer.json"]:
        p = hf_hub_download(repo, f, local_dir=os.path.join(out, "_hf", name))
        dst = os.path.join(out, name, os.path.basename(f))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(dst):
            os.symlink(p, dst)
        print(name, os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")

for f in ["onnx/model_quantized.onnx", "tokenizer.json"]:
    p = hf_hub_download("Xenova/multilingual-e5-small", f, local_dir=os.path.join(out, "_hf", "e5"))
    dst = os.path.join(out, "e5", os.path.basename(f))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if not os.path.exists(dst):
        os.symlink(p, dst)
    print("e5", os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")

# ---------- whisper tokenizer reference ----------
from tokenizers import Tokenizer

sys.path.insert(0, os.path.dirname(__file__))
PROMPT = open(os.path.join(os.path.dirname(__file__), "..", "core", "src", "main", "kotlin", "com", "chdv",
                           "searchplus", "core", "Whisper.kt"), encoding="utf-8").read().split('const val PROMPT = "')[1].split('"')[0]
wt = Tokenizer.from_file(os.path.join(out, "whisper-base", "tokenizer.json"))
ids = wt.encode(" " + PROMPT, add_special_tokens=False).ids
open(os.path.join(out, "whisper_prompt_ids.txt"), "w").write(",".join(map(str, ids)))
print("prompt tokens:", len(ids))

# ---------- spoken test sentences (Microsoft Hebrew voice) ----------
CASES = [
    "מסכת ברכות דף כ״ז עמוד א׳",
    "ליקוטי שיחות חלק ג׳ פרשת ויצא",
    "גמרא בבא מציעא דף נ״ט",
    "תניא פרק ה׳",
    "מה מברכים על פרי העץ",
]
os.makedirs(os.path.join(out, "audio"), exist_ok=True)
open(os.path.join(out, "audio", "cases.txt"), "w", encoding="utf-8").write("\n".join(CASES))


async def tts():
    import edge_tts
    for i, t in enumerate(CASES):
        mp3 = os.path.join(out, "audio", f"{i}.mp3")
        await edge_tts.Communicate(t, "he-IL-AvriNeural").save(mp3)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
                        os.path.join(out, "audio", f"{i}.wav")], check=True)


asyncio.run(tts())

# ---------- log-mel reference (numpy, same recipe as openai/whisper) ----------
import wave


def read_wav(p):
    with wave.open(p) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


def mel_filters(n_mels=80, sr=16000, n_fft=400):
    def hz_to_mel(f):
        f_sp = 200.0 / 3
        mels = f / f_sp
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = np.log(6.4) / 27.0
        return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-10) / min_log_hz) / logstep, mels)

    def mel_to_hz(m):
        f_sp = 200.0 / 3
        freqs = f_sp * m
        min_log_hz = 1000.0
        min_log_mel = min_log_hz / f_sp
        logstep = np.log(6.4) / 27.0
        return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), freqs)

    fftfreqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = mel_to_hz(np.linspace(hz_to_mel(0.0), hz_to_mel(sr / 2), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    w = np.zeros((n_mels, len(fftfreqs)))
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        w[i] = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels])
    return w * enorm[:, None]


def log_mel(audio):
    n = 480000
    audio = np.pad(audio[:n], (0, max(0, n - len(audio))))
    x = np.pad(audio, 200, mode="reflect")
    win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 400)
    frames = np.stack([x[t * 160: t * 160 + 400] * win for t in range(3000)])
    spec = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    mel = mel_filters() @ spec.T
    lg = np.log10(np.maximum(mel, 1e-10))
    lg = np.maximum(lg, lg.max() - 8.0)
    return ((lg + 4.0) / 4.0).astype(np.float32)


log_mel(read_wav(os.path.join(out, "audio", "0.wav"))).astype("<f4").tofile(os.path.join(out, "mel_ref.f32"))

# ---------- e5 reference: tokens (HF tokenizers) and vectors (onnxruntime) ----------
import onnxruntime as ort

et = Tokenizer.from_file(os.path.join(out, "e5", "tokenizer.json"))
sess = ort.InferenceSession(os.path.join(out, "e5", "model_quantized.onnx"))
TEXTS = [
    "query: מה מברכים על פרי העץ",
    "passage: אמר רבי שמעון בן יוחאי, כל המתפלל צריך שיכוין את לבו לשמים",
    "passage: וַיֵּצֵא יַעֲקֹב מִבְּאֵר שָׁבַע וַיֵּלֶךְ חָרָנָה",
    "query: what is the blessing on fruit",
    "passage: ליקוטי שיחות חלק ג׳, שיחה לפרשת ויצא – ״ויצא יעקב״ (כ״ח, י)",
]
with open(os.path.join(out, "e5_ref.txt"), "w", encoding="utf-8") as f:
    for t in TEXTS:
        enc = et.encode(t)
        ids = np.array([enc.ids], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
        if "token_type_ids" in [i.name for i in sess.get_inputs()]:
            feed["token_type_ids"] = np.zeros_like(ids)
        h = sess.run(None, feed)[0][0]
        v = h.mean(axis=0)
        v = v / np.linalg.norm(v)
        f.write(t + "\t" + ",".join(map(str, enc.ids)) + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
# ---------- bge-m3 (the deep understanding model): tokens, CLS vectors ----------
try:
    for f in ["onnx/model_quantized.onnx", "tokenizer.json"]:
        p = hf_hub_download("Xenova/bge-m3", f, local_dir=os.path.join(out, "_hf", "bge"))
        dst = os.path.join(out, "bge", os.path.basename(f))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(dst):
            os.symlink(p, dst)
        print("bge", os.path.basename(f), os.path.getsize(p) // 1024 // 1024, "MB")
    bt = Tokenizer.from_file(os.path.join(out, "bge", "tokenizer.json"))
    bs = ort.InferenceSession(os.path.join(out, "bge", "model_quantized.onnx"))
    print("bge inputs", [i.name for i in bs.get_inputs()], "outputs", [o.name for o in bs.get_outputs()])
    with open(os.path.join(out, "bge_ref.txt"), "w", encoding="utf-8") as f:
        for t in ["מה מברכים על פרי העץ", "על פירות האילן הוא אומר בורא פרי העץ", "מי שנתחייב מלקות לוקה ארבעים חסר אחת"]:
            enc = bt.encode(t)
            ids = np.array([enc.ids], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
            h = bs.run(None, feed)[0][0]
            v = h[0] / np.linalg.norm(h[0])
            f.write(t + "\t" + ",".join(map(str, enc.ids)) + "\t" + ",".join(f"{x:.6f}" for x in v) + "\n")
except Exception as e:
    print("bge-m3 skipped:", e)
print("reference ready")
