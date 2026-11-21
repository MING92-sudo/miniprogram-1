<template>
  <Modal :open="open" title="修改电梯定位" :sub="(row.projectName || row.elevatorName || '') + ' · ' + (row.elevatorCode || '')"
    icon="map-pin" note="elevator:edit" :confirm-label="saving ? '保存中…' : '保存定位'"
    @close="$emit('close')" @confirm="submit">
    <Form ref="formRef" v-model="formModel" :rules="rules" v-slot="{ errors }">
      <div class="form-grid one">
        <FormField label="详细地址">
          <input v-model="formModel.location" name="geo-location" class="ctl" maxlength="200"
            placeholder="填写电梯实际所在地址" autocomplete="off" />
        </FormField>
      </div>
      <div class="form-grid">
        <FormField label="经度（GCJ-02）" :error="errors.lng">
          <input v-model="formModel.lng" name="geo-lng" class="ctl mono" inputmode="decimal"
            placeholder="-180 至 180" autocomplete="off" />
        </FormField>
        <FormField label="纬度（GCJ-02）" :error="errors.lat">
          <input v-model="formModel.lat" name="geo-lat" class="ctl mono" inputmode="decimal"
            placeholder="-90 至 90" autocomplete="off" />
        </FormField>
      </div>
    </Form>
    <RuleNote class="mt12" icon="alert">
      坐标采用 GCJ-02，保存后用于后续维保签到围栏校验。经纬度须同时填写或同时留空；同时留空可清除错误首采，下次主维保签到时按现场定位重新采集。
    </RuleNote>
  </Modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import Modal from './Modal.vue'
import Form from './Form.vue'
import FormField from './FormField.vue'
import RuleNote from './RuleNote.vue'
import { updateElevator } from '../api/archive'
import { useToast } from './stores/ui'

const props = defineProps({
  open: Boolean,
  row: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['close', 'saved'])
const toast = useToast()
const formRef = ref(null)
const formModel = ref({ location: '', lng: '', lat: '' })
const saving = ref(false)

function text(value) {
  return value == null ? '' : String(value).trim()
}

function coordinateError(field, raw) {
  const lng = text(formModel.value.lng)
  const lat = text(formModel.value.lat)
  if (!lng && !lat) return ''
  if (!lng || !lat) return '经纬度须同时填写或同时留空'
  const label = field === 'lng' ? '经度' : '纬度'
  const limit = field === 'lng' ? 180 : 90
  const value = Number(text(raw))
  if (!Number.isFinite(value)) return label + '必须是有效数字'
  if (Math.abs(value) > limit) return label + '须在 -' + limit + ' 至 ' + limit + ' 之间'
  return ''
}

const rules = {
  lng: [(value) => coordinateError('lng', value)],
  lat: [(value) => coordinateError('lat', value)]
}

watch(() => props.open, (open) => {
  if (!open) return
  formModel.value = {
    location: text(props.row.location),
    lng: text(props.row.lng),
    lat: text(props.row.lat)
  }
}, { immediate: true })

async function submit() {
  if (saving.value || !props.row.id || !formRef.value.validate()) return
  const body = {
    location: text(formModel.value.location),
    lng: text(formModel.value.lng),
    lat: text(formModel.value.lat)
  }
  saving.value = true
  try {
    const updated = await updateElevator(props.row.id, body)
    toast.success('定位已保存')
    emit('saved', updated)
    emit('close')
  } catch (e) {
    toast.error(e.message || '定位保存失败')
  } finally {
    saving.value = false
  }
}
</script>
