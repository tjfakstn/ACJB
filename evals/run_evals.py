"""재검증 범위 제안(SPEC AC20) evals 러너.

백엔드 없이 evalset.jsonl의 가상 이력으로 서버와 같은 순서를 밟는다:
임베딩(Voyage) → 상위 K개 검색 → 프롬프트(src/qating/prompts/recheck_scope.md)로 Claude 호출 →
스키마 검증 → 근거 id 검사 → 결정적 지표 + judge_prompt.md 채점 → results/YYYY-MM-DD.json.

서버와 어긋나지 않게 모델 이름·차원은 backend application.yml, K는 RecheckSuggestionService.java,
프롬프트·스키마는 src/qating/에서 그대로 읽는다. 임베딩 텍스트 규칙과 근거 id 검사는 서버 코드
(EmbeddingTexts, RecheckSuggestionService.filter)를 옮긴 것이다 — 서버를 고치면 여기도 고친다.

키: 환경 변수 VOYAGE_API_KEY·ANTHROPIC_API_KEY, 없으면 backend/.env에서 그 두 줄만 읽는다. 키는 출력·저장하지 않는다.
의존성: 표준 라이브러리만.

    python3 evals/run_evals.py                 # 전체
    python3 evals/run_evals.py --case ev-001   # 한 케이스
    python3 evals/run_evals.py --no-judge      # 채점 없이 결정적 지표만
    python3 evals/run_evals.py --prompt 경로    # 고친 프롬프트 후보를 서버에 넣기 전에 비교
"""

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EVALS = ROOT / "evals"
APP_YML = ROOT / "backend/src/main/resources/application.yml"
SERVICE_JAVA = ROOT / "backend/src/main/java/com/acjb/server/service/RecheckSuggestionService.java"
PROMPT_MD = ROOT / "src/qating/prompts/recheck_scope.md"
SCHEMA_JSON = ROOT / "src/qating/schemas/RecheckSuggestion.schema.json"
JUDGE_MD = EVALS / "judge_prompt.md"
CACHE = EVALS / ".cache" / "embeddings.json"

VOYAGE_URL = "https://api.voyageai.com/v1/embeddings"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
VOYAGE_BATCH = 100

# 첫 합격 기준(evals/README.md) — 첫 실행 후 조정
THRESHOLDS = {"drop_rate_max": 0.10, "recall_min": 0.6, "grounded_min": 0.9}

# RecheckSuggestionSchema.UNSUPPORTED_KEYS와 같다 — 구조화 출력 API가 받지 않는 제약
UNSUPPORTED_KEYS = {"maxItems", "minLength", "maxLength", "minimum", "maximum", "pattern"}
ANNOTATION_KEYS = {"$schema", "title", "description"}
# RecheckPrompt.TAG와 같다
TAG = re.compile(r"</?\s*(target_issue|documents|document)\b[^>]*>", re.IGNORECASE)
JUDGE_TAG = re.compile(r"</?\s*(target_issue|documents|document|suggestions)\b[^>]*>", re.IGNORECASE)


# ---------------------------------------------------------------- 설정·키

def read_config():
    yml = APP_YML.read_text(encoding="utf-8")

    def block_value(block, key):
        m = re.search(rf"^  {block}:\n((?:    .*\n)+)", yml, re.MULTILINE)
        if not m:
            raise SystemExit(f"application.yml에서 app.{block}를 찾지 못했습니다")
        v = re.search(rf"^    {key}:\s*(\S+)", m.group(1), re.MULTILINE)
        if not v:
            raise SystemExit(f"application.yml에서 app.{block}.{key}를 찾지 못했습니다")
        return v.group(1)

    k = re.search(r"TOP_K\s*=\s*(\d+)", SERVICE_JAVA.read_text(encoding="utf-8"))
    if not k:
        raise SystemExit("RecheckSuggestionService.java에서 TOP_K를 찾지 못했습니다")
    return {
        "llm_model": block_value("recheck", "model"),
        "embedding_model": block_value("voyage", "model"),
        "dimension": int(block_value("voyage", "dimension")),
        "top_k": int(k.group(1)),
    }


