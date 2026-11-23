<template>
  <div>
    <ViewHead title="移交管理" sub="安装移交 · GB/T 10060—2023 表1 74 项检查清单 / 1:1 整改 / 一梯一档（内部移交把关，不替代监督检验）">
      <template #meta><PageMeta perm="handover:view" :extra="[{ k: '移交单', v: rows.length, icon: 'exchange', cls: 'count' }]" /></template>
    </ViewHead>

    <div class="mt16">
      <DataTable :columns="cols" :rows="rows" :loading="loading" clickable
        :search-keys="['handoverNo', 'elevatorCode', 'projectName', 'initiatorName']" search-placeholder="搜索移交单号 / 设备码 / 项目 / 发起人"
        :filters="statusFilters" empty-hint="当前数据范围内没有移交单。" @row-click="openDetail">
        <template #toolbar>
          <PermButton code="handover:create" label="移交单起草" variant="primary" icon="plus" @click="openCreate" />
        </template>
        <template #cell-handoverNo="{ row }">
          <b class="mono">{{ row.handoverNo }}</b>
          <span v-if="missingDocs(row)" class="tag tag-signal" style="margin-left:6px">缺{{ missingDocs(row) }}件</span>
        </template>
        <template #cell-elevatorCode="{ row }">
          <span class="mono f12">{{ row.elevatorCode || '—' }}</span>
          <div class="cell-2">{{ row.elevatorName || '' }}</div>
        </template>
        <template #cell-createdAt="{ value }"><span class="mono f12">{{ fmtTime(value) }}</span></template>
        <template #cell-status="{ value }"><StatusTag :status="value" :label="hoStatusLabel(value)" /></template>
        <template #row-actions="{ row }">
          <button class="btn btn-ghost btn-xs" type="button" @click.stop="openDetail(row)">
            <svg class="ic" aria-hidden="true"><use href="#i-eye" /></svg><span>详情</span>
          </button>
        </template>
      </DataTable>
    </div>
    <RuleNote class="mt12">状态机：草稿 → 待安装部审核 → 资料提交 → 查验中 ⇄ 整改复验 → 待确认归档 → 已归档；作废需原因必填并留痕。缺件时「审核通过」被后端拦截；判定结论由后端冻结，前端不改写。</RuleNote>

    <!-- 详情抽屉 -->
    <Drawer :open="detailOpen" :title="detail.handoverNo || '移交单'" :sub="(detail.projectName || '') + ' · ' + (detail.elevatorCode || '')"
      :meta="{ perm: 'handover:view' }" note="按权限码渲染 · 后端 MultiRoleGate 强校验" @close="detailOpen = false">
      <template #meta>
        <StatusTag :status="detail.status" :label="hoStatusLabel(detail.status)" />
        <span v-if="detail.category" class="scope-pill">{{ detail.category }}</span>
        <span v-if="missingDocs(detail)" class="scope-pill" style="border-color:var(--signal-a32);color:var(--signal-soft)">缺 {{ missingDocs(detail) }} 件必传资料</span>
      </template>

      <div v-if="detail.id">
        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-exchange" /></svg>状态机</div>
          <Steps :states="hoSteps" :current="hoStatusLabel(detail.status)" :bad-state="detail.status === 'VOIDED' ? '已作废' : ''" />
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-building" /></svg>单据信息</div>
          <div class="kv-grid">
            <div class="kv"><span class="k">设备码</span><span class="v mono">{{ detail.elevatorCode || '—' }}</span></div>
            <div class="kv"><span class="k">梯号</span><span class="v">{{ detail.elevatorName || '—' }}</span></div>
            <div class="kv"><span class="k">品种</span><span class="v">{{ detail.category || '—' }}</span></div>
            <div class="kv"><span class="k">安装地址</span><span class="v">{{ detail.location || '—' }}</span></div>
            <div class="kv"><span class="k">安装竣工日期</span><span class="v mono">{{ detail.installCompleteDate || '—' }}</span></div>
            <div class="kv"><span class="k">安装单位</span><span class="v">{{ detail.installUnitName || '—' }}</span></div>
            <div class="kv"><span class="k">特殊类别</span><span class="v">{{ detail.specialType || '—' }}</span></div>
            <div class="kv"><span class="k">接收工程师</span><span class="v">{{ detail.receiverName || '—' }}</span></div>
            <div class="kv"><span class="k">发起人</span><span class="v">{{ detail.initiatorName || '—' }}</span></div>
            <div class="kv"><span class="k">创建时间</span><span class="v mono">{{ fmtTime(detail.createdAt) }}</span></div>
          </div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-history" /></svg>时间轴</div>
          <Timeline :items="timelineItems" />
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-file-text" /></svg>资料清单（3 类必传）</div>
          <div class="chk-list">
            <div v-for="(d, i) in docList" :key="d.name" :class="['chk-row', d.fid ? 'pass' : 'fail']">
              <svg class="ic" aria-hidden="true"><use :href="d.fid ? '#i-check' : '#i-alert'" /></svg>
              <span class="cr-n">{{ i + 1 }}</span>
              <span class="cr-t">{{ d.name }}</span>
              <span class="cr-d">{{ d.fid ? '已上传' : '缺件 · 待补传' }}</span>
            </div>
          </div>
          <div v-if="missingDocs(detail)" class="rule-note mt8">
            <svg class="ic" aria-hidden="true"><use href="#i-alert" /></svg>
            <span>缺 {{ missingDocs(detail) }} 件必传资料，提交审核将被拦截；资料补传在小程序侧完成。</span>
          </div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-clipboard" /></svg>查验与整改</div>
          <div class="chips mb12">
            <button v-for="t in tabs" :key="t.key" :class="['chip', { on: tab === t.key }]" type="button" @click="switchTab(t.key)">{{ t.label }}</button>
          </div>

          <!-- 检查清单 -->
          <div v-if="tab === 'checklist'">
            <AlertStrip v-if="check.conclusion" :tone="conclusionTone" :icon="check.conclusion === 'QUALIFIED' ? 'check' : 'alert'"
              :title="conclusionLabel(check.conclusion)"
              :text="'应检 ' + (check.totalItems || 74) + ' · 合格 ' + (check.passCount || 0) + ' · 不合格 ' + (check.failCount || 0) + ' · 不适用 ' + (check.naCount || 0) + ' · 重要项不合格 ' + (check.importantFailCount || 0)" />
            <p v-if="!editable" class="fhelp mt8">当前状态或权限下为只读（仅查验中且具现场查验权限可逐项填报）。</p>
            <p v-if="!items.length" class="fhelp mt8">
              <svg class="ic ic-sm" aria-hidden="true"><use href="#i-alert" /></svg>
              移交单进入「查验中」后自动生成 GB/T 10060—2023 表 1 的 74 项检查清单（当前状态：{{ hoStatusLabel(detail.status) }}）。
            </p>
            <div v-for="grp in groups" :key="grp.group" class="sec mt12">
              <div class="sec-t">{{ grp.group }}</div>
              <div class="chk-list">
                <template v-for="it in grp.items" :key="it.id">
                  <div v-if="!editable" :class="['chk-row', it.result === 'PASS' ? 'pass' : it.result === 'FAIL' ? 'fail' : '']">
                    <svg class="ic" aria-hidden="true"><use :href="it.result === 'FAIL' ? '#i-x' : it.result === 'PASS' ? '#i-check' : '#i-minus'" /></svg>
                    <span class="cr-n">{{ it.itemNo }}<span v-if="it.important" class="c-amber">☆</span></span>
                    <span class="cr-t">{{ it.itemName }}</span>
                    <span class="cr-d">
                      {{ resultLabel(it.result) || '未填' }}
                      <template v-if="it.measuredValue"> · 实测 {{ it.measuredValue }}</template>
                      <template v-if="it.naReason"> · {{ it.naReason }}</template>
                    </span>
                  </div>
                  <div v-else class="chk-row item-edit" :class="{ fail: it.result === 'FAIL' }">
                    <span class="cr-n">{{ it.itemNo }}<span v-if="it.important" class="c-amber">☆</span></span>
                    <div class="grow">
                      <div class="row between gap8">
                        <b class="f13">{{ it.itemName }}</b>
                        <select v-model="it.result" class="ctl" style="width:auto;min-width:110px;height:28px" :aria-label="'第' + it.itemNo + '项结果'">
                          <option value="">未填</option>
                          <option value="PASS">√ 合格</option>
                          <option value="FAIL">× 不合格</option>
                          <option value="NA">— 不适用</option>
                        </select>
                      </div>
                      <p class="fhelp mt4">{{ it.requirement }}</p>
                      <div class="row gap8 wrap mt6">
                        <input v-if="isTest(it.itemNo) && (it.result === 'PASS' || it.result === 'FAIL')"
                          v-model="it.measuredValue" class="ctl" style="width:140px;height:28px" placeholder="实测值" :aria-label="'第' + it.itemNo + '项实测值'" />
                        <input v-if="it.result === 'FAIL'" v-model="it.failDesc" class="ctl grow" style="height:28px" placeholder="不合格描述（现象与实测值）" />
                        <select v-if="it.result === 'NA'" v-model="it.naReason" class="ctl" style="width:auto;height:28px" :aria-label="'第' + it.itemNo + '项原因码'">
                          <option value="">选原因码</option>
                          <option v-for="na in naCodes" :key="na.code" :value="na.code">{{ na.code }} {{ na.label }}</option>
                        </select>
                        <input v-if="it.result === 'NA'" v-model="it.naNote" class="ctl grow" style="height:28px" placeholder="不适用说明（NA-05 须附见证人）" />
                        <label v-if="it.result === 'FAIL' && (it.important || isTest(it.itemNo))" class="btn btn-ghost btn-xs">
                          <svg class="ic" aria-hidden="true"><use href="#i-upload" /></svg>上传照片
                          <input type="file" accept="image/*" hidden @change="uploadPhoto(it, $event)" />
                        </label>
                        <span v-if="it.photoFileIds" class="tag tag-ok">已附照片</span>
                      </div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
            <fieldset v-if="editable" class="fs mt12">
              <legend>有条件移交例外通道（7.3，仅一般项 1—3 不合格时生效）</legend>
              <div class="perm-list" style="max-height:none">
                <label class="perm-item" :class="{ on: conditional.siteLimited }">
                  <input v-model="conditional.siteLimited" type="checkbox" class="chk" /><span>现场受限无法修复</span>
                </label>
                <label class="perm-item" :class="{ on: conditional.tripartiteAgreed }">
                  <input v-model="conditional.tripartiteAgreed" type="checkbox" class="chk" /><span>安装/维保/使用三方协商确认</span>
                </label>
              </div>
              <input v-model="conditional.controlMeasures" class="ctl mt8" placeholder="控制措施（必填，缺一即按整改处理）" />
            </fieldset>
          </div>

          <!-- 整改清单 -->
          <div v-else-if="tab === 'rectification'">
            <p v-if="!rects.length" class="fhelp">暂无不合格项整改记录（提交查验判定为整改/不合格时按检查项序号 1:1 自动生成，不可增删）。</p>
            <div v-for="r in rects" :key="r.id" class="card mb12" style="padding:12px 14px">
              <div class="row between gap8 wrap">
                <div class="row gap8">
                  <b class="mono f12">第 {{ r.itemNo }} 项</b>
                  <span class="f12 muted">{{ r.itemType }}</span>
                </div>
                <StatusTag :status="r.recheckResult || 'PENDING'" :label="r.recheckResult === 'PASS' ? '复验通过' : r.recheckResult === 'FAIL' ? '复验不通过' : '待复验'" />
              </div>
              <div class="kv-grid mt8">
                <div class="kv"><span class="k">责任方</span><span class="v">{{ r.respParty || '—' }}</span></div>
                <div class="kv"><span class="k">期限</span><span class="v mono">{{ r.dueDate || '—' }}</span></div>
                <div class="kv"><span class="k">整改措施</span><span class="v">{{ r.rectMeasure || '—' }}</span></div>
                <div class="kv"><span class="k">整改人</span><span class="v">{{ r.rectifierName || '—' }}</span></div>
              </div>
              <div class="row gap8 mt8">
                <PermButton code="handover:rectify" label="上传整改" variant="ghost" size="xs" icon="upload" @click="openRectify(r)" />
                <PermButton code="handover:review" label="逐条复验" variant="ghost" size="xs" icon="refresh" @click="openRecheck(r)" />
              </div>
            </div>
          </div>

          <!-- 归档目录 -->
          <div v-else>
            <div class="row gap12 mb12">
              <PermButton code="handover:view" label="生成/刷新归档目录" variant="ghost" size="sm" icon="archive" @click="loadArchive" />
              <span class="fhelp">一梯一档 · SHA-256 校验指纹 · 留存 ≥4 年</span>
            </div>
            <div v-if="archive.length" class="tbl-wrap">
              <table class="tbl">
                <thead><tr><th>序</th><th>类别</th><th>校验指纹</th><th>归档时间</th></tr></thead>
                <tbody>
                  <tr v-for="a in archive" :key="a.id">
                    <td class="mono">{{ a.seq }}</td>
                    <td>{{ archiveLabel(a.category) }}</td>
                    <td><span class="mono f11" style="word-break:break-all">{{ a.sha256 }}</span></td>
                    <td class="mono f12">{{ a.archivedAt }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="fhelp">尚未生成归档目录。</p>
          </div>
        </div>

        <div class="sec">
          <div class="sec-t"><svg class="ic" aria-hidden="true"><use href="#i-signature" /></svg>签字区</div>
          <div class="paper">
            <div class="p-head"><span>移交签字区</span><span>{{ detail.handoverNo }}</span></div>
            <div class="grid2 mt8">
              <div v-for="n in signNodes" :key="n.type" :class="['sign-box', signedNode(n.type) ? '' : 'todo']">
                <template v-if="signedNode(n.type)">
                  <div class="sb-name">{{ signedNode(n.type).byName || signedNode(n.type).actorName || '已签' }}</div>
                  <div class="sb-meta">{{ n.label }} · {{ fmtTime(signedNode(n.type).at) }}</div>
                </template>
                <template v-else>待{{ n.label }}</template>
              </div>
            </div>
            <div v-if="archiveSha" class="hash-line">
              <svg class="ic ic-sm" aria-hidden="true"><use href="#i-hash" /></svg>
              <span>SHA-256</span><code>{{ archiveSha }}</code>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <PermButton v-if="detail.status === 'PENDING_INST_REVIEW'" code="handover:review" label="审核通过" variant="primary" icon="check" @click="approveReview" />
        <PermButton v-if="detail.status === 'PENDING_INST_REVIEW'" code="handover:review_return" label="退回整改" variant="ghost" icon="refresh" @click="rejectModal = true" />
        <PermButton v-if="detail.status === 'INSPECTING'" code="handover:receive" label="指派接收工程师" variant="ghost" icon="user-check" @click="openReceiver" />
        <PermButton v-if="detail.status === 'PENDING_CONFIRM'" code="handover:sign_install" label="安装部签字" variant="ghost" icon="signature" @click="signNode('INSTALLER_HANDOVER')" />
        <PermButton v-if="detail.status === 'PENDING_CONFIRM'" code="handover:sign_maint" label="维保确认签字" variant="ghost" icon="signature" @click="signNode('MAINTENANCE_CONFIRM')" />
        <PermButton v-if="detail.status === 'PENDING_CONFIRM'" code="handover:sign_maint" label="确认归档" variant="primary" icon="archive" @click="confirmArchive" />
        <PermButton v-if="detail.status && detail.status !== 'ARCHIVED' && detail.status !== 'VOIDED'" code="handover:void" label="作废" variant="danger" icon="ban" @click="voidModal = true" />
        <PermButton code="handover:review" label="☆项 NA 审批" variant="ghost" size="sm" @click="naModal = true" />
        <PermButton code="handover:export" label="检查清单" variant="ghost" size="sm" icon="download" @click="doExport('CHECK')" />
        <PermButton code="handover:export" label="整改清单" variant="ghost" size="sm" icon="download" @click="doExport('RECT')" />
        <PermButton code="handover:export" label="归档目录" variant="ghost" size="sm" icon="download" @click="doExport('INDEX')" />
        <PermButton v-if="editable" code="handover:inspect" label="提交查验" variant="primary" icon="check" @click="submit" />
      </template>
    </Drawer>

    <!-- 移交单起草（handover:create=INST_PM/PLATFORM_ADMIN） -->
    <Modal :open="createOpen" title="移交单起草" sub="三份资料齐全后创建并进入内部审核" icon="plus"
      note="handover:create" confirm-label="确认起草" @close="createOpen = false" @confirm="submitCreate">
      <div class="form-grid">
        <FormField label="项目名称" required :error="createErr.projectName"><input v-model="createModel.projectName" class="ctl" placeholder="如：江北城三期" /></FormField>
        <FormField label="使用单位" required><select v-model="createModel.useUnitId" class="ctl"><option value="">请选择使用单位档案</option><option v-for="u in useUnits" :key="u.id" :value="u.id">{{ u.unitName }}</option></select></FormField>
        <FormField label="厂家出厂编号" required hint="归档时用于 2.7 查询"><input v-model="createModel.factoryNumber" class="ctl mono" /></FormField>
        <FormField label="单位内编号" required><input v-model="createModel.insideNumber" class="ctl mono" placeholder="如：KT-01" /></FormField>
        <FormField label="电梯编码（可空，归档回填）"><input v-model="createModel.elevatorCode" class="ctl mono" placeholder="未注册可留空" /></FormField>
        <FormField label="注册代码（可空，归档回填）"><input v-model="createModel.regCode" class="ctl mono" placeholder="未注册可留空" /></FormField>
        <FormField label="设备代码（可空，归档回填）"><input v-model="createModel.deviceCode" class="ctl mono" placeholder="未获取可留空" /></FormField>
        <FormField label="电梯名称"><input v-model="createModel.elevatorName" class="ctl" placeholder="如：1# 客梯" /></FormField>
        <FormField label="电梯品种"><input v-model="createModel.category" class="ctl" placeholder="如：曳引与强制驱动电梯" /></FormField>
        <FormField label="安装地址"><input v-model="createModel.location" class="ctl" /></FormField>
        <FormField label="型号"><input v-model="createModel.model" class="ctl" /></FormField>
        <FormField label="品牌"><input v-model="createModel.brand" class="ctl" /></FormField>
        <FormField label="制造单位"><input v-model="createModel.manufacturer" class="ctl" /></FormField>
        <FormField label="驱动方式"><input v-model="createModel.driveMode" class="ctl" /></FormField>
        <FormField label="额定载重"><input v-model="createModel.ratedLoad" type="number" class="ctl mono" /></FormField>
        <FormField label="载重单位"><input v-model="createModel.ratedLoadUnit" class="ctl mono" /></FormField>
        <FormField label="额定速度"><input v-model="createModel.ratedSpeed" type="number" step="0.01" class="ctl mono" /></FormField>
        <FormField label="速度单位"><input v-model="createModel.ratedSpeedUnit" class="ctl mono" /></FormField>
        <FormField label="层站/门"><input v-model="createModel.stationsDoors" class="ctl mono" placeholder="如：11/11" /></FormField>
        <FormField label="维保类别"><select v-model="createModel.workTypeCode" class="ctl"><option value="HM">半月</option><option value="FM">按需</option><option value="TM">季度</option><option value="SM">半年</option><option value="OY">年度</option></select></FormField>
        <FormField label="监督检验合格日"><input v-model="createModel.supervisionPassDate" type="date" class="ctl mono" /></FormField>
        <FormField label="安装竣工日期"><input v-model="createModel.installCompleteDate" type="date" class="ctl mono" /></FormField>
        <FormField label="安装单位名称"><input v-model="createModel.installUnitName" class="ctl" /></FormField>
        <FormField label="特殊类别"><select v-model="createModel.specialType" class="ctl"><option value="">无</option><option value="消防">消防电梯</option><option value="防爆">防爆电梯</option></select></FormField>
      </div>
      <div class="mt16">
        <h3>移交资料（全部必传）</h3>
        <div v-for="doc in createDocs" :key="doc.field" class="form-grid mt8">
          <FormField :label="doc.label" required>
            <input type="file" accept=".pdf,.jpg,.jpeg,.png" class="ctl" @change="uploadCreateDoc(doc.field, $event)" />
            <div class="dim f11 mt4">{{ doc.fileId ? '已上传' : '支持 PDF / JPG / PNG' }}</div>
          </FormField>
        </div>
      </div>
      <RuleNote class="mt12">发起人取当前账号（{{ auth.userName }}）。三份资料全部上传成功后才能创建；电梯编码、注册代码、设备代码在归档时由 2.7 自动回填。</RuleNote>
    </Modal>

    <!-- 审核退回 -->
    <Modal :open="rejectModal" title="退回整改" :sub="detail.handoverNo" icon="refresh" note="handover:review_return"
      confirm-label="确认退回" @close="rejectModal = false" @confirm="confirmReject">
      <div class="form-grid one">
        <FormField label="退回原因" required :error="rejectErr.remark">
          <textarea v-model="rejectForm.remark" class="ctl" placeholder="必填，说明整改项" />
        </FormField>
        <FormField label="整改期限" required :error="rejectErr.deadline">
          <input v-model="rejectForm.deadline" type="date" class="ctl mono" />
        </FormField>
      </div>
    </Modal>

    <!-- 作废 -->
    <Modal :open="voidModal" title="作废移交单" :sub="detail.handoverNo" icon="ban" danger require-confirm
      confirm-text="我已知悉作废不可逆，全程审计留痕" note="handover:void" confirm-label="确认作废"
      @close="voidModal = false" @confirm="confirmVoid">
      <FormField label="作废原因" required :error="voidErr">
        <textarea v-model="voidReason" class="ctl" placeholder="必填" />
      </FormField>
      <RuleNote class="mt12">作废后单据不可恢复；历史资料与审计保留。</RuleNote>
    </Modal>

    <!-- 指派接收工程师 -->
    <Modal :open="receiverModal" title="指派接收工程师" :sub="detail.handoverNo + ' · 验收查验责任人'" icon="user-check"
      note="handover:receive" confirm-label="确认指派" @close="receiverModal = false" @confirm="confirmReceiver">
      <FormField label="接收工程师" required :error="receiverErr">
        <select v-model="receiverId" class="ctl">
          <option value="">请选择接收工程师</option>
          <option v-for="e in employees" :key="e.id" :value="e.id">{{ e.name }}{{ e.groupName ? ' · ' + e.groupName : '' }}</option>
        </select>
      </FormField>
    </Modal>

    <!-- 上传整改 -->
    <Modal :open="rectifyModal" title="上传整改（安装部）" icon="upload" note="handover:rectify" confirm-label="保存"
      @close="rectifyModal = false" @confirm="confirmRectify">
      <div class="form-grid one">
        <FormField label="整改措施" required><textarea v-model="rectForm.rectMeasure" class="ctl" rows="3" /></FormField>
        <FormField label="整改后照片">
          <label class="btn btn-ghost btn-sm">
            <svg class="ic" aria-hidden="true"><use href="#i-image" /></svg>选择照片
            <input type="file" accept="image/*" hidden @change="uploadRectPhoto" />
          </label>
          <span v-if="rectForm.afterPhotoFileId" class="tag tag-ok mt6">已上传</span>
        </FormField>
        <FormField label="整改人"><input v-model="rectForm.rectifierName" class="ctl" /></FormField>
      </div>
    </Modal>

    <!-- 逐条复验 -->
    <Modal :open="recheckModal" title="逐条复验（原查验工程师）" icon="refresh" note="handover:review" confirm-label="提交复验"
      @close="recheckModal = false" @confirm="confirmRecheck">
      <div class="form-grid one">
        <FormField label="复验结果" required>
          <select v-model="recheckForm.result" class="ctl">
            <option value="PASS">合格</option>
            <option value="FAIL">不通过</option>
          </select>
        </FormField>
        <FormField label="复验人"><input v-model="recheckForm.recheckerName" class="ctl" /></FormField>
      </div>
      <RuleNote class="mt12">全部整改项复验通过后才回「待确认」。</RuleNote>
    </Modal>

    <!-- NA 审批 -->
    <Modal :open="naModal" title="☆项不适用审批（维保部经理）" icon="shield" note="handover:review" confirm-label="批准"
      @close="naModal = false" @confirm="confirmNa">
      <FormField label="审批人"><input v-model="naForm.approverName" class="ctl" /></FormField>
      <RuleNote class="mt12">批准后方可提交含 ☆项不适用的查验清单（记录审批留痕）。</RuleNote>
    </Modal>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import http from '../api/client'
import DataTable from '../ui/DataTable.vue'
import Drawer from '../ui/Drawer.vue'
import Modal from '../ui/Modal.vue'
import StatusTag from '../ui/StatusTag.vue'
import PermButton from '../ui/PermButton.vue'
import FormField from '../ui/FormField.vue'
import ViewHead from '../ui/ViewHead.vue'
import PageMeta from '../ui/PageMeta.vue'
import RuleNote from '../ui/RuleNote.vue'
import AlertStrip from '../ui/AlertStrip.vue'
import Steps from '../ui/Steps.vue'
import Timeline from '../ui/Timeline.vue'
import { useToast } from '../ui/stores/ui'
import { useAuthStore } from '../stores/auth'
import { uploadFile } from '../api/upload'
import { statusLabel } from '../ui/labels'
import * as api from '../api/handover'

const auth = useAuthStore()
const toast = useToast()

const HO_LABEL = {
  INIT: '草稿', PENDING_INST_REVIEW: '待安装部审核', DOC_SUBMITTING: '资料提交',
  INSPECTING: '查验中', RECTIFYING: '整改复验中', PENDING_CONFIRM: '待确认归档', ARCHIVED: '已归档', VOIDED: '已作废'
}
function hoStatusLabel(s) { return HO_LABEL[s] || statusLabel(s) }
const hoSteps = ['草稿', '待安装部审核', '资料提交', '查验中', '整改复验中', '待确认归档', '已归档']
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }

