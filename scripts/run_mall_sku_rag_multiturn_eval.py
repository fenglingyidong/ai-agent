import argparse
import json
import sys
import time
import traceback
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from scripts.mall_sku_rag_eval_lib import EvalResult
from scripts.mall_sku_rag_eval_lib import post_react
from scripts.mall_sku_rag_eval_lib import query_trace_summaries
from scripts.mall_sku_rag_eval_lib import write_trace_summary_jsonl


DEFAULT_QUESTIONS = Path("docs/evaluation/2026-06-22-mall-sku-rag-multiturn-eval.json")
DEFAULT_OUTPUT_DIR = Path("docs/evaluation/artifacts")
DEFAULT_ENDPOINT = "http://localhost:18082/api/react"


@dataclass(frozen=True)
class MultiturnTurn:
    id: str
    turn: int
    type: str
    question: str
    expected_answer_points: list[str]


@dataclass(frozen=True)
class MultiturnConversation:
    id: str
    title: str
    focus: str
    turns: list[MultiturnTurn]
    expected_tool_chain: list[dict]
    deductions: list[str]


@dataclass(frozen=True)
class MultiturnSuite:
    source: str
    title: str
    conversations: list[MultiturnConversation]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run mall SKU RAG multi-turn evaluations.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run", help="Run selected multi-turn eval conversations.")
    run_parser.add_argument("--ids", default="", help="Comma-separated conversation ids, for example M01,M05.")
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
    if args.command == "run":
        return run_eval(args, post_func=post_func, trace_func=trace_func)
    return 1


def run_eval(args, post_func=post_react, trace_func=query_trace_summaries) -> int:
    run_id = args.run_id or time.strftime("api-%Y%m%d-%H%M%S-multiturn")
    suite = load_multiturn_suite(args.questions)
    selected = _select_conversations(suite.conversations, args.ids)
    args.output_dir.mkdir(parents=True, exist_ok=True)

    stdout_file = args.output_dir / f"{run_id}-multiturn-run.stdout.log"
    stderr_file = args.output_dir / f"{run_id}-multiturn-run.stderr.log"
    results_file = args.output_dir / f"{run_id}-mall-sku-rag-multiturn-results.json"
    trace_file = args.output_dir / f"{run_id}-langfuse-trace-summary.jsonl"

    with stdout_file.open("w", encoding="utf-8") as stdout, stderr_file.open("w", encoding="utf-8") as stderr:
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result_conversations, session_ids, duration = _run_conversations(
                selected=selected,
                run_id=run_id,
                endpoint=args.endpoint,
                username=args.username,
                password=args.password,
                model_id=args.model_id,
                web_search_enabled=args.web_search,
                post_func=post_func,
            )

            payload = {
                "runId": run_id,
                "source": suite.source,
                "questions": args.questions.as_posix(),
                "endpoint": args.endpoint,
                "modelId": args.model_id,
                "webSearchEnabled": args.web_search,
                "durationSeconds": round(duration, 3),
                "conversations": result_conversations,
            }
            results_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"results={results_file.as_posix()}")

            traces = []
            if args.trace_wait_seconds > 0:
                time.sleep(args.trace_wait_seconds)
            try:
                traces = trace_func(session_ids)
            except Exception as ex:
                print(f"trace_error={ex}")
                traceback.print_exc(file=stderr)
            write_trace_summary_jsonl(traces, trace_file)
            print(f"traceSummary={trace_file.as_posix()}")
            print(f"durationSeconds={duration:.3f}")

    print(f"runId={run_id}")
    print(f"stdout={stdout_file.as_posix()}")
    print(f"stderr={stderr_file.as_posix()}")
    print(f"results={results_file.as_posix()}")
    print(f"traceSummary={trace_file.as_posix()}")
    return 0


