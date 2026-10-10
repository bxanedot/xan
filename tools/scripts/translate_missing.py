#!/usr/bin/env python3
"""Fill missing Android strings with machine-translation drafts.

Existing translations are never replaced. Generated XML is checked for lost
format placeholders and written only after an entire locale succeeds. The APK
does not depend on this script or a translation service at runtime.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import html
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import json
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = ROOT / "mobile" / "android"
RES = ANDROID_ROOT / "app/src/main/res"
PREFERENCES = ANDROID_ROOT / "app/src/main/kotlin/app/xan/music/constants/PreferenceKeys.kt"
MARKER = re.compile(r"\[\[\[(\d+)\]\]\]")
FORMAT = re.compile(r"%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
ENDPOINT = "https://translate.googleapis.com/translate_a/single"
TRANSLATE = None
BATCH_LIMIT = 2500
LOCAL_UNSUPPORTED = {"eu", "bo", "ky", "te"}
BRAND_ONLY_KEYS = {"app_name", "discord", "spotify"}
GENERATED_MARKER = "<!-- Machine-translated draft; replace with reviewed translations through Crowdin. -->"


def languages() -> list[str]:
    source = PREFERENCES.read_text(encoding="utf-8")
    mapping = source.split("val LanguageCodeToName =", 1)[1].split("val CountryCodeToName =", 1)[0]
    return re.findall(r'"([A-Za-z0-9-]+)"\s+to\s+"', mapping)


def directory_for(tag: str) -> Path:
    special = {"pt": "values-pt-rBR", "pt-PT": "values-pt", "id": "values-in"}
    if tag in special:
        return RES / special[tag]
    parts = tag.split("-")
    if len(parts) == 1:
        return RES / f"values-{parts[0]}"
    if len(parts[1]) == 2 and parts[1].isalpha():
        return RES / f"values-{parts[0]}-r{parts[1].upper()}"
    return RES / ("values-b+" + "+".join(parts))


def source_text(element: ET.Element) -> str:
    value = "".join(element.itertext())
    return value.replace(r"\'", "'").replace(r'\"', '"').replace(r"\n", "\n")


def resources(folder: Path) -> tuple[dict[str, tuple[str, dict[str, str]]], dict[str, dict[str, str]]]:
    strings: dict[str, tuple[str, dict[str, str]]] = {}
    plurals: dict[str, dict[str, str]] = {}
    if not folder.exists():
        return strings, plurals
    for path in sorted(folder.glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for entry in root:
            name = entry.get("name")
            if not name:
                continue
            if entry.tag == "string":
                strings[name] = (source_text(entry), dict(entry.attrib))
            elif entry.tag == "plurals":
                plurals[name] = {
                    item.get("quantity", "other"): source_text(item) for item in entry if item.tag == "item"
                }
    return strings, plurals


def translate_batch(tag: str, texts: list[str], attempts: int = 4) -> list[str]:
    payload = texts[0] if len(texts) == 1 else "\n".join(f"[[[{index}]]] {value}" for index, value in enumerate(texts))
    query = urlencode({"client": "gtx", "sl": "en", "tl": tag, "dt": "t", "q": payload})
    request = Request(f"{ENDPOINT}?{query}", headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(attempts):
        try:
            with urlopen(request, timeout=40) as response:
                result = json.load(response)
            translated = "".join(part[0] for part in result[0] if part and part[0])
            if len(texts) == 1:
                values = [translated.strip()]
            else:
                markers = list(MARKER.finditer(translated))
                if len(markers) != len(texts) or [int(m.group(1)) for m in markers] != list(range(len(texts))):
                    raise ValueError("translation response lost item markers")
                values = [
                    translated[m.end() : markers[i + 1].start() if i + 1 < len(markers) else len(translated)].strip()
                    for i, m in enumerate(markers)
                ]
            for original, value in zip(texts, values):
                if Counter(FORMAT.findall(original)) != Counter(FORMAT.findall(value)):
                    raise ValueError("translation response changed format placeholders")
            return values
        except ValueError:
            if len(texts) == 1:
                raise
            midpoint = len(texts) // 2
            return translate_batch(tag, texts[:midpoint], 2) + translate_batch(tag, texts[midpoint:], 2)
        except Exception:
            if attempt == attempts - 1:
                if len(texts) == 1:
                    raise
                midpoint = len(texts) // 2
                return translate_batch(tag, texts[:midpoint], 2) + translate_batch(tag, texts[midpoint:], 2)
            time.sleep(min(2 ** attempt, 8))
    raise AssertionError("unreachable")


def batch(items: list[tuple[str, str]], limit: int | None = None) -> list[list[tuple[str, str]]]:
    limit = limit or BATCH_LIMIT
    batches: list[list[tuple[str, str]]] = []
    current: list[tuple[str, str]] = []
    size = 0
    for item in items:
        additional = len(item[1]) + 22
        if current and size + additional > limit:
            batches.append(current)
            current, size = [], 0
        current.append(item)
        size += additional
    if current:
        batches.append(current)
    return batches


def android_text(value: str) -> str:
    value = value.replace("\\", "\\\\").replace("\n", r"\n").replace("'", r"\'").replace('"', r'\"')
    return html.escape(value, quote=False)


class LocalBackend:
    def __init__(self, model_dir: Path):
        import ctranslate2
        import sentencepiece as spm

        self.sp = spm.SentencePieceProcessor()
        self.sp.load(str(model_dir / "sentencepiece.bpe.model"))
        self.model = ctranslate2.Translator(
            str(model_dir), device="cpu", compute_type="int8", inter_threads=2, intra_threads=8
        )

    def raw(self, tag: str, texts: list[str]) -> list[str]:
        code = tag.split("-")[0]
        code = {"fil": "tl", "iw": "he"}.get(code, code)
        if code in LOCAL_UNSUPPORTED:
            raise ValueError(f"local model does not support {tag}")
        source = [self.sp.encode(text, out_type=str) + ["</s>", "__en__"] for text in texts]
        results = self.model.translate_batch(
            source,
            target_prefix=[[f"__{code}__"] for _ in texts],
            beam_size=2,
            max_batch_size=24,
            max_decoding_length=256,
        )
        return [self.sp.decode(result.hypotheses[0][1:]).strip() for result in results]

    def __call__(self, tag: str, texts: list[str]) -> list[str]:
        values = self.raw(tag, texts)
        for index, (original, value) in enumerate(zip(texts, values)):
            if Counter(FORMAT.findall(original)) == Counter(FORMAT.findall(value)) and value:
                continue
            # Translate the surrounding words independently when the model drops
            # an Android format token; this retains every positional argument.
            tokens = FORMAT.findall(original)
            parts = FORMAT.split(original)
            translated_parts = self.raw(tag, [part for part in parts if part.strip()])
            translated_iter = iter(translated_parts)
            parts = [next(translated_iter) if part.strip() else part for part in parts]
            values[index] = "".join(part + (tokens[i] if i < len(tokens) else "") for i, part in enumerate(parts))
            if not values[index] or Counter(FORMAT.findall(original)) != Counter(FORMAT.findall(values[index])):
                raise ValueError("local model changed format placeholders")
        return values


class NllbBackend(LocalBackend):
    """Cover picker languages that M2M100 does not include."""

    LANGUAGE_CODES = {
        "eu": "eus_Latn",
        "bo": "bod_Tibt",
        "ky": "kir_Cyrl",
        "te": "tel_Telu",
    }

    def __init__(self, model_dir: Path):
        import ctranslate2

        import sentencepiece as spm

        self.sp = spm.SentencePieceProcessor()
        self.sp.load(str(model_dir / "sentencepiece.bpe.model"))
        self.model = ctranslate2.Translator(
            str(model_dir), device="cpu", compute_type="int8", inter_threads=1, intra_threads=8
        )

    def raw(self, tag: str, texts: list[str]) -> list[str]:
        code = self.LANGUAGE_CODES[tag.split("-")[0]]
        source = [["eng_Latn"] + self.sp.encode(text, out_type=str) + ["</s>"] for text in texts]
        results = self.model.translate_batch(
            source,
            target_prefix=[[code] for _ in texts],
            beam_size=2,
            max_batch_size=16,
            max_decoding_length=256,
        )
        return [self.sp.decode(result.hypotheses[0][1:]).strip() for result in results]


def restore_generated_brand_names(output: Path, source_strings: dict) -> None:
    if not output.exists():
        return
    document = output.read_text(encoding="utf-8")
    marker_at = document.find(GENERATED_MARKER)
    if marker_at < 0:
        return
    reviewed, generated = document[:marker_at], document[marker_at:]
    for name in BRAND_ONLY_KEYS:
        if name not in source_strings:
            continue
        value = android_text(source_strings[name][0])
        generated = re.sub(
            rf'(<string name="{re.escape(name)}"[^>]*>).*?(</string>)',
            lambda match: match.group(1) + value + match.group(2),
            generated,
            flags=re.DOTALL,
        )
    updated = reviewed + generated
    if updated != document:
        output.write_text(updated, encoding="utf-8")


def generate(tag: str, source_strings: dict, source_plurals: dict) -> str:
    if tag == "en":
        return "en: defaults already complete"
    folder = directory_for(tag)
    output = folder / "xan_strings.xml"
    restore_generated_brand_names(output, source_strings)
    existing_strings, existing_plurals = resources(folder)
    missing = [
        (name, text)
        for name, (text, attributes) in source_strings.items()
        if name not in existing_strings and attributes.get("translatable") != "false" and text.strip()
    ]
    missing_plurals = [
        (name, quantity, text)
        for name, forms in source_plurals.items()
        if name not in existing_plurals
        for quantity, text in forms.items()
    ]
    items = [(name, text) for name, text in missing]
    items += [(f"{name}::{quantity}", text) for name, quantity, text in missing_plurals]
    cache_path = ROOT / "build/localizations" / f"{tag}.json"
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    translated: dict[str, str] = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    for name, value in items:
        if name in BRAND_ONLY_KEYS:
            translated[name] = value
    pending = [(name, text) for name, text in items if name not in translated]
    chunks = batch(pending)
    for number, chunk in enumerate(chunks, 1):
        keys, texts = zip(*chunk)
        try:
            values = TRANSLATE(tag, list(texts))
        except Exception as error:
            raise RuntimeError(f"batch {number}/{len(chunks)} ({keys[0]}..{keys[-1]}): {error}") from error
        for key, value in zip(keys, values):
            translated[key] = value
        cache_path.write_text(json.dumps(translated, ensure_ascii=False), encoding="utf-8")
        if number % 5 == 0 or number == len(chunks):
            print(f"{tag}: translated {number}/{len(chunks)} batches", flush=True)
    if not missing and not missing_plurals:
        return f"{tag}: no missing resources"
    lines = [f'    {GENERATED_MARKER}']
    for name, (value, attributes) in source_strings.items():
        if name not in translated or name in existing_strings:
            continue
        translation = translated[name]
        formatted = ' formatted="false"' if attributes.get("formatted") == "false" else ""
        lines.append(f'    <string name="{name}"{formatted}>{android_text(translation)}</string>')
    for name, forms in source_plurals.items():
        if name not in existing_plurals and any(f"{name}::{form}" in translated for form in forms):
            lines.append(f'    <plurals name="{name}">')
            for form in forms:
                translation = translated.get(f"{name}::{form}", forms[form])
                lines.append(f'        <item quantity="{form}">{android_text(translation)}</item>')
            lines.append('    </plurals>')
    folder.mkdir(parents=True, exist_ok=True)
    if output.exists():
        original = output.read_text(encoding="utf-8")
        at = original.rfind("</resources>")
        if at < 0:
            raise ValueError(f"missing closing resources tag in {output}")
        updated = original[:at] + "\n".join(lines) + "\n" + original[at:]
    else:
        updated = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(lines) + "\n</resources>\n"
    output.write_text(updated, encoding="utf-8")
    ET.parse(output)
    return f"{tag}: added {len(missing)} strings and {len(missing_plurals)} plural forms"


def main() -> int:
    global TRANSLATE, BATCH_LIMIT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--languages", nargs="*", help="Picker language codes; default is every listed language")
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--backend", choices=("online", "local", "nllb"), default="online")
    parser.add_argument("--model-dir", type=Path)
    args = parser.parse_args()
    if args.backend == "local":
        TRANSLATE = LocalBackend(args.model_dir or ROOT / "build/translation-model")
        BATCH_LIMIT = 6000
    elif args.backend == "nllb":
        TRANSLATE = NllbBackend(args.model_dir or ROOT / "build/translation-model-nllb")
        BATCH_LIMIT = 6000
    else:
        TRANSLATE = translate_batch
    source_strings, source_plurals = resources(RES / "values")
    selected = args.languages or languages()
    if args.backend == "local":
        unsupported = [tag for tag in selected if tag.split("-")[0] in LOCAL_UNSUPPORTED]
        selected = [tag for tag in selected if tag not in unsupported]
        if unsupported:
            print("Local model cannot cover: " + ", ".join(unsupported), flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(generate, tag, source_strings, source_plurals): tag for tag in selected}
        failed = []
        for future in as_completed(futures):
            tag = futures[future]
            try:
                print(future.result(), flush=True)
            except Exception as error:
                print(f"{tag}: FAILED: {error}", file=sys.stderr, flush=True)
                failed.append(tag)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