const rows = ref([])
const loading = ref(false)
const cols = [
  { key: 'handoverNo', label: '移交单号', sortable: true },
  { key: 'projectName', label: '项目', sortable: true },
  { key: 'initiatorName', label: '发起人', sortable: true },
  { key: 'elevatorCode', label: '设备' },
  { key: 'installUnitName', label: '安装单位' },
  { key: 'createdAt', label: '创建时间', sortable: true },
  { key: 'status', label: '状态', sortable: true }
]
const statusFilters = [{ key: 'status', label: '状态', options: Object.keys(HO_LABEL).map((c) => ({ value: c, label: HO_LABEL[c] })) }]
function missingDocs(row) {
  if (!row || !row.id) return 0
  return ['supervisionReportFileId', 'maintenanceManualFileId', 'qualityCertificateFileId'].filter((k) => !row[k]).length
}

let detailSeq = 0
const detailOpen = ref(false)
const detail = ref({})
const detailId = ref('')
const loaded = ref(false)
const signs = ref([])
const checks = ref([])
const tab = ref('checklist')
const tabs = [{ key: 'checklist', label: '74 项检查清单' }, { key: 'rectification', label: '1:1 整改清单' }, { key: 'archive', label: '一梯一档' }]
const check = ref({})
const items = ref([])
const rects = ref([])
const archive = ref([])
const conditional = reactive({ siteLimited: false, tripartiteAgreed: false, controlMeasures: '' })
const employees = ref([])