def read_keys():
    keys = {name: os.environ.get(name, "") for name in ("VOYAGE_API_KEY", "ANTHROPIC_API_KEY")}
    env_file = ROOT / "backend/.env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            name, sep, value = line.partition("=")
            if sep and name.strip() in keys and not keys[name.strip()]:
                keys[name.strip()] = value.strip().strip('"').strip("'")
    missing = [n for n, v in keys.items() if not v]
    if missing:
        raise SystemExit("키가 없습니다: " + ", ".join(missing) + " (환경 변수 또는 backend/.env)")
    return keys


def prompt_between_markers(path):
    doc = path.read_text(encoding="utf-8")
    start, end = doc.find("<!-- prompt:start -->"), doc.find("<!-- prompt:end -->")
    if start < 0 or end < start:
        raise SystemExit(f"{path}에서 prompt:start/end를 찾지 못했습니다")
    return doc[start + len("<!-- prompt:start -->"):end].strip()


def api_schema(node):
    """RecheckSuggestionSchema.stripUnsupported와 같다"""
    if isinstance(node, dict):
        return {k: api_schema(v) for k, v in node.items() if k not in UNSUPPORTED_KEYS}
    return node


# ---------------------------------------------------------------- 문서 텍스트 (EmbeddingTexts와 같은 규칙)

def issue_text(i):
    return "\n".join([
        "화면: " + i["screen_location"],
        "증상: " + i["description"],
        "재현: " + " → ".join(i["repro_steps"]),
        "플랫폼: " + i["platform_scope"],
    ])


def mismatch_text(m):
    lines = []
    if m.get("screen"):
        lines.append("화면: " + m["screen"])
    if m.get("selector"):
        lines.append("요소: " + m["selector"])
    lines.append(f'{m["property"]}: 기대 {m["expected_value"]} / 실제 {m["actual_value"]}')
    return "\n".join(lines)


def failed_verification_text(v, issue):
    lines = ["화면: " + issue["screen_location"], "증상: " + issue["description"], "결과: 재검증 실패"]
    if v.get("scope"):
        lines.append("범위: " + v["scope"])
    return "\n".join(lines)


def build_documents(case):
    """서버처럼 type별 id를 매긴다. 대상 이슈는 qa_issue:1이고 검색 문서에서 빠진다. pass 재검증은 문서가 아니다."""
    issues = {"target": case["target"]}
    ref_to_id = {}
    counters = {"qa_issue": 1, "style_mismatch": 0, "verification": 0}
    for h in case["history"]:
        counters[h["type"]] += 1
        ref_to_id[h["ref"]] = f'{h["type"]}:{counters[h["type"]]}'
        if h["type"] == "qa_issue":
            issues[h["ref"]] = h
    docs = []
    for h in case["history"]:
        if h["type"] == "qa_issue":
            text = issue_text(h)
        elif h["type"] == "style_mismatch":
            text = mismatch_text(h)
        elif h["result"] == "fail":
            text = failed_verification_text(h, issues[h["issue_ref"]])
        else:
            continue
        docs.append({"id": ref_to_id[h["ref"]], "ref": h["ref"], "text": text})
    for g in case["should_suggest"] + case["should_not_suggest"]:
        for r in g["refs"]:
            if r not in ref_to_id:
                raise SystemExit(f'{case["id"]}: 정답에 없는 ref {r}')
    return docs, ref_to_id


# ---------------------------------------------------------------- HTTP

