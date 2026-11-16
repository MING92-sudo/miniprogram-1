<template>
  <div class="view" style="max-width:1200px">
    <ViewHead title="设计规范与自检" sub="token / 字体 / 语义色 / 权限范式 / 图标 / 12 条不变量">
      <template #actions>
        <button class="btn btn-ghost" type="button" @click="$router.push('/dashboard')">
          <svg class="ic" aria-hidden="true"><use href="#i-gauge" /></svg>返回工作台
        </button>
      </template>
      <template #meta>
        <div class="meta-strip">
          <span class="ms count"><svg class="ic ic-sm" aria-hidden="true"><use href="#i-check" /></svg>自检 <b>{{ passN }} / 12 通过</b></span>
        </div>
      </template>
    </ViewHead>
    <div class="card mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-palette" /></svg>设计 token（与小程序原型同源）</h3></div>
      <div class="card-body">
        <div class="swatches">
          <div v-for="t in TOKENS" :key="t[0]" class="sw">
            <i :style="{ background: t[1] }" />
            <span class="sw-n">{{ t[0] }}<div class="f11 dim">{{ t[2] }}</div></span>
            <span class="sw-v">{{ t[1] }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="grid2 mt16">
      <div class="card">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>字体层级</h3></div>
        <div class="card-body">
          <div class="type-row"><span style="font-size:20px;font-weight:600">标题 H1 · 20px</span><span class="m">--fs-h2</span></div>
          <div class="type-row"><span style="font-size:16px;font-weight:600">标题 H2 · 16px</span><span class="m">--fs-h</span></div>
          <div class="type-row"><span style="font-size:13px">正文 Body · 13px</span><span class="m">--fs-body</span></div>
          <div class="type-row"><span style="font-size:12px">次级 Small · 12px</span><span class="m">--fs-sm</span></div>
          <div class="type-row"><span class="label-mono">标签 LABEL · 11px</span><span class="m">--fs-label</span></div>
          <div class="type-row"><span class="mono num">1234567890 · 数字</span><span class="m">--font-mono</span></div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-shield" /></svg>权限范式</h3></div>
        <div class="card-body">
          <div class="spec-list">
            <div><b>前端只隐藏，后端必校验。</b>导航/按钮按权限码渲染（Y 可用 / C 置灰+tooltip / – 隐藏），但每个接口在服务端二次校验。</div>
            <div><b>角色只从服务端读取。</b>登录页不选角色；后台下发 roles + permissions + dataScope，绝不接受客户端传入角色。</div>
            <div><b>脱敏在服务端。</b>原型在 mock 层按角色出值演示，生产环境字段脱敏由服务端完成。</div>
            <div><b>数据范围逐条判断。</b>SELF/TEAM/PROJECT/ORG/ORG_FLAT/ALL；越权单据 id → 403，不泄露是否存在。</div>
            <div><b>写操作即时留痕。</b>派单/审批/停用/修订/导出/容缺/作废/移装均追加审计，不可删除。</div>
          </div>
        </div>
      </div>
    </div>
    <div class="card mt16">
      <div class="card-head">
        <h3><svg class="ic" aria-hidden="true"><use href="#i-check" /></svg>数据自检（12 条不变量）</h3>
        <span :class="['ch-tag', 'tag', passN === 12 ? 'tag-ok' : 'tag-signal']">{{ passN }} / 12</span>
      </div>
      <div class="card-body">
        <div class="chk-list">
          <div v-for="c in checks" :key="c.id" :class="['chk-row', c.pass ? 'pass' : 'fail']">
            <svg class="ic" aria-hidden="true"><use :href="c.pass ? '#i-check' : '#i-x'" /></svg>
            <span class="cr-n">{{ c.id }}</span><span class="cr-t">{{ c.name }}</span><span class="cr-d">{{ c.detail }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="card mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-sitemap" /></svg>导航可见数推导（含设计规范）</h3></div>
      <div class="card-body">
        <div class="tbl-wrap">
          <table class="tbl">
            <thead><tr><th>角色</th><th class="num">矩阵推导可见视图</th><th class="num">spec §11#3 标注</th><th>口径</th></tr></thead>
            <tbody>
              <tr v-for="r in navRows" :key="r.role">
                <td>{{ roleName(r.role) }}</td>
                <td class="num"><b class="c-amber">{{ r.n }}</b></td>
                <td class="num dim">{{ stated[r.role] }}</td>
                <td class="f12 dim">{{ r.n === stated[r.role] ? '一致' : '以权威矩阵为准（差异已记录）' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <RuleNote class="mt12" icon="check">spec §11#3 已对齐权威权限矩阵推导值「20/16/16/9/16」（含设计规范与二期 4 视图）。导航可见数按 roles §4.1-4.6 矩阵推导。</RuleNote>
      </div>
    </div>
    <div class="card mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-image" /></svg>图标库（内联 SVG sprite · {{ ICON_NAMES.length }} 枚）</h3></div>
      <div class="card-body">
        <div class="ico-gallery">
          <div v-for="n in ICON_NAMES" :key="n" class="ico-cell">
            <svg class="ic ic-lg" aria-hidden="true"><use :href="'#i-' + n" /></svg><span>i-{{ n }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="card mt16">
      <div class="card-head"><h3><svg class="ic" aria-hidden="true"><use href="#i-info" /></svg>冲突与偏离记录</h3></div>
      <div class="card-body">
        <div class="spec-list">
          <div><b>C1</b> 两份文档 TabBar 不一致 → Web 不用 TabBar，按导航矩阵。</div>
          <div><b>C2</b> routes 仅定义小程序 pkg-admin → admin-spec 补齐管理端 IA。</div>
          <div><b>C7</b> 约 42 码无小程序归属 → 25 码补 Web 落地，其余标注小程序侧。</div>
          <div><b>D2</b> 移交「11 态」实为 11 条转移、去重 10 态。</div>
          <div><b>D3</b> 平台对 plan:view / order:view_pool 运维只读。</div>
          <div><b>D4</b> 系统字体栈 + 内联 SVG，离线零外部请求。</div>
          <div><b>D5</b> 后台账号+密码登录为推导（文档仅 wx.login）。</div>
          <div><b>D-A1</b> plan:generate 落地为手动派单/延期，不做批量生成（派单仅定时任务驱动）。</div>
          <div><b>合规偏离</b> 原型同步页「批量重报」不实现——AGENTS §2.4 禁批量重试入口，仅逐条人工重报。</div>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
// 设计规范与自检（原型 Views.spec 对齐；D-A3 开发/验收工具，仅 DEV 路由）
import { computed } from 'vue'
import { M } from '../../ui/prototype-mock'
import { ICON_NAMES } from '../../ui/icons'
import ViewHead from '../../ui/ViewHead.vue'
import RuleNote from '../../ui/RuleNote.vue'
const TOKENS = [['--ink', '#0A0F12', '页面底'], ['--ink-2', '#0D1418', '侧栏底'], ['--panel', '#131C21', '卡面'], ['--panel-2', '#172228', '表头/悬浮'], ['--line', '#26343B', '1px 分隔'], ['--line-2', '#2E3E46', '强分隔'], ['--text', '#E8EEF0', '正文'], ['--muted', '#A3B4BB', '次级'], ['--dim', '#7E929B', '标签 ≥11px'], ['--amber', '#FFB020', '主操作/进行中'], ['--amber-soft', '#FFC85C', '黄字'], ['--signal', '#FF4B2B', '急修/危险/超时'], ['--signal-soft', '#FF8F7A', '红字'], ['--ok', '#2FC48D', '合格/已闭环'], ['--ok-soft', '#5FE0B2', '绿字'], ['--paper', '#EDE5D3', '单据底'], ['--paper-ink', '#22201A', '单据墨色'], ['--paper-line', '#C6BA9F', '单据分隔']]
const checks = M.selfCheck()
const passN = computed(() => checks.filter(c => c.pass).length)
const stated = { PLATFORM_ADMIN: 20, MAINT_MGR: 16, MAINT_SUP: 16, INST_MGR: 9, CLIENT_ADMIN: 16 }
const navRows = M.SWITCH_ROLES.map(s => ({ role: s.role, n: M.visibleNav(s.role).length }))
function roleName(rc) { const r = M.ROLES.find(x => x.code === rc); return r ? r.name : rc }
</script>