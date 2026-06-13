import json
import re
import sys
from base64 import b64encode
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


RAG_DIR = Path("docs/rag/mall-sku-rag")
IMPORT_URL = "http://localhost:18082/api/rag/documents/import"
AUTH_HEADER = "Basic " + b64encode(b"alice:demo123").decode("ascii")


def field(text: str, label: str) -> str:
    pattern = re.compile(rf"^{re.escape(label)}：(.+)$", re.MULTILINE)
    match = pattern.search(text)
    return match.group(1).strip() if match else ""


def parse_doc(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    sku_id = field(text, "SKU ID")
    spu_id = field(text, "SPU ID")
    title = field(text, "商品名") or path.stem
    category = field(text, "类目")
    brand = field(text, "品牌")
    price_text = field(text, "价格快照").replace("元", "").strip()
    stock_text = field(text, "库存快照")
    stock_match = re.search(r"库存\s+(\d+)\s+件", stock_text)

    return {
        "sourceId": f"mall-sku-{sku_id}",
        "title": title,
        "content": text,
        "productId": spu_id,
        "skuId": sku_id,
        "category": category,
        "brand": brand,
        "price": str(Decimal(price_text)) if price_text else None,
        "stock": int(stock_match.group(1)) if stock_match else None,
        "attributes": {
            "sourceFile": path.name,
            "spuId": spu_id,
        },
    }


def post_json(payload: dict) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(
        IMPORT_URL,
        data=data,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": AUTH_HEADER,
        },
        method="POST",
    )
    with urlopen(request, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    files = sorted(RAG_DIR.glob("*.txt"))
    if not files:
        print(f"No txt files found in {RAG_DIR}", file=sys.stderr)
        return 1

    imported = 0
    parent_count = 0
    child_count = 0
    failures: list[str] = []

    for path in files:
        payload = parse_doc(path)
        try:
            response = post_json(payload)
        except HTTPError as ex:
            body = ex.read().decode("utf-8", errors="replace")
            failures.append(f"{path.name}: HTTP {ex.code} {body}")
            continue
        except URLError as ex:
            failures.append(f"{path.name}: {ex.reason}")
            continue

        imported += 1
        parent_count += int(response.get("parentCount", 0))
        child_count += int(response.get("childCount", 0))
        print(
            f"imported {path.name}: parents={response.get('parentCount')} "
            f"children={response.get('childCount')}"
        )

    print(
        json.dumps(
            {
                "files": len(files),
                "imported": imported,
                "failed": len(failures),
                "parents": parent_count,
                "children": child_count,
            },
            ensure_ascii=False,
        )
    )

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