const naCodes = [
  { code: 'NA-01', label: '本梯无此配置/部件' },
  { code: 'NA-02', label: '品种/型式不涉及' },
  { code: 'NA-03', label: '建筑无此要求' },
  { code: 'NA-04', label: '特殊类别不涉及' },
  { code: 'NA-05', label: '现场条件限制无法检测' }
]
const signNodes = [
  { type: 'INSTALLER_HANDOVER', label: '安装部签字' },
  { type: 'SITE_INSPECT', label: '现场查验签字' },
  { type: 'MAINTENANCE_ASSIGN', label: '维保指派签字' },
  { type: 'MAINTENANCE_CONFIRM', label: '维保确认签字' }
]
function signedNode(type) {
  return signs.value.find((s) => s.nodeType === type && String(s.action || '').toUpperCase() === 'APPROVE') || null
}
const archiveSha = computed(() => (archive.value.find((a) => a.category === 'INDEX') || archive.value[0] || {}).sha256 || '')
const docList = computed(() => [
  { name: '监督检验报告', fid: detail.value.supervisionReportFileId },
  { name: '维保手册', fid: detail.value.maintenanceManualFileId },
  { name: '质量合格证', fid: detail.value.qualityCertificateFileId }
])
const timelineItems = computed(() => {
  const d = detail.value
  const out = []
  if (d.createdAt) out.push({ title: '发起移交', actor: d.initiatorName || '', time: fmtTime(d.createdAt), cls: 'done' })
  if (d.reviewedAt) out.push({ title: d.reviewRemark ? '审核退回' : '内部审核通过', actor: d.reviewByName || '', time: fmtTime(d.reviewedAt), text: d.reviewRemark || '', cls: d.reviewRemark ? 'bad' : 'done' })
  checks.value.forEach((c) => out.push({ title: '现场查验提交', actor: c.checkerName || '', time: fmtTime(c.checkedAt), text: c.conclusion ? conclusionLabel(c.conclusion) : '', cls: 'done' }))
  signs.value.forEach((s) => out.push({ title: (signNodes.find((n) => n.type === s.nodeType) || {}).label || s.nodeType, actor: s.byName || s.actorName || '', time: fmtTime(s.at), cls: String(s.action || '').toUpperCase() === 'REJECT' ? 'bad' : 'done' }))
  if (d.confirmedAt) out.push({ title: '经理确认归档', actor: d.confirmedByName || '', time: fmtTime(d.confirmedAt), cls: 'done' })
  out.push({ title: '当前状态 · ' + hoStatusLabel(d.status), cls: 'active', text: d.status === 'VOIDED' ? '已作废' : '状态机推进中' })
  return out
})

