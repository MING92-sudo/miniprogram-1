// 离线缓存与补传- em_order_<id>：按工单缓存本地快照（检查项填写等），弱网时先保本地- offline_queue：待补传请求队列，网络恢复时自动逐条重放（复用任务 id 作幂等键）
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

// 重放时自动携带幂等键uploads: [{ field, path, fileId? }]，用于签名等单文件字段先补传再回填业务 data。
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

let flushing = null

// 逐条重放：成功一条移除一条；失败保留。持久化时合并快照之后的新任务，禁止旧快照覆盖新入队数据。
async function doFlushQueue() {
  const queue = getQueue().slice()
  const snapshotIds = new Set(queue.map((t) => t.id))
  let success = 0
  const failed = []
  const persist = (nextQueue) => {
    const added = getQueue().filter((t) => !snapshotIds.has(t.id))
    wx.setStorageSync(QUEUE_KEY, nextQueue.concat(added))
  }
  for (let idx = 0; idx < queue.length; idx++) {
    const task = queue[idx]
    try {
      if (Array.isArray(task.photoPaths) && task.photoPaths.length > 0) {
        const fileIds = task.uploadedFileIds || []
        while (fileIds.length < task.photoPaths.length) {
          const r = await uploadImage(task.photoPaths[fileIds.length]) // 按序续传未完成的
          fileIds.push(r.fileId)
          task.uploadedFileIds = fileIds.slice()
          persist(failed.concat([task], queue.slice(idx + 1)))
        }
        task.data.photoFileIds = (task.data.photoFileIds || []).concat(fileIds)
        task.photoPaths = undefined
        task.uploadedFileIds = undefined
      }

      const uploads = Array.isArray(task.uploads) ? task.uploads : []
      for (let uIdx = 0; uIdx < uploads.length; uIdx++) {
        const upload = uploads[uIdx]
        if (!upload.fileId) {
          const r = await uploadImage(upload.path)
          upload.fileId = r.fileId
          persist(failed.concat([task], queue.slice(idx + 1)))
        }
        task.data[upload.field] = upload.fileId
      }
      await request({
        url: task.url,
        method: task.method,
        data: task.data,
        // 首次任务可显式固定 key（签退 co_<orderId>）；通用任务复用任务 id。
        idempotencyKey: task.idempotencyKey || task.id
      })
      success++
      persist(failed.concat(queue.slice(idx + 1)))
    } catch (e) {
      failed.push(task)
      persist(failed.concat(queue.slice(idx)))
    }
  }
  persist(failed)
  return { success, fail: failed.length }
}

function flushQueue() {
  if (flushing) return flushing
  flushing = doFlushQueue().finally(() => { flushing = null })
  return flushing
}

module.exports = {
  QUEUE_KEY,
  cacheOrderSnapshot,
  getCachedOrder,
  listCachedOrders,
  clearOrderCache,
  enqueue,
  getQueue,
  flushQueue
}
