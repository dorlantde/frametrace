# 视频检索过程与对照评测

工作台的“定位证据”支持 KEYWORD、VECTOR、HYBRID 三种检索策略。“检索过程”展示问题改写、关键词、OCR 关键词、候选时间范围、原文、得分与向量来源。向量来源包括 QDRANT、LOCAL、UNAVAILABLE、NOT_REQUESTED。降级原因单独展示，相关性得分用于排序。

分析详情会记录检索轨迹及最后一次选入模型上下文的原始片段。五分钟以内的首次分析直接使用原始上下文，此时可以查看选入片段。已有历史 trace 缺少这些字段时，界面兼容显示已有内容。

## 接口与排序

新增 `GET /analysis/evidence-search-details?id=...&query=...&strategy=HYBRID`，返回统一响应中的 RetrievalReport。入口执行视频所有权检查、上下文检查及 AI 配额检查。原 `/analysis/evidence-search` 继续返回证据列表。

三种策略共用生产排序逻辑，每次先选前 3 个知识块，再对其片段排序，交互搜索最多返回 8 条。

- HYBRID 知识块得分为语义 × 0.6 + 关键词 × 0.25 + OCR × 0.15。
- KEYWORD 知识块得分为关键词 × 0.625 + OCR × 0.375。
- HYBRID 和 KEYWORD 的片段分为知识块分 × 0.55 + 片段关键词 × 0.25 + 片段 OCR × 0.20。
- VECTOR 使用知识块语义得分，块内片段按时间稳定排序。

查询阶段的关键词策略跳过向量查询。首次创建知识块仍沿用已有摘要和索引生成流程。问题改写失败时使用原始问题，向量库失败或无结果时使用已有本地向量，Embedding 失败或维度不匹配时语义分为 0。VECTOR 在缺失向量时的排序质量会下降，界面显示原因。

## 教学样例评测

样例位于 `server/src/test/resources/evaluation/retrieval-cases.json`，包含 8 个知识块和 8 道带目标时间区间的问题。文本与时间标注均为人工编写的教学数据。问题关键词固定，评测跳过在线问题改写，使用真实 Embedding 与本地余弦计算，复用生产排序方法。Qdrant 分支和降级另有自动化测试。

在已加载模型环境变量的终端中执行：

```powershell
cd server
mvn "-Dretrieval.live=true" "-Dtest=RetrievalEvaluationTest" test
```

该命令会调用已配置的向量服务并消耗其额度。默认测试会跳过这项在线评测。原始逐题报告写入 `server/target/retrieval-evaluation.json`。

2026-11-25 本机使用 `openai/text-embedding-3-small` 的一次运行结果如下。

| 策略 | 样本数 | Hit@1 | Hit@3 | MRR@8 |
| --- | --- | --- | --- | --- |
| KEYWORD | 8 | 1.0 | 1.0 | 1.0 |
| VECTOR | 8 | 1.0 | 1.0 | 1.0 |
| HYBRID | 8 | 1.0 | 1.0 | 1.0 |

Hit@K 表示前 K 个候选中至少一个与标注时间段相交的问题占比。MRR@8 对首个正确候选排名取倒数，未命中记 0，再对所有问题求平均。

这组样例用于验证评测与排序链路。样本少、主题区分明显、关键词人工给定，三种策略全部命中，尚无法据此比较优劣或推断真实视频准确率。后续应加入真实转写、相近主题干扰片段、同义表达、OCR 噪声及跨片段问题，并使用独立标注集验证。