const editable = computed(() => detail.value.status === 'INSPECTING' && auth.can('handover:inspect'))
const groups = computed(() => {
  const map = new Map()
  for (const it of items.value) {
    const g = it.itemGroup || 'G00'
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(it)
  }
  return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([group, its]) => ({ group, items: its }))
})
const conclusionTone = computed(() => ({ QUALIFIED: 'as-ok', DISQUALIFIED: 'as-sig', CONDITIONAL: '', RECTIFY: '' }[check.value.conclusion] || ''))

function isTest(no) { return no >= 57 && no <= 74 }
function resultLabel(r) { return { PASS: '合格', FAIL: '不合格', NA: '不适用' }[r] || '' }
function conclusionLabel(c) {
  return { QUALIFIED: '合格，准予移交', RECTIFY: '一般项不合格，须整改复检', DISQUALIFIED: '判定不合格，全面修复后重新验收', CONDITIONAL: '有条件移交（7.3 例外通道）' }[c] || c
}
function archiveLabel(c) {
  return { CHECKLIST: '检查清单', RECTIFICATION: '整改清单', DOCS: '移交资料 D1—D20', PHOTOS: '逐项+定角照片', CONFIRMATION: '交接确认单', FIRST_ORDER: '首保工单关联', PENDING_DOCS: '待补件清单', INDEX: '归档目录' }[c] || c
}

