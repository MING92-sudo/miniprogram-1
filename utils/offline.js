// 离线缓存与补传（docs/02 架构约定）
// - em_order_<id>：按工单缓存本地快照（检查项填写等），弱网时先保本地
// - offline_queue：待补传请求队列，网络恢复时自动逐条重放（复用任务 id 作幂等键）
const { request } = require('./request')
const { uuid } = require('./util')
const { uploadImage } = require('../services/upload')

const QUEUE_KEY = 'offline_queue'
const ORDER_CACHE_PREFIX = 'em_order_'

// ── 工单本地快照 ──────────────────────────────

function cacheOrderSnapshot(order) {
  if (!order || !order.id) return
  wx.setStorageSync(ORDER_CACHE_PREFIX + order.id, {
    snapshot: order,
    cachedAt: Date.now()
  })
}

function getCachedOrder(orderId) {
  return wx.getStorageSync(ORDER_CACHE_PREFIX + orderId) || null
}

function listCachedOrders() {
  const info = wx.getStorageInfoSync()
  return (info.keys || [])
    .filter((k) => k.indexOf(ORDER_CACHE_PREFIX) === 0)
    .map((k) => {
      const cached = wx.getStorageSync(k) || {}
      const snapshot = cached.snapshot || {}
      return {
        key: k,
        orderId: k.slice(ORDER_CACHE_PREFIX.length),
        orderNo: snapshot.orderNo || snapshot.id || k,
        elevatorName: snapshot.elevatorName || '',
        cachedAt: cached.cachedAt || 0
      }
    })
    .sort((a, b) => b.cachedAt - a.cachedAt)
}

function clearOrderCache(orderId) {
  wx.removeStorageSync(ORDER_CACHE_PREFIX + orderId)
}

// ── 待补传队列 ────────────────────────────────

// task: { url, method, data, desc, photoPaths?, uploads? }；重放时自动携带幂等键
// uploads: [{ field, path, fileId? }]，用于签名等单文件字段先补传再回填业务 data。
// 同一 url+method 重复入队时替换旧任务（后提交者为准），保证队列级幂等
function enqueue(task) {
  const queue = wx.getStorageSync(QUEUE_KEY) || []
  const taskFull = Object.assign({ id: uuid(), method: 'POST', createdAt: Date.now() }, task)
  const idx = queue.findIndex((t) => t.url === taskFull.url && t.method === taskFull.method)
  if (idx > -1) queue[idx] = taskFull
  else queue.push(taskFull)
  wx.setStorageSync(QUEUE_KEY, queue)
  return queue.length
}

function getQueue() {
  return wx.getStorageSync(QUEUE_KEY) || []
}

// 逐条重放：成功一条移除一条；失败保留在队列等待下次补传
async function flushQueue() {
  const queue = getQueue()
  let success = 0
  const failed = []
  // 统一持久化，避免某一步只写“当前任务 + 后续任务”时丢掉本轮已经失败的前置任务。
  const persist = (nextQueue) => wx.setStorageSync(QUEUE_KEY, nextQueue)
  for (let idx = 0; idx < queue.length; idx++) {
    const task = queue[idx]
    try {
      // 离线期间拍摄的照片：补传时先补传图片文件，再把 fileId 填回业务数据。
      // 断点续传：已成功照片的 fileId 记入 task.uploadedFileIds 并即时持久化，
      // 单张失败不重传整批（docs/08 审查 #6）
      if (Array.isArray(task.photoPaths) && task.photoPaths.length > 0) {
        const fileIds = task.uploadedFileIds || []
        while (fileIds.length < task.photoPaths.length) {
          const r = await uploadImage(task.photoPaths[fileIds.length]) // 按序续传未完成的
          fileIds.push(r.fileId)
          task.uploadedFileIds = fileIds.slice()
          // 每成功一张即持久化断点（含本任务剩余照片 + 其后未处理任务）
          persist(failed.concat([task], queue.slice(idx + 1)))
        }
        task.data.photoFileIds = fileIds
        task.photoPaths = undefined
        task.uploadedFileIds = undefined
      }

      // 签名等单文件上传字段：已上传的 fileId 持久化在 upload.fileId，断点续传时不再重传。
      const uploads = Array.isArray(task.uploads) ? task.uploads : []
      for (let uIdx = 0; uIdx < uploads.length; uIdx++) {
        const upload = uploads[uIdx]
        if (!upload.fileId) {
          const r = await uploadImage(upload.path)
          upload.fileId = r.fileId
          // 每成功一个字段即持久化，避免应用被杀后重复上传。
          persist(failed.concat([task], queue.slice(idx + 1)))
        }
        task.data[upload.field] = upload.fileId
      }
      await request({
        url: task.url,
        method: task.method,
        data: task.data,
        // 复用任务 id 作幂等键：同一任务多次重放后端不产生重复记录
        idempotencyKey: task.id
      })
      success++
      // 本条完成即持久化移除（避免应用被杀后整批重复重放）
      persist(failed.concat(queue.slice(idx + 1)))
    } catch (e) {
      failed.push(task)
      // 失败任务保留：持久化"前置失败任务 + 当前任务 + 其后未处理任务"
      persist(failed.concat(queue.slice(idx)))
    }
  }
  persist(failed)
  return { success, fail: failed.length }
}

function clearQueue() {
  wx.removeStorageSync(QUEUE_KEY)
}

module.exports = {
  QUEUE_KEY,
  cacheOrderSnapshot,
  getCachedOrder,
  listCachedOrders,
  clearOrderCache,
  enqueue,
  getQueue,
  flushQueue,
  clearQueue
}
