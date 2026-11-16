<template>
  <div class="state forbid">
    <div :class="['code-big', { sig: unknown }]">{{ unknown ? '404' : '403' }}</div>
    <h2>{{ unknown ? '页面不存在或无权限' : '无权限访问' }}<template v-if="name"> · {{ name }}</template></h2>
    <p>{{ unknown ? '该路由不在管理端白名单内，或当前角色无权访问。' : '当前角色不在该页面的允许范围内。前端隐藏不作为安全边界，后端每个接口都会校验。' }}</p>
    <div v-if="codes.length" class="perm-need mt16">
      <div class="pn-row"><span class="dim">所需权限码</span><b class="mono c-amber">{{ codes.join(' / ') }}</b></div>
      <div class="pn-row"><span class="dim">允许角色</span><b>{{ allowedRoles.length ? allowedRoles.map(roleLabel).join('、') : '无' }}</b></div>
    </div>
    <div class="perm-need mt8">
      <div class="pn-row"><span class="dim">我的当前角色</span><b>{{ roleLabel(currentRole) }}（{{ currentRole }}）</b></div>
    </div>
    <div class="st-acts">
      <button class="btn btn-primary" type="button" @click="$router.push('/dashboard')">
        <svg class="ic" aria-hidden="true"><use href="#i-gauge" /></svg>返回工作台
      </button>
    </div>
  </div>
</template>
<script setup>
// 403/404 态（原型 forbidState 对齐）：大号状态码 + 所需权限码 + 允许角色 + 当前角色
import { computed } from 'vue'
import { roleLabel } from '../labels'
const props = defineProps({
  code: { type: String, default: '' }, codes: { type: Array, default: () => [] },
  allowedRoles: { type: Array, default: () => [] }, currentRole: { type: String, default: '' },
  name: { type: String, default: '' }, unknown: Boolean
})
const codes = computed(() => (props.codes.length ? props.codes : props.code ? [props.code] : []))
</script>