async function loadList() {
  loading.value = true
  try { rows.value = (await api.listHandovers({ size: 200 })).list || [] } catch (e) { toast.error(e.message || '加载移交单失败') } finally { loading.value = false }
}
async function loadEmployees() {
  try { employees.value = (await http.get('/employees')) || [] } catch { employees.value = [] }
}
onMounted(() => { loadList(); loadEmployees(); loadElevatorOptions() })

// ── 移交单起草（handover:create=INST_PM/PLATFORM_ADMIN）──
function emptyCreateModel() {
  return {
    projectName: '', useUnitId: '', factoryNumber: '', insideNumber: '',
    elevatorCode: '', regCode: '', deviceCode: '', elevatorName: '', category: '', location: '',
    model: '', brand: '', manufacturer: '', driveMode: '', ratedLoad: '', ratedLoadUnit: 'kg',
    ratedSpeed: '', ratedSpeedUnit: 'm/s', stationsDoors: '', workTypeCode: 'HM',
    supervisionPassDate: '', installCompleteDate: '', installUnitName: '', specialType: ''
  }
}
function emptyCreateDocs() {
  return [
    { field: 'supervisionReportFileId', label: '电梯监督检验报告', fileId: '' },
    { field: 'qualityCertificateFileId', label: '安装质量证明文件', fileId: '' },
    { field: 'maintenanceManualFileId', label: '维护使用说明书', fileId: '' }
  ]
}
const createOpen = ref(false)
const createModel = ref(emptyCreateModel())
const createDocs = ref(emptyCreateDocs())
const createErr = reactive({ projectName: '' })
const useUnits = ref([])
async function loadElevatorOptions() {
  try { useUnits.value = (await api.getHandoverOptions()).useUnits || [] } catch { useUnits.value = [] }
}
function openCreate() {
  createModel.value = emptyCreateModel()
  createDocs.value = emptyCreateDocs()
  createErr.projectName = ''
  createOpen.value = true
}
async function uploadCreateDoc(field, ev) {
  const file = ev.target.files && ev.target.files[0]
  if (!file) return
  try {
    const res = await uploadFile(file)
    const doc = createDocs.value.find((d) => d.field === field)
    if (doc) doc.fileId = res.fileId
    toast.success('资料已上传')
  } catch (e) { toast.error(e.message || '资料上传失败') } finally { ev.target.value = '' }
}
async function submitCreate() {
  createErr.projectName = createModel.value.projectName.trim() ? '' : '项目名称必填'
  if (createErr.projectName) return
  if (!createModel.value.useUnitId) { toast.error('请选择使用单位'); return }
  if (!createModel.value.factoryNumber.trim()) { toast.error('请填写厂家出厂编号，供归档时查询注册信息'); return }
  if (!createModel.value.insideNumber.trim()) { toast.error('请填写单位内编号'); return }
  if (createDocs.value.some((d) => !d.fileId)) { toast.error('监督检验报告、维护使用说明书、安装质量证明文件缺一不可'); return }
  try {
    const body = Object.assign({}, createModel.value, {
      projectName: createModel.value.projectName.trim(),
      supervisionReportFileId: createDocs.value.find((d) => d.field === 'supervisionReportFileId').fileId,
      maintenanceManualFileId: createDocs.value.find((d) => d.field === 'maintenanceManualFileId').fileId,
      qualityCertificateFileId: createDocs.value.find((d) => d.field === 'qualityCertificateFileId').fileId,
      initiatorId: (auth.user && auth.user.id) || '',
      initiatorName: auth.userName || ''
    })
    const created = await api.createHandover(body)
    createOpen.value = false
    toast.success('移交单已起草（待内部审核）', created.handoverNo || created.id)
    await loadList()
    if (created && created.id) openDetail(created)
  } catch (e) { toast.error(e.message || '起草失败') }
}

