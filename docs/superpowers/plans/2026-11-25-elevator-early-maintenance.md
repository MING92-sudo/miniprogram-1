# 电梯「提前保养」手动派单 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让维保经理/主管/平台管理员在管理端对某台未到期电梯手动触发「提前保养」，生成一张进区域池的待接单工单。

**Architecture:** 复用后端已有的 `POST /elevators/{id}/dispatch`（`DispatchService.dispatchNow`，不看到期日），把它从 `CONSOLE_WRITE` 兜底改归 `order:dispatch` 权限，并在管理端电梯台账与电梯档案两处把已存在但未接线的 `dispatchElevator(id)` 挂到按钮上。零新业务逻辑。

**Tech Stack:** Java 17 / Spring Boot（后端）；Vue 3 + Vite 自建组件库（管理端）；Vitest（前端源码断言测试）。

**Spec:** `docs/superpowers/specs/2026-11-25-elevator-early-maintenance-design.md`

## Global Constraints

- 后端统一 `ApiResponse`；权限以后端强校验为准，前端 `PermButton` 隐藏只是体验优化（AGENTS §5.3）。
- 管理端不引新 UI 框架，用自建组件库 `admin/src/ui/`（AGENTS §5.3）。
- 管理端提交前必跑 `npm run lint && npm test && npm run build`（Windows esbuild `Access is denied` → `TMP/TEMP` 指向 `admin/.tmp-build`）；后端 `mvn test`。
- 提交信息一行中文、动词开头（AGENTS §7）。
- 新码权限 `order:dispatch` 后端经 `MultiRoleGate` 校验 `PermissionMatrix.state`（MAINT_MGR + MAINT_SUP + PLATFORM_ADMIN 经 392-394 行 PA 全码 Y 兜底）。

## Review Focus

1. **INACTIVE 电梯不应显示「提前保养」入口**（后端 `dispatchNow` 拒绝停用梯）——Task 2/3 源码断言 `row.status !== 'INACTIVE'` 的 `v-if`。
2. **无 `order:dispatch` 权限的角色看不到按钮**——前端 `PermButton code="order:dispatch"`，后端 `@RequirePerm` 兜底 403；Task 1 反射测试断言注解值。
3. **电梯已有未完成工单 → 后端 422 文案**——已有 `DispatchServiceTest.dispatchNowPublishesToPool` 与 `ElevatorInactiveTest` 覆盖服务行为；前端仅 toast 透出错误，无需新增测试。
4. **PLATFORM_ADMIN 经 PA 全码 bypass 仍可派单**——Task 1 反射测试断言 `ORDER_DISPATCH`（该码 PA 亦持有），PA 兜底由既有 `PermissionMatrix` 静态块保证。

---