def load_multiturn_suite(path: Path) -> MultiturnSuite:
    data = json.loads(path.read_text(encoding="utf-8"))
    return MultiturnSuite(
        source=str(data.get("source", "")),
        title=str(data.get("title", "")),
        conversations=[
            MultiturnConversation(
                id=str(item["id"]),
                title=str(item.get("title", "")),
                focus=str(item.get("focus", "")),
                turns=[
                    MultiturnTurn(
                        id=str(turn.get("id", f"{item['id']}-T{turn.get('turn', index + 1)}")),
                        turn=int(turn.get("turn", index + 1)),
                        type=str(turn.get("type", "chat")),
                        question=str(turn.get("question", "")),
                        expected_answer_points=_as_string_list(turn.get("expectedAnswerPoints", [])),
                    )
                    for index, turn in enumerate(item.get("turns", []))
                ],
                expected_tool_chain=list(item.get("expectedToolChain", [])),
                deductions=_as_string_list(item.get("deductions", [])),
            )
            for item in data.get("conversations", [])
        ],
    )


def _run_conversations(
    selected: list[MultiturnConversation],
    run_id: str,
    endpoint: str,
    username: str,
    password: str,
    model_id: str,
    web_search_enabled: bool,
    post_func,
) -> tuple[list[dict], list[str], float]:
    result_conversations: list[dict] = []
    session_ids: list[str] = []
    started = time.perf_counter()

    print(f"runId={run_id}")
    print(f"endpoint={endpoint}")
    print(f"modelId={model_id}")
    print(f"conversationCount={len(selected)}")

    for conversation in selected:
        session_id = f"{run_id}-{conversation.id}"
        session_ids.append(session_id)
        print(f"START {conversation.id} session={session_id}")
        result_turns = []
        for turn in conversation.turns:
            if turn.type != "chat":
                print(f"TURN {conversation.id}.{turn.turn} skip type={turn.type}")
                result_turns.append(_skipped_turn_result_dict(turn, session_id))
                continue
            print(f"TURN {conversation.id}.{turn.turn} request")
            result = post_func(
                endpoint=endpoint,
                username=username,
                password=password,
                message=turn.question,
                session_id=session_id,
                model_id=model_id,
                web_search_enabled=web_search_enabled,
                question_id=turn.id,
            )
            print(
                f"TURN {conversation.id}.{turn.turn} status={result.status} "
                f"seconds={result.duration_seconds:.3f} answerChars={len(result.answer or '')}"
            )
            result_turns.append(_turn_result_dict(turn, result))
        result_conversations.append(
            {
                "id": conversation.id,
                "title": conversation.title,
                "focus": conversation.focus,
                "sessionId": session_id,
                "expectedToolChain": conversation.expected_tool_chain,
                "deductions": conversation.deductions,
                "turns": result_turns,
            }
        )

    return result_conversations, session_ids, time.perf_counter() - started


def _turn_result_dict(turn: MultiturnTurn, result: EvalResult) -> dict:
    data = result.to_dict()
    data["turn"] = turn.turn
    data["type"] = turn.type
    data["expectedAnswerPoints"] = turn.expected_answer_points
    return data


def _skipped_turn_result_dict(turn: MultiturnTurn, session_id: str) -> dict:
    return {
        "id": turn.id,
        "question": turn.question,
        "sessionId": session_id,
        "status": "SKIPPED",
        "durationSeconds": 0,
        "answer": "",
        "error": "ui_action is not sent to /api/react",
        "turn": turn.turn,
        "type": turn.type,
        "expectedAnswerPoints": turn.expected_answer_points,
    }


def _select_conversations(
    conversations: list[MultiturnConversation],
    ids_text: str,
) -> list[MultiturnConversation]:
    if not ids_text.strip():
        return conversations
    requested = [item.strip() for item in ids_text.split(",") if item.strip()]
    by_id = {conversation.id: conversation for conversation in conversations}
    missing = [conversation_id for conversation_id in requested if conversation_id not in by_id]
    if missing:
        available = ", ".join(sorted(by_id))
        raise SystemExit(f"unknown conversation ids: {', '.join(missing)}; available: {available}")
    return [by_id[conversation_id] for conversation_id in requested]


def _as_string_list(value) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


if __name__ == "__main__":
    raise SystemExit(main())