async function openDetail(row) {
  const seq = ++detailSeq
  const targetId = row.id
  detail.value = { ...row }
  detailOpen.value = true
  tab.value = 'checklist'
  signs.value = []
  checks.value = []
  check.value = {}
  items.value = []
  rects.value = []
  archive.value = []
  conditional.siteLimited = false
  conditional.tripartiteAgreed = false
  conditional.controlMeasures = ''
  detailId.value = targetId
  loaded.value = false
  const loadDetail = (async () => {
    try {
      const full = await api.handoverDetail(targetId)
      if (seq !== detailSeq) return
      detail.value = { ...row, ...full }
      signs.value = full.signs || []
      checks.value = full.checks || []
    } catch { /* 详情补全失败时退回列表行字段 */ }
  })()
  await Promise.all([loadDetail, loadChecklist(targetId, seq), loadRects(targetId, seq)])
  if (seq !== detailSeq) return
  loaded.value = true
}
function switchTab(key) {
  tab.value = key
  if (key === 'archive' && !archive.value.length) loadArchive()
}
async function loadChecklist(handoverId = detailId.value, seq = detailSeq) {
  try {
    const data = await api.getChecklist(handoverId)
    if (seq !== detailSeq || handoverId !== detailId.value) return
    check.value = data.check || {}
    items.value = (data.items || []).map((it) => ({ ...it }))
  } catch (e) {
    if (seq !== detailSeq || handoverId !== detailId.value) return
    // 422=未到查验中（清单在进入查验中时生成）：静默置空并显示引导文案，不报错误
    if (e.code === 422) { check.value = {}; items.value = [] }
    else toast.error(e.message || '加载检查清单失败')
  }
}
async function loadRects(handoverId = detailId.value, seq = detailSeq) {
  try {
    const data = await api.getRectifications(handoverId)
    if (seq !== detailSeq || handoverId !== detailId.value) return
    rects.value = data
  } catch {
    if (seq !== detailSeq || handoverId !== detailId.value) return
    rects.value = []
  }
}
async function loadArchive(handoverId = detailId.value, seq = detailSeq) {
  try {
    const data = await api.getArchive(handoverId)
    if (seq !== detailSeq || handoverId !== detailId.value) return
    archive.value = data
  } catch (e) {
    if (seq !== detailSeq || handoverId !== detailId.value) return
    toast.error(e.message || '加载归档目录失败')
  }
}

