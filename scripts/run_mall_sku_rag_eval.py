import argparse
import json
import sys
import time
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from scripts.mall_sku_rag_eval_lib import EvalResult
from scripts.mall_sku_rag_eval_lib import export_questions_file
from scripts.mall_sku_rag_eval_lib import post_react
from scripts.mall_sku_rag_eval_lib import query_trace_summaries
from scripts.mall_sku_rag_eval_lib import QuestionSpec
from scripts.mall_sku_rag_eval_lib import score_run
from scripts.mall_sku_rag_eval_lib import write_trace_summary_jsonl


DEFAULT_SOURCE = Path("docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md")
DEFAULT_QUESTIONS = Path("docs/evaluation/artifacts/mall-sku-rag-guide-eval-questions.json")
DEFAULT_OUTPUT_DIR = Path("docs/evaluation/artifacts")
DEFAULT_ENDPOINT = "http://localhost:18082/api/react"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run mall SKU RAG evaluations.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export-questions", help="Export eval markdown questions to JSON.")
    export_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    export_parser.add_argument("--output", type=Path, default=DEFAULT_QUESTIONS)

    run_parser = subparsers.add_parser("run", help="Run selected eval questions.")
    run_parser.add_argument("--ids", required=True, help="Comma-separated question ids, for example Q08,Q25.")
    run_parser.add_argument("--questions", type=Path, default=DEFAULT_QUESTIONS)
    run_parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    run_parser.add_argument("--run-id", default="")
    run_parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    run_parser.add_argument("--username", default="alice")
    run_parser.add_argument("--password", default="demo123")
    run_parser.add_argument("--model-id", default="qwen")
    run_parser.add_argument("--web-search", action="store_true")
    run_parser.add_argument("--trace-wait-seconds", type=float, default=3.0)

    return parser


def main(argv: list[str] | None = None, post_func=post_react, trace_func=query_trace_summaries) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "export-questions":
        count = export_questions_file(args.source, args.output)
        print(f"questions={count}")
        print(f"output={args.output.as_posix()}")
        return 0
    if args.command == "run":
        return run_eval(args, post_func=post_func, trace_func=trace_func)
    return 1


def run_eval(args, post_func=post_react, trace_func=query_trace_summaries) -> int:
    run_id = args.run_id or time.strftime("api-%Y%m%d-%H%M%S")
    questions = _load_questions(args.questions)
    selected = _select_questions(questions, args.ids)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    results: list[EvalResult] = []
    started = time.perf_counter()
    for question in selected:
        session_id = f"{run_id}-{question.id}"
        results.append(
            post_func(
                endpoint=args.endpoint,
                username=args.username,
                password=args.password,
                message=question.question,
                session_id=session_id,
                model_id=args.model_id,
                web_search_enabled=args.web_search,
                question_id=question.id,
            )
        )

    duration = time.perf_counter() - started
    results_file = args.output_dir / f"{run_id}-mall-sku-rag-eval-results.json"
    results_payload = {
        "runId": run_id,
        "questionIds": [question.id for question in selected],
        "endpoint": args.endpoint,
        "modelId": args.model_id,
        "webSearchEnabled": args.web_search,
        "durationSeconds": round(duration, 3),
        "results": [result.to_dict() for result in results],
    }
    results_file.write_text(json.dumps(results_payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.trace_wait_seconds > 0:
        time.sleep(args.trace_wait_seconds)
    try:
        traces = trace_func([result.session_id for result in results])
    except Exception as ex:
        traces = []
        print(f"trace_error={ex}")

    trace_file = args.output_dir / f"{run_id}-langfuse-trace-summary.jsonl"
    write_trace_summary_jsonl(traces, trace_file)

    scored = score_run(run_id, selected, results, traces)
    scored["sourceResults"] = results_file.as_posix()
    scored["langfuseTraceSummary"] = trace_file.as_posix()
    scored_file = args.output_dir / f"{run_id}-mall-sku-rag-eval-scored.json"
    scored_file.write_text(json.dumps(scored, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"runId={run_id}")
    print(f"results={results_file.as_posix()}")
    print(f"traceSummary={trace_file.as_posix()}")
    print(f"scored={scored_file.as_posix()}")
    return 0


def _load_questions(path: Path) -> list[QuestionSpec]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [
        QuestionSpec(
            id=item["id"],
            title=item.get("title", ""),
            category=item.get("category", ""),
            difficulty=item.get("difficulty", ""),
            question=item.get("question", ""),
            expected_hits=item.get("expectedHits", []),
            answer_points=item.get("answerPoints", []),
        )
        for item in data.get("questions", [])
    ]


def _select_questions(questions: list[QuestionSpec], ids_text: str) -> list[QuestionSpec]:
    requested = [item.strip() for item in ids_text.split(",") if item.strip()]
    by_id = {question.id: question for question in questions}
    missing = [question_id for question_id in requested if question_id not in by_id]
    if missing:
        available = ", ".join(sorted(by_id))
        raise SystemExit(f"unknown question ids: {', '.join(missing)}; available: {available}")
    return [by_id[question_id] for question_id in requested]


if __name__ == "__main__":
    raise SystemExit(main())