def post_json(url, headers, body, label, retries=6):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json", **headers})
        try:
            with urllib.request.urlopen(req, timeout=120) as res:
                return json.loads(res.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            # 429·529·5xx는 기다렸다 다시. Voyage 무료 등급은 분당 호출 한도가 작다
            if e.code in (429, 500, 502, 503, 529) and attempt < retries - 1:
                wait = 25 * (attempt + 1)
                print(f"  {label}: HTTP {e.code}, {wait}초 뒤 다시 시도", file=sys.stderr)
                time.sleep(wait)
                continue
            raise SystemExit(f"{label} 실패: HTTP {e.code} {detail}")
        except urllib.error.URLError as e:
            if attempt < retries - 1:
                time.sleep(10)
                continue
            raise SystemExit(f"{label} 연결 실패: {e.reason}")


# ---------------------------------------------------------------- 임베딩 (캐시)

class Embedder:
    def __init__(self, key, model, dimension):
        self.key, self.model, self.dimension = key, model, dimension
        self.cache = json.loads(CACHE.read_text(encoding="utf-8")) if CACHE.exists() else {}
        self.calls = 0

    def _key(self, text, input_type):
        return hashlib.sha256(f"{self.model}|{self.dimension}|{input_type}|{text}".encode("utf-8")).hexdigest()

    def prefetch(self, texts, input_type):
        """캐시에 없는 문장을 묶어서 한 번에 보낸다 — 호출 수를 줄여 분당 한도를 피한다"""
        todo = sorted({t for t in texts if self._key(t, input_type) not in self.cache})
        for start in range(0, len(todo), VOYAGE_BATCH):
            batch = todo[start:start + VOYAGE_BATCH]
            res = post_json(VOYAGE_URL, {"Authorization": "Bearer " + self.key},
                            {"input": batch, "model": self.model, "input_type": input_type,
                             "output_dimension": self.dimension}, "Voyage 임베딩")
            self.calls += 1
            data = res.get("data")
            if not isinstance(data, list) or len(data) != len(batch):
                raise SystemExit("Voyage 응답의 벡터 개수가 요청과 다릅니다")
            for item in data:
                vec = item.get("embedding")
                if not isinstance(vec, list) or len(vec) != self.dimension:
                    raise SystemExit("Voyage 응답 형식이 예상과 다릅니다")
                self.cache[self._key(batch[item["index"]], input_type)] = vec
        if todo:
            CACHE.parent.mkdir(parents=True, exist_ok=True)
            CACHE.write_text(json.dumps(self.cache), encoding="utf-8")

    def get(self, text, input_type):
        return self.cache[self._key(text, input_type)]


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return 0.0 if na == 0 or nb == 0 else dot / (na * nb)


# ---------------------------------------------------------------- Claude

def claude(key, model, prompt, schema, effort, label):
    body = {
        "model": model,
        "max_tokens": 8192,
        "output_config": {"format": {"type": "json_schema", "schema": schema}, "effort": effort},
        "messages": [{"role": "user", "content": prompt}],
    }
    res = post_json(ANTHROPIC_URL, {"x-api-key": key, "anthropic-version": "2023-06-01"}, body, label)
    if res.get("stop_reason") != "end_turn":
        raise RuntimeError(f'{label}: stop_reason={res.get("stop_reason")}')
    text = "".join(b.get("text", "") for b in res.get("content", []) if b.get("type") == "text")
    return json.loads(text)


def validate_suggestions(out):
    """RecheckSuggestion.schema.json 정본 검증(서버의 networknt 검증과 같은 규칙). 벗어나면 전체 실패"""
    ok = (isinstance(out, dict) and set(out) == {"suggestions"} and isinstance(out["suggestions"], list)
          and len(out["suggestions"]) <= 5)
    if ok:
        for s in out["suggestions"]:
            ok = ok and isinstance(s, dict) and set(s) == {"screen", "reason", "evidence_ids"} \
                and isinstance(s["screen"], str) and len(s["screen"]) >= 1 \
                and isinstance(s["reason"], str) and len(s["reason"]) >= 1 \
                and isinstance(s["evidence_ids"], list) and all(isinstance(x, str) for x in s["evidence_ids"])
    if not ok:
        raise RuntimeError("모델 출력이 RecheckSuggestion 스키마를 벗어났습니다")


def filter_suggestions(target, documents, out):
    """RecheckSuggestionService.filter와 같다"""
    searched = {d["id"] for d in documents}
    kept, screens, dropped = [], set(), 0
    for s in out["suggestions"]:
        screen = s["screen"].strip()
        ids = [x.strip() for x in s["evidence_ids"]]
        valid = len(ids) > 0 and all(x in searched for x in ids)
        if not valid or screen == target["screen_location"].strip() or screen in screens:
            dropped += 1
            continue
        screens.add(screen)
        kept.append({"screen": screen, "reason": s["reason"].strip(), "evidence": list(dict.fromkeys(ids))})
    return kept, dropped


JUDGE_SCHEMA = {
    "type": "object",
    "properties": {"scores": {"type": "array", "items": {
        "type": "object",
        "properties": {"index": {"type": "integer"}, "grounded": {"type": "boolean"},
                       "useful": {"type": "integer", "enum": [1, 2, 3]}, "note": {"type": "string"}},
        "required": ["index", "grounded", "useful", "note"], "additionalProperties": False}}},
    "required": ["scores"], "additionalProperties": False,
}


# ---------------------------------------------------------------- 지표

def norm(s):
    return re.sub(r"\s+", "", s)


def score_case(case, kept, ref_to_id):
    id_to_ref = {v: k for k, v in ref_to_id.items()}
    hits = []
    for g in case["should_suggest"]:
        gold_ids = {ref_to_id[r] for r in g["refs"]}
        hit = any(gold_ids & set(s["evidence"]) or norm(g["screen"]) in norm(s["screen"]) or norm(s["screen"]) in norm(g["screen"])
                  for s in kept)
        hits.append({"screen": g["screen"], "hit": hit})
    bad_ids = {ref_to_id[r] for g in case["should_not_suggest"] for r in g["refs"]}
    noise = [s["screen"] for s in kept if s["evidence"] and set(s["evidence"]) <= bad_ids]
    for s in kept:
        s["evidence_refs"] = [id_to_ref.get(x, x) for x in s["evidence"]]
    return hits, noise


# ---------------------------------------------------------------- 실행

def run_case(case, cfg, embedder, keys, prompt_tpl, judge_tpl, schema, use_judge):
    docs, ref_to_id = build_documents(case)
    result = {"id": case["id"], "pattern": case["pattern"], "documents": len(docs)}
    if not docs:
        result.update(searched=[], llm_called=False, suggestions=[], dropped=0, hits=[], noise=[], scores=[])
        return result
    target_text = issue_text(case["target"])
    q = embedder.get(target_text, "query")
    ranked = sorted(docs, key=lambda d: (-cosine(q, embedder.get(d["text"], "document")), d["id"]))
    top = ranked[:cfg["top_k"]]
    rendered_docs = "\n".join(f'<document id="{d["id"]}">\n{TAG.sub(r"[\1]", d["text"])}\n</document>' for d in top)
    prompt = prompt_tpl.replace("{{target}}", TAG.sub(r"[\1]", target_text)).replace("{{documents}}", rendered_docs)
    result["searched"] = [d["ref"] for d in top]
    result["llm_called"] = True
    try:
        out = claude(keys["ANTHROPIC_API_KEY"], cfg["llm_model"], prompt, schema, "medium", f'{case["id"]} 제안')
        validate_suggestions(out)
    except (RuntimeError, json.JSONDecodeError) as e:
        result.update(error=str(e), suggestions=[], dropped=0, hits=[], noise=[], scores=[])
        return result
    kept, dropped = filter_suggestions(case["target"], top, out)
    hits, noise = score_case(case, kept, ref_to_id)
    result.update(suggestions=kept, dropped=dropped, raw_count=len(out["suggestions"]), hits=hits, noise=noise, scores=[])
    if use_judge and kept:
        suggestions_text = json.dumps([{"index": i, "screen": s["screen"], "reason": s["reason"], "evidence": s["evidence"]}
                                       for i, s in enumerate(kept)], ensure_ascii=False, indent=1)
        jp = (judge_tpl.replace("{{target}}", JUDGE_TAG.sub(r"[\1]", target_text))
              .replace("{{documents}}", rendered_docs)
              .replace("{{suggestions}}", JUDGE_TAG.sub(r"[\1]", suggestions_text)))
        try:
            scores = claude(keys["ANTHROPIC_API_KEY"], cfg["llm_model"], jp, JUDGE_SCHEMA, "medium", f'{case["id"]} 채점')
            by_index = {s["index"]: s for s in scores["scores"]}
            result["scores"] = [by_index.get(i) for i in range(len(kept))]
        except (RuntimeError, json.JSONDecodeError) as e:
            result["judge_error"] = str(e)
    return result


def summarize(results, use_judge):
    gold = [h for r in results for h in r["hits"]]
    kept = sum(len(r["suggestions"]) for r in results)
    dropped = sum(r["dropped"] for r in results)
    scores = [s for r in results for s in r["scores"] if s]
    summary = {
        "cases": len(results),
        "errors": [r["id"] for r in results if "error" in r],
        "recall": round(sum(h["hit"] for h in gold) / len(gold), 3) if gold else None,
        "drop_rate": round(dropped / (kept + dropped), 3) if kept + dropped else 0.0,
        "noise_rate": round(sum(len(r["noise"]) for r in results) / kept, 3) if kept else 0.0,
        "grounded_rate": round(sum(s["grounded"] for s in scores) / len(scores), 3) if scores else None,
        "useful_mean": round(sum(s["useful"] for s in scores) / len(scores), 2) if scores else None,
        "empty_history_skips_llm": all(not r["llm_called"] for r in results if r["documents"] == 0),
        "suggestions_kept": kept,
        "suggestions_dropped": dropped,
    }
    checks = {
        "drop_rate": summary["drop_rate"] <= THRESHOLDS["drop_rate_max"],
        "recall": summary["recall"] is not None and summary["recall"] >= THRESHOLDS["recall_min"],
        "empty_history_skips_llm": summary["empty_history_skips_llm"],
        "no_errors": not summary["errors"],
    }
    if use_judge:
        checks["grounded_rate"] = summary["grounded_rate"] is not None and summary["grounded_rate"] >= THRESHOLDS["grounded_min"]
    summary["checks"] = checks
    summary["passed"] = all(checks.values())
    return summary


def main():
    ap = argparse.ArgumentParser(description="재검증 범위 제안 evals")
    ap.add_argument("--case", action="append", help="이 id만 실행(여러 번 가능)")
    ap.add_argument("--no-judge", action="store_true", help="채점 프롬프트를 부르지 않는다")
    ap.add_argument("--out", help="결과 파일 경로(기본 evals/results/YYYY-MM-DD.json)")
    ap.add_argument("--prompt", help="재검증 범위 제안 프롬프트 파일(기본 src/qating/prompts/recheck_scope.md)")
    args = ap.parse_args()

    cfg = read_config()
    keys = read_keys()
    cases = [json.loads(line) for line in (EVALS / "evalset.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.case:
        cases = [c for c in cases if c["id"] in set(args.case)]
        if not cases:
            raise SystemExit("해당 id의 케이스가 없습니다")
    prompt_path = Path(args.prompt).resolve() if args.prompt else PROMPT_MD
    prompt_tpl = prompt_between_markers(prompt_path)
    judge_tpl = prompt_between_markers(JUDGE_MD)
    raw_schema = json.loads(SCHEMA_JSON.read_text(encoding="utf-8"))
    schema = api_schema({k: v for k, v in raw_schema.items() if k not in ANNOTATION_KEYS})

    embedder = Embedder(keys["VOYAGE_API_KEY"], cfg["embedding_model"], cfg["dimension"])
    all_docs = [d["text"] for c in cases for d in build_documents(c)[0]]
    all_queries = [issue_text(c["target"]) for c in cases if c["history"]]
    embedder.prefetch(all_docs, "document")
    embedder.prefetch(all_queries, "query")

    results = []
    for c in cases:
        print(f'{c["id"]} ({c["pattern"]}) ...', file=sys.stderr)
        results.append(run_case(c, cfg, embedder, keys, prompt_tpl, judge_tpl, schema, not args.no_judge))

    summary = summarize(results, not args.no_judge)
    now = dt.datetime.now()
    out_path = Path(args.out) if args.out else EVALS / "results" / f"{now:%Y-%m-%d}.json"
    if not args.out and out_path.exists():
        out_path = EVALS / "results" / f"{now:%Y-%m-%d-%H%M}.json"
    out_path.write_text(json.dumps({
        "run_at": now.isoformat(timespec="seconds"),
        "config": {**cfg, "judge": not args.no_judge, "thresholds": THRESHOLDS,
                   "prompt": str(prompt_path.relative_to(ROOT)) if prompt_path.is_relative_to(ROOT) else prompt_path.name,
                   "prompt_sha256": hashlib.sha256(prompt_tpl.encode("utf-8")).hexdigest()[:12]},
        "voyage_calls": embedder.calls,
        "summary": summary,
        "cases": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    shown = out_path.relative_to(ROOT) if out_path.is_relative_to(ROOT) else out_path
    print(f"결과: {shown}", file=sys.stderr)
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