async function uploadPhoto(it, ev) {
  const file = ev.target.files && ev.target.files[0]
  if (!file) return
  try {
    const res = await uploadFile(file)
    it.photoFileIds = it.photoFileIds ? it.photoFileIds + ',' + res.fileId : res.fileId
    toast.success('照片已上传')
  } catch (e) { toast.error(e.message || '照片上传失败') } finally { ev.target.value = '' }
}

async function submit() {
  const id = detailId.value
  const seq = detailSeq
  if (!loaded.value || !id || detail.value.id !== id) {
    toast.error('移交单加载未完成，请稍候重试')
    return
  }
  const payload = items.value.map((it) => ({
    itemNo: it.itemNo, result: it.result || null, measuredValue: it.measuredValue || null,
    failDesc: it.failDesc || null, naReason: it.naReason || null, naNote: it.naNote || null, photoFileIds: it.photoFileIds || null
  }))
  try {
    const order = await api.submitChecklist(id, { items: payload, conditional: { ...conditional } })
    if (seq !== detailSeq) return
    detail.value = { ...detail.value, status: order.status }
    toast.success('查验已提交：' + conclusionLabel(check.value.conclusion))
    await Promise.all([loadChecklist(id, seq), loadRects(id, seq), loadList()])
  } catch (e) { toast.error(e.message || '提交查验失败') }
}

async function approveReview() {
  try {
    const order = await http.post(`/handovers/${detail.value.id}/review`, { action: 'APPROVE' })
    detail.value = { ...detail.value, ...order }
    toast.success('审核通过 · 进入查验中',detail.value.handoverNo)
    await loadList()
  } catch (e) { toast.error(e.message || '审核失败') }
}
const rejectModal = ref(false)
const rejectForm = reactive({ remark: '', deadline: '' })
const rejectErr = reactive({ remark: '', deadline: '' })
async function confirmReject() {
  rejectErr.remark = rejectForm.remark.trim() ? '' : '退回原因必填'
  rejectErr.deadline = rejectForm.deadline ? '' : '整改期限必填'
  if (rejectErr.remark || rejectErr.deadline) return
  try {
    const order = await http.post(`/handovers/${detail.value.id}/review`, { action: 'REJECT', remark: rejectForm.remark, deadline: rejectForm.deadline })
    detail.value = { ...detail.value, ...order }
    rejectModal.value = false
    toast.success('已退回整改',detail.value.handoverNo)
    await loadList()
  } catch (e) { toast.error(e.message || '退回失败') }
}

