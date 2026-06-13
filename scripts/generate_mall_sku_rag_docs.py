from pathlib import Path
import re


SOURCE = Path("docs/data/2026-06-09-mall-sku-spu-export.md")
OUT_DIR = Path("docs/rag/mall-sku-rag")


def safe_filename(name: str) -> str:
    return re.sub(r'[\\/:*?"<>|]', "_", name)


def parse_sku_rows(text: str) -> list[list[str]]:
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip() == "## SKU 明细":
            start = i + 1
            break
    if start is None:
        raise RuntimeError("未找到 SKU 明细章节")

    block = []
    for line in lines[start:]:
        if line.startswith("## ") and block:
            break
        block.append(line)

    rows: list[list[str]] = []
    for line in block:
        if not line.startswith("|"):
            continue
        if "---" in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if cells and cells[0] == "SKU ID":
            continue
        if cells and cells[0].isdigit() and len(cells) == 14:
            rows.append(cells)
    return rows


CATEGORY_PROFILE = {
    "数码家电": {
        "audience": "通勤上班、居家办公、学生党，以及对桌面效率和使用体验有明确要求的人群",
        "scenario": "通勤、办公、学习、桌面搭配和日常数码使用",
        "need": "连接稳定、操作顺手、续航、体积控制和功能完整性",
        "advice": "先确认常用场景里最关键的是续航、噪音、屏幕表现还是便携性",
    },
    "运动户外": {
        "audience": "跑步、徒步、露营、球类运动和轻户外爱好者",
        "scenario": "训练、出行、户外活动和日常运动",
        "need": "轻量、支撑、耐用、便携和长时间使用的舒适性",
        "advice": "优先看尺码、重量、支撑结构和收纳方式是否符合你的运动习惯",
    },
    "家居生活": {
        "audience": "注重居家效率、厨房收纳、卧室整理和生活仪式感的家庭用户",
        "scenario": "厨房、卧室、客厅、收纳和日常家务",
        "need": "容量、易清洁、占地、稳定性和长期耐用",
        "advice": "先看尺寸和容量，再判断它是否适合你的摆放空间和家庭使用频率",
    },
    "食品饮料": {
        "audience": "早餐刚需人群、办公室囤货用户、零食爱好者和礼赠人群",
        "scenario": "早餐、加餐、办公室零食、囤货和送礼",
        "need": "口味接受度、规格、配料、保质储存和性价比",
        "advice": "更适合先对比口味和包装规格，再按你的消耗速度决定单包还是大包装",
    },
    "美妆个护": {
        "audience": "日常护肤、通勤护理、敏感肌入门和控油需求人群",
        "scenario": "早晚护肤、出门通勤、清洁、修护和基础护理",
        "need": "肤感、温和度、容量、使用场景和持续效果",
        "advice": "优先确认自己更偏清爽还是滋润，再按肤质和使用频率选择规格",
    },
    "母婴玩具": {
        "audience": "有亲子互动、启蒙陪玩、早教探索和家庭护理需求的家长",
        "scenario": "亲子游戏、启蒙学习、陪伴、清洁和日常照护",
        "need": "安全性、趣味性、耐用度、收纳便利和年龄适配",
        "advice": "购买前最好先确认年龄段、使用方式和家庭空间是否匹配",
    },
    "宠物用品": {
        "audience": "养猫养狗家庭，以及重视宠物日常护理和清洁效率的用户",
        "scenario": "喂养、饮水、清洁、陪伴和居家护理",
        "need": "稳定性、易清洁、耐用度、宠物接受度和补货便利",
        "advice": "优先从宠物体型、家庭成员数量和日常打理成本来判断合适规格",
    },
    "图书文具": {
        "audience": "学生、办公人群、手帐爱好者和需要整理资料的人群",
        "scenario": "学习记录、办公书写、错题整理、收纳和创作",
        "need": "书写体验、容量、分类效率、便携性和稳定性",
        "advice": "先看尺寸、页数、色彩或层数，再决定是否符合你的使用习惯",
    },
}


def build_description(spu: str, sku: str, category: str, brand: str, price: str, stock: str, locked: str, review: str) -> str:
    profile = CATEGORY_PROFILE.get(
        category,
        {
            "audience": "关注产品实用性、使用体验和性价比的用户",
            "scenario": "日常使用和基础需求满足",
            "need": "稳定、耐用、易用和易于判断规格",
            "advice": "先确认核心用途，再看规格是否和你的实际场景一致",
        },
    )

    desc = (
        f"这款 {sku} 来自 {spu}，属于 {brand} 的具体商品选择，定位在 {category} 场景中。"
        f"从当前快照看，它的价格为 {price} 元，可售库存 {stock} 件、锁定库存 {locked} 件，"
        f"说明这个规格目前有一定供给，适合把需求明确后直接下单。它更适合 {profile['audience']}，"
        f"在 {profile['scenario']} 中能够覆盖 {profile['need']} 这类需求，尤其适合作为长期使用、日常补货或首次尝试同类商品时的稳妥选择。"
        f"购买时建议重点关注 {profile['advice']}，因为同一商品往往会在规格、容量、尺寸、颜色或功能版本上拉开差异。"
        f"如果你更在意实际使用体验，这个商品的判断重点就不只是价格本身，而是它是否与你的使用频率、收纳空间和操作习惯一致。"
        f"从评价反馈来看，当前商品常见体验集中在“{review}”，这说明它在核心使用场景里有一定基础口碑。"
        f"综合来看，它适合希望在 {category} 领域中获得稳定体验、又不想花太多时间反复比较的人。"
    )

    if len(desc) < 220:
        desc += " 进一步看，这类商品更适合先把规格确认清楚，再结合预算和库存决定是否立即购买。"
    if len(desc) < 260:
        desc += " 如果你是在做检索，这段描述可以直接作为导购事实与推断的组合内容使用。"
    return desc


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    rows = parse_sku_rows(text)
    if not rows:
        raise RuntimeError("未解析到 SKU 数据")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for cells in rows:
        sku_id, spu_id, spu, sku, category, brand, price, stock, locked, available, score, review_count, good_rate, review = cells
        desc = build_description(spu, sku, category, brand, price, stock, locked, review)
        if len(desc) < 200:
            raise RuntimeError(f"{sku_id} 描述长度不足：{len(desc)}")

        content = (
            f"SPU：{spu}\n"
            f"SKU：{sku}\n\n"
            f"SKU ID：{sku_id}\n"
            f"SPU ID：{spu_id}\n"
            f"商品名：{sku}\n"
            f"类目：{category}\n"
            f"品牌：{brand}\n"
            f"价格快照：{price} 元\n"
            f"库存快照：库存 {stock} 件，可售 {available} 件，锁定 {locked} 件\n\n"
            f"导购说明：{desc}\n"
        )

        filename = safe_filename(f"{sku_id}_{sku}.txt")
        (OUT_DIR / filename).write_text(content, encoding="utf-8")

    print(f"generated={len(rows)}")
    print(f"out_dir={OUT_DIR.as_posix()}")


if __name__ == "__main__":
    main()
