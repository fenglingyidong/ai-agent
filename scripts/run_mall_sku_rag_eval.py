import argparse
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from scripts.mall_sku_rag_eval_lib import export_questions_file


DEFAULT_SOURCE = Path("docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md")
DEFAULT_QUESTIONS = Path("docs/evaluation/artifacts/mall-sku-rag-guide-eval-questions.json")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run mall SKU RAG evaluations.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export-questions", help="Export eval markdown questions to JSON.")
    export_parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    export_parser.add_argument("--output", type=Path, default=DEFAULT_QUESTIONS)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "export-questions":
        count = export_questions_file(args.source, args.output)
        print(f"questions={count}")
        print(f"output={args.output.as_posix()}")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
