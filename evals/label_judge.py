"""judge 검증용 사람 라벨 입력기 (EVAL_DESIGN.md 3절).

라벨 시트(evals/results/<RUN_ID>.label_sheet.jsonl — judge 판정이 빠진 시트)를 한 줄씩 보여 주고,
사람이 기준마다 통과(y)/실패(n)를 매기면 evals/judge_labels.jsonl에 한 줄씩 더한다.
judge의 판정은 보여 주지 않는다 — 사람이 judge 출력을 보기 전에 매겨야 일치율이 judge의 신뢰도를 잰다.

    python3 evals/label_judge.py 2026-11-10T2303 --labeler "설만수"
    python3 evals/run_evals.py --judge-agreement 2026-11-10T2303     # 일치율 → history.csv judge_agreement

기준
  s1 (parse)   symptom이 원문에 없는 원인·조건을 덧붙이지 않고 요약됐는가
  c1 (recheck) 제안 이유의 사실이 근거 문서에서 실제로 확인되는가
  c2 (recheck) 이번 수정의 영향을 받을 화면이라 같이 볼 이유가 있는가
중간에 멈춰도(q) 이미 매긴 케이스는 저장되고, 다시 실행하면 남은 케이스부터 묻는다.
"""
import argparse
import json
import sys
from pathlib import Path

EVALS = Path(__file__).resolve().parent
LABELS = EVALS / "judge_labels.jsonl"

CRITERIA = {
    "s1": "symptom이 원문에 없는 원인·조건을 덧붙이지 않았는가",
    "c1": "이유의 사실이 근거 문서에서 확인되는가",
    "c2": "이 수정의 영향을 받을 화면이라 같이 볼 이유가 있는가",
}


def ask(criterion):
    while True:
        a = input(f"  {criterion} {CRITERIA[criterion]} [y/n/q] ").strip().lower()
        if a in ("y", "n"):
            return a == "y"
        if a == "q":
            raise KeyboardInterrupt


def show(row):
    print("\n" + "=" * 80)
    print(f"[{row['id']}]")
    if "query" in row:
        print("원문:", row["query"])
        print("파싱 결과:", json.dumps(row["parsed"], ensure_ascii=False, indent=1))
    else:
        print("대상 이슈:", json.dumps(row["target"], ensure_ascii=False, indent=1))
        print("검색된 문서:")
        for d in row["searched"]:
            print("  -", json.dumps(d, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser(description="judge 검증용 사람 라벨 입력")
    ap.add_argument("run_id")
    ap.add_argument("--labeler", required=True, help="라벨을 매긴 사람(여럿이 합의했으면 '설만수, 오단비 합의')")
    args = ap.parse_args()

    sheet = EVALS / "results" / f"{args.run_id}.label_sheet.jsonl"
    if not sheet.exists():
        raise SystemExit(f"{sheet}가 없습니다 — 먼저 python3 evals/run_evals.py --label-sheet {args.run_id}")
    rows = [json.loads(l) for l in sheet.read_text(encoding="utf-8").splitlines() if l.strip()]
    done = set()
    if LABELS.exists():
        done = {(l["run_id"], l["id"]) for l in map(json.loads, filter(str.strip, LABELS.read_text(encoding="utf-8").splitlines()))}
    todo = [r for r in rows if (args.run_id, r["id"]) not in done]
    print(f"라벨 시트 {len(rows)}줄 중 남은 {len(todo)}줄. y=통과, n=실패, q=멈춤(매긴 것은 저장됨)")

    saved = 0
    try:
        for row in todo:
            show(row)
            out = {"run_id": args.run_id, "id": row["id"], "labeler": args.labeler}
            if "query" in row:
                out["s1"] = ask("s1")
            else:
                items = []
                for i, sug in enumerate(row["suggestions"]):
                    print(f"\n  제안 {i}: {json.dumps(sug, ensure_ascii=False)}")
                    items.append({"index": i, "c1": ask("c1"), "c2": ask("c2")})
                out["items"] = items
            with LABELS.open("a", encoding="utf-8") as f:
                f.write(json.dumps(out, ensure_ascii=False) + "\n")
            saved += 1
    except (KeyboardInterrupt, EOFError):
        print("\n멈춤.")
    print(f"이번에 {saved}줄 저장: {LABELS}")
    if saved and len(todo) == saved:
        print(f"모두 매겼습니다. 다음: python3 evals/run_evals.py --judge-agreement {args.run_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
