<template>
  <div class="ui-scope selfcheck">
    <h1>数据自检（开发态）</h1>
    <ul>
      <li v-for="r in results" :key="r.id" :class="r.ok ? 'ok' : 'fail'">
        <span class="num">#{{ r.id }}</span> {{ r.detail }} — {{ r.ok ? '通过' : '失败' }}
      </li>
    </ul>
  </div>
</template>
<script setup>
// M2 基准集九不变量自检（D-A3，场景 16）
import { mockData, checkInvariants } from '../../ui/mock-data'
const results = checkInvariants(mockData)
</script>
<style scoped>
.selfcheck { padding: 24px; }
.selfcheck ul { list-style: none; padding: 0; }
.selfcheck li { padding: 8px 12px; border: 1px solid var(--line); border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.selfcheck .ok { color: var(--ok); }
.selfcheck .fail { color: var(--signal); }
</style>