### Task 1: 后端 — `dispatchNow` 归 `order:dispatch`

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/ElevatorController.java`（`dispatchNow`，约 127-130 行；顶部 import）
- Test: `backend/src/test/java/com/cqwlw/maintenance/controller/ElevatorControllerPermTest.java`（新建）

**Interfaces:**
- Consumes: 无（本计划首个任务）。
- Produces: `ElevatorController.dispatchNow(String id)` 带 `@RequirePerm(PermissionMatrix.ORDER_DISPATCH)`；后续前端调用 `POST /elevators/{id}/dispatch` 时后端按 `order:dispatch` 校验。

- [ ] **Step 1: 写失败测试**

新建 `ElevatorControllerPermTest.java`：

```java
package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ElevatorControllerPermTest {
    @Test
    void dispatchNowRequiresOrderDispatch() throws Exception {
        RequirePerm perm = ElevatorController.class
                .getMethod("dispatchNow", String.class).getAnnotation(RequirePerm.class);
        assertNotNull(perm, "dispatchNow 缺少 @RequirePerm（手动派单应归 order:dispatch 而非 CONSOLE_WRITE 兜底）");
        assertEquals(PermissionMatrix.ORDER_DISPATCH, perm.value());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f backend/pom.xml -Dtest=ElevatorControllerPermTest test`
Expected: FAIL，`perm` 为 null（当前 `dispatchNow` 无 `@RequirePerm`）。

- [ ] **Step 3: 给 `dispatchNow` 加 `@RequirePerm(PermissionMatrix.ORDER_DISPATCH)`**

在 `ElevatorController.java` 顶部补两个 import（`com.cqwlw.maintenance.auth.PermissionMatrix`、`com.cqwlw.maintenance.auth.RequirePerm`），并在 `@PostMapping("/elevators/{id}/dispatch")` 上方加一行 `@RequirePerm(PermissionMatrix.ORDER_DISPATCH)`。方法体不变。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -f backend/pom.xml -Dtest=ElevatorControllerPermTest test`
Expected: PASS。

- [ ] **Step 5: 跑后端全量测试并提交**

Run: `mvn -f backend/pom.xml test`
Expected: 全绿（947 个 @Test 基线，含既有 `DispatchServiceTest`/`ElevatorInactiveTest`）。

```bash
git add backend/src/main/java/com/cqwlw/maintenance/controller/ElevatorController.java backend/src/test/java/com/cqwlw/maintenance/controller/ElevatorControllerPermTest.java
git commit -m "手动派单归 order:dispatch 权限并补注解测试"
```

---

### Task 2: 管理端电梯台账加「提前保养」入口

**Files:**
- Modify: `admin/src/views/ElevatorsView.vue`（行内操作 + 确认 Modal + script）
- Test: `admin/src/views/ElevatorsView.early-dispatch.test.js`（新建，源码断言）

**Interfaces:**
- Consumes: `dispatchElevator(id)`（`admin/src/api/archive.js`，已存在，调用 `http.post('/elevators/' + id + '/dispatch', {})`）。
- Produces: `ElevatorsView` 行内「提前保养」按钮 + 确认弹窗 + `askEarlyDispatch(row)` / `submitEarlyDispatch()`。

- [ ] **Step 1: 写失败测试**

新建 `ElevatorsView.early-dispatch.test.js`（沿用 `ElevatorsView.confirm.test.js` 的 `readFileSync` 源码断言风格）：

```js
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

describe('电梯台账提前保养入口', () => {
  const src = readFileSync('src/views/ElevatorsView.vue', 'utf8')

  it('行内操作含 order:dispatch 权限的提前保养按钮', () => {
    expect(src).toContain('label="提前保养"')
    expect(src).toContain('code="order:dispatch"')
  })

  it('非停用电梯才显示入口（INACTIVE 不可派单）', () => {
    expect(src).toContain("row.status !== 'INACTIVE'")
  })

  it('确认后调用 dispatchElevator 手动派单', () => {
    expect(src).toContain('dispatchElevator(current.value.id)')
  })

  it('带二次确认弹窗', () => {
    expect(src).toContain('require-confirm')
    expect(src).toContain('confirm-label="确认提前保养"')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd admin && npm test -- ElevatorsView.early-dispatch.test.js`
Expected: FAIL，断言字符串不存在。

- [ ] **Step 3: 实现按钮 + 确认弹窗 + 提交**

在 `ElevatorsView.vue`：
1. import 行（约 246 行）把 `dispatchElevator` 加进 `from '../api/archive'` 的花括号列表。
2. 行内操作模板（约 27 行「编辑」之后）加：
   `<PermButton v-if="row.status !== 'INACTIVE'" code="order:dispatch" label="提前保养" variant="ghost" size="xs" icon="bolt" @click="askEarlyDispatch(row)" />`
3. 删除确认 Modal 附近加：
   `<Modal :open="showEarlyDispatch" title="提前保养" :sub="current.elevatorCode" icon="bolt" require-confirm note="order:dispatch" confirm-text="我已知悉，确认提前保养" confirm-label="确认提前保养" @close="showEarlyDispatch = false" @confirm="submitEarlyDispatch"><RuleNote icon="calendar">确认提前保养 {{ current.elevatorCode }}？将生成一张待接单（半月保）工单进入区域池，维保员到场签到抢单后开始保养。</RuleNote></Modal>`
4. script 段加：
   ```js
   const showEarlyDispatch = ref(false)
   function askEarlyDispatch(row) { current.value = row; showEarlyDispatch.value = true }
   async function submitEarlyDispatch() {
     try { await dispatchElevator(current.value.id); toast.success('已生成提前保养工单 · 进入区域池待接单'); showEarlyDispatch.value = false; await load() }
     catch (e) { toast.error(e.message || '提前保养派单失败') }
   }
   ```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd admin && npm test -- ElevatorsView.early-dispatch.test.js`
Expected: PASS。

- [ ] **Step 5: lint 并提交**

Run: `cd admin && npm run lint`
Expected: 通过。

```bash
git add admin/src/views/ElevatorsView.vue admin/src/views/ElevatorsView.early-dispatch.test.js
git commit -m "电梯台账增加提前保养入口"
```

---

### Task 3: 管理端电梯档案加「提前保养」入口

**Files:**
- Modify: `admin/src/views/ElevatorDetailView.vue`（头部操作 + 确认 Modal + script）
- Test: `admin/src/views/ElevatorDetailView.early-dispatch.test.js`（新建，源码断言）

**Interfaces:**
- Consumes: `dispatchElevator(id)`（同 Task 2）。
- Produces: `ElevatorDetailView` 头部「提前保养」按钮 + `earlyOpen` / `submitEarlyDispatch()`。

- [ ] **Step 1: 写失败测试**

新建 `ElevatorDetailView.early-dispatch.test.js`：

```js
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

describe('电梯档案提前保养入口', () => {
  const src = readFileSync('src/views/ElevatorDetailView.vue', 'utf8')

  it('头部操作含 order:dispatch 权限的提前保养按钮', () => {
    expect(src).toContain('label="提前保养"')
    expect(src).toContain('code="order:dispatch"')
  })

  it('非停用电梯才显示入口', () => {
    expect(src).toContain("row.status !== 'INACTIVE'")
  })

  it('确认后调用 dispatchElevator 手动派单', () => {
    expect(src).toContain('dispatchElevator(row.value.id)')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd admin && npm test -- ElevatorDetailView.early-dispatch.test.js`
Expected: FAIL。

- [ ] **Step 3: 实现按钮 + 确认弹窗 + 提交**

在 `ElevatorDetailView.vue`：
1. 顶部 script import 段加 `import { dispatchElevator } from '../api/archive'` 和 `import Modal from '../ui/Modal.vue'`（该文件当前未 import Modal）。
2. `ViewHead` 的 `#actions` 模板（约 6 行「修改定位」之后）加：
   `<PermButton v-if="row.status !== 'INACTIVE'" code="order:dispatch" label="提前保养" variant="primary" icon="bolt" @click="earlyOpen = true" />`
3. `ElevatorGeoModal` 附近加确认 Modal（`title="提前保养"`、`note="order:dispatch"`、`require-confirm`、`confirm-label="确认提前保养"`、`@confirm="submitEarlyDispatch"`、`@close="earlyOpen = false"`；正文用纯文本说明将生成待接单半月保工单进入区域池）。
4. script 段加：
   ```js
   const earlyOpen = ref(false)
   async function submitEarlyDispatch() {
     try { await dispatchElevator(row.value.id); toast.success('已生成提前保养工单 · 进入区域池待接单'); earlyOpen.value = false }
     catch (e) { toast.error(e.message || '提前保养派单失败') }
   }
   ```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd admin && npm test -- ElevatorDetailView.early-dispatch.test.js`
Expected: PASS。

- [ ] **Step 5: lint + build + 提交**

Run: `cd admin && npm run lint && npm test && npm run build`
Expected: 全通过（Windows esbuild `Access is denied` → `TMP/TEMP` 指向 `admin/.tmp-build`）。

```bash
git add admin/src/views/ElevatorDetailView.vue admin/src/views/ElevatorDetailView.early-dispatch.test.js
git commit -m "电梯档案增加提前保养入口"
```
