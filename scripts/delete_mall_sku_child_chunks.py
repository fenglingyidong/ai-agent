from pymilvus import MilvusClient

client = MilvusClient(uri="http://localhost:19530", db_name="default")
collection = "product_index"

ids = []
offset = 0
limit = 1000

while True:
    rows = client.query(
        collection_name=collection,
        filter='metadata["docType"] == "rag-child"',
        output_fields=["doc_id", "metadata"],
        limit=limit,
        offset=offset,
    )

    if not rows:
        break

    for row in rows:
        metadata = row.get("metadata") or {}
        source_id = str(metadata.get("sourceId") or "")

        if source_id.startswith("mall-sku-"):
            ids.append(row["doc_id"])

    print(f"scanned offset={offset}, rows={len(rows)}, matched={len(ids)}")

    if len(rows) < limit:
        break

    offset += limit

print(f"matched mall-sku child chunks: {len(ids)}")

for i in range(0, len(ids), 200):
    batch_ids = ids[i:i + 200]
    client.delete(collection_name=collection, ids=batch_ids)
    print(f"deleted {i + len(batch_ids)} / {len(ids)}")

client.flush(collection_name=collection)

print("milvus mall-sku child chunks deleted")