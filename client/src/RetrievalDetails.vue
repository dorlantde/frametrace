<script setup>
defineProps({ report: { type: Object, required: true } })
const score = value => Number.isFinite(value) ? value.toFixed(3) : '—'
const time = value => `${(value / 1000).toFixed(1)} 秒`
</script>

<template>
  <details class="retrieval-details">
    <summary>检索过程 · {{ report.strategy }}</summary>
    <p>语义查询 {{ report.intent?.semanticQuery }}</p>
    <p>关键词 {{ report.intent?.keywords?.join('、') || '无' }}</p>
    <p>画面文字关键词 {{ report.intent?.visualKeywords?.join('、') || '无' }}</p>
    <p v-for="reason in report.fallbacks" :key="reason" class="retrieval-warning">{{ reason }}</p>
    <p>语义分数来自所在知识块，关键词和 OCR 分数来自片段。排序分数用于比较相关性。</p>
    <div class="retrieval-table">
      <table>
        <thead><tr><th>片段</th><th>排序分数</th><th>知识块得分</th><th>语义</th><th>关键词</th><th>OCR</th><th>向量来源</th></tr></thead>
        <tbody><tr v-for="candidate in report.candidates" :key="`${candidate.evidence.startMs}-${candidate.evidence.endMs}`">
          <td>{{ time(candidate.evidence.startMs) }} – {{ time(candidate.evidence.endMs) }}</td>
          <td>{{ score(candidate.score) }}</td><td>{{ score(candidate.chunkScore) }}</td><td>{{ score(candidate.semanticScore) }}</td>
          <td>{{ score(candidate.keywordScore) }}</td><td>{{ score(candidate.ocrScore) }}</td><td>{{ candidate.vectorSource }}</td>
        </tr></tbody>
      </table>
    </div>
    <details v-for="candidate in report.candidates" :key="`text-${candidate.evidence.startMs}`">
      <summary>{{ time(candidate.evidence.startMs) }} 原始证据</summary>
      <p>{{ candidate.evidence.transcript || '无语音文字' }}</p>
      <p>{{ candidate.evidence.ocrTexts?.join('\n') || '无画面文字' }}</p>
    </details>
  </details>
</template>

<style scoped>
.retrieval-details { margin: 12px 0; padding: 12px; border: 1px solid var(--border-tech); font-size: .85rem; }
.retrieval-details p { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.retrieval-details summary { cursor: pointer; }
.retrieval-warning { color: #ffb86b; }
.retrieval-table { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 6px; text-align: left; border-bottom: 1px solid var(--border-tech); white-space: nowrap; }
</style>
