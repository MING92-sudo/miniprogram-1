<template>
  <div class="page">
    <div class="page-head"><h1>角色权限矩阵（只读）</h1></div>
    <div class="matrix-wrap">
      <table class="matrix">
        <thead>
          <tr><th class="stick">权限</th><th v-for="r in roles" :key="r">{{ roleLabel(r) }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.code">
            <td class="stick code">{{ permLabel(row.code) }}</td>
            <td v-for="r in roles" :key="r" :class="'st-' + row.states[r]" :title="triStateLabel(row.states[r])">{{ triStateSymbol(row.states[r]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="hint">✓=可操作 ◐=只读/需审批 ✕=禁止。写入口二期开放。</p>
  </div>
</template>
<script setup>
// M3 角色权限矩阵：13 角色 × 86 码三态，只读（role:assign 可见）；中文显示
import { ref, onMounted } from 'vue'
import { rolesMatrix } from '../api/ledger'
import { canonicalRoles } from '../ui/permission-v2'
import { roleLabel, permLabel, triStateLabel, triStateSymbol } from '../ui/labels'
const rows = ref([])
const roles = ref([...canonicalRoles()])
onMounted(async () => { rows.value = await rolesMatrix() })
</script>
<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h1 { font-size: 20px; }
.matrix-wrap { overflow: auto; border: 1px solid var(--line); border-radius: 10px; }
.matrix { border-collapse: collapse; width: 100%; font-size: 12px; }
.matrix th, .matrix td { padding: 6px 10px; border-bottom: 1px solid var(--line); text-align: center; }
.matrix th { background: var(--panel-2); color: var(--muted); font-family: var(--font-mono); font-size: 11px; }
.matrix .stick { position: sticky; left: 0; background: var(--panel); text-align: left; }
.matrix .code { font-family: var(--font-mono); color: var(--text); }
.st-Y { color: var(--ok); }
.st-C { color: var(--amber); }
.st-- { color: var(--dim); }
.hint { color: var(--dim); font-size: 12px; margin-top: 10px; }
</style>