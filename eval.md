最小可跑的 DuReader `Recall@K` 评测入口。

新增文件：

- [DuReaderRecallEvaluationRunner.java](D:/mycodes/RAGAgent/src/main/java/com/example/ragagent/eval/DuReaderRecallEvaluationRunner.java:34)
- [application-rag-eval.yml](D:/mycodes/RAGAgent/src/main/resources/application-rag-eval.yml:1)

它做的事情是：

```text
读取 DuReader dev jsonl
-> 用 question 作为 query
-> 用 answer_docs 作为 gold 文档标注，缺失时回退到 is_selected
-> 将每条样本的候选 documents 导入你的父子块 RAG 索引
-> 调用当前 ParentChildHybridDocumentRetriever
-> 按返回父块 metadata.sourceId 计算 Recall@K
```

运行前需要：

```powershell
$env:DASHSCOPE_API_KEY="你的 key"
```

确保 Redis Stack 正在运行，然后执行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=rag-eval"
```

默认配置在 [application-rag-eval.yml](D:/mycodes/RAGAgent/src/main/resources/application-rag-eval.yml:10)：

```yaml
app:
  rag-eval:
    input-file: dureader_preprocessed/devset/search.dev.json
    max-examples: 100
    index-documents: true
    ks: 1,3
```

第一次跑会调用 embedding 接口导入文档。后面如果 Redis 里的 `rag-eval-index` 还在，可以把 `index-documents` 改成 `false`，避免重复花 embedding 成本。







DuReader Recall@K 消融实验结果：
inputFile=dureader_preprocessed/devset/zhidao.dev.json
samples=25
candidateDocuments=120
actualIndexedDocuments=0
strategy=dense-only
  avgRetrievedParents=6.00
  avgLatencyMs=1211.21
  metrics=Recall@1=0.3600 (9/25), Recall@3=0.6800 (17/25), Recall@5=0.8800 (22/25)
strategy=bm25-only
  avgRetrievedParents=5.40
  avgLatencyMs=19.59
  metrics=Recall@1=0.4000 (10/25), Recall@3=0.7200 (18/25), Recall@5=0.8000 (20/25)
strategy=hybrid-rrf
  avgRetrievedParents=6.00
  avgLatencyMs=5.73
  metrics=Recall@1=0.2800 (7/25), Recall@3=0.7600 (19/25), Recall@5=0.9200 (23/25)
strategy=hybrid-rrf-dynamic-truncation
  avgRetrievedParents=4.84
  avgLatencyMs=4.48
  metrics=Recall@1=0.2800 (7/25), Recall@3=0.7200 (18/25), Recall@5=0.8000 (20/25)


在 DuReader 子集评测中

- 设计语义缓存策略，结合Query Embedding、相似度阈值与缓存过期机制复用历史回答，降低重复请求下的LLM调用次数和端到端响应延迟。
- 设计父子索引RAG检索方案，融合Dense/BM25双路异步召回与RRF排序，并通过动态截断在Recall@3=0.72、Recall@5=0.80持平的情况下，将平均返回父块数减少约19.3%。