// 作废（原因必填，不可逆）
const voidModal = ref(false)
const voidReason = ref('')
const voidErr = ref('')
async function confirmVoid() {
  if (!voidReason.value.trim()) { voidErr.value = '作废原因必填'; return }
  voidErr.value = ''
  try {
    const order = await http.post(`/handovers/${detail.value.id}/void`, { reason: voidReason.value })
    detail.value = { ...detail.value, ...order }
    voidModal.value = false
    toast.success('已作废 · 审计已记录',detail.value.handoverNo)
    await loadList()
  } catch (e) { toast.error(e.message || '作废失败') }
}

// 指派接收工程师
const receiverModal = ref(false)
const receiverId = ref('')
const receiverErr = ref('')
function openReceiver() { receiverId.value = detail.value.receiverId || ''; receiverErr.value = ''; receiverModal.value = true }
async function confirmReceiver() {
  if (!receiverId.value) { receiverErr.value = '请指定接收工程师'; return }
  receiverErr.value = ''
  const emp = employees.value.find((e) => e.id === receiverId.value)
  try {
    const order = await http.post(`/handovers/${detail.value.id}/assign-receiver`, { receiverId: receiverId.value, receiverName: emp ? emp.name : '' })
    detail.value = { ...detail.value, ...order }
    receiverModal.value = false
    toast.success('已指派接收工程师',emp ? emp.name : '')
    await loadList()
  } catch (e) { toast.error(e.message || '指派失败') }
}

// 节点签字 / 确认归档
async function signNode(nodeType) {
  const targetId = detailId.value
  const seq = detailSeq
  const isCurrent = () => seq === detailSeq && targetId === detailId.value && detail.value.id === targetId
  if (!isCurrent()) return
  try {
    await http.post(`/handovers/${targetId}/sign`, { nodeType, action: 'APPROVE' })
    if (!isCurrent()) return
    const full = await api.handoverDetail(targetId)
    if (!isCurrent()) return
    signs.value = full.signs || []
    toast.success('签字已留痕',nodeType)
  } catch (e) {
    if (isCurrent()) toast.error(e.message || '签字失败')
  }
}
async function confirmArchive() {
  try {
    const order = await api.confirmHandover(detail.value.id, { confirmedById: auth.user?.id, confirmedByName: auth.userName })
    detail.value = { ...detail.value, ...order }
    toast.success('已确认归档 · 建档 + 首保工单已生成',detail.value.handoverNo)
    await Promise.all([loadList(), loadArchive()])
  } catch (e) { toast.error(e.message || '确认归档失败') }
}

// 整改
const rectifyModal = ref(false)
const currentRect = ref(null)
const rectForm = reactive({ rectMeasure: '', afterPhotoFileId: '', rectifierName: '' })
function openRectify(r) {
  currentRect.value = r
  rectForm.rectMeasure = r.rectMeasure || ''
  rectForm.afterPhotoFileId = r.afterPhotoFileId || ''
  rectForm.rectifierName = auth.userName
  rectifyModal.value = true
}
async function uploadRectPhoto(ev) {
  const file = ev.target.files && ev.target.files[0]
  if (!file) return
  try { const res = await uploadFile(file); rectForm.afterPhotoFileId = res.fileId; toast.success('照片已上传') }
  catch (e) { toast.error(e.message || '照片上传失败') } finally { ev.target.value = '' }
}
async function confirmRectify() {
  try {
    await api.rectifyItem(detail.value.id, currentRect.value.id, { ...rectForm })
    rectifyModal.value = false
    toast.success('整改已上传')
    await loadRects()
  } catch (e) { toast.error(e.message || '上传整改失败') }
}

// 复验
const recheckModal = ref(false)
const recheckForm = reactive({ result: 'PASS', recheckerName: '' })
function openRecheck(r) {
  currentRect.value = r
  recheckForm.result = 'PASS'
  recheckForm.recheckerName = auth.userName
  recheckModal.value = true
}
async function confirmRecheck() {
  try {
    const order = await api.recheckItem(detail.value.id, currentRect.value.id, { ...recheckForm, recheckerId: auth.user?.id })
    recheckModal.value = false
    detail.value = { ...detail.value, status: order.status }
    toast.success('复验已提交')
    await Promise.all([loadRects(), loadList()])
  } catch (e) { toast.error(e.message || '复验失败') }
}

// NA 审批
const naModal = ref(false)
const naForm = reactive({ approverName: '' })
async function confirmNa() {
  try {
    await api.naApprove(detail.value.id, { approverId: auth.user?.id, approverName: naForm.approverName || auth.userName })
    naModal.value = false
    toast.success('NA 审批已记录')
  } catch (e) { toast.error(e.message || 'NA 审批失败') }
}

// PDF 导出（blob 下载）
async function doExport(form) {
  try {
    const blob = await api.exportPdf(detail.value.id, form)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `handover-${detail.value.handoverNo || detail.value.id}-${form}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) { toast.error(e.message || '导出失败') }
}
</script>
<style scoped>
/* 仅补全局组件层未覆盖的逐项填报行内布局 */
.item-edit { align-items: flex-start; flex-wrap: wrap; }
.item-edit .grow { min-width: 0; }
</style>