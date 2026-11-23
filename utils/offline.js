// 离线缓存与补传- em_order_<id>：按工单缓存本地快照（检查项填写等），弱网时先保本地- offline_queue：待补传请求队列，网络恢复时自动逐条重放（复用任务 id 作幂等键）
const { request } = require('./request')
const { uuid } = require('./util')
const { getAccountId } = require('./auth')
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

function readQueue() {
  return wx.getStorageSync(QUEUE_KEY) || []
}

// 重放时自动携带幂等键uploads: [{ field, path, fileId? }]，用于签名等单文件字段先补传再回填业务 data。
function enqueue(task) {
  const queue = readQueue()
  const ownerId = getAccountId()
  if (!ownerId) {
    wx.showToast({ title: '请先登录后再保存离线任务', icon: 'none' })
    return queue.length
  }
  const taskFull = Object.assign({ id: uuid(), method: 'POST', createdAt: Date.now() }, task, { ownerId })
  // 仅替换同账号同请求，避免换号后的入队覆盖他人待补传任务。
  const idx = queue.findIndex((t) => t.ownerId === ownerId && t.url === taskFull.url && t.method === taskFull.method)
  if (idx > -1) queue[idx] = taskFull
  else queue.push(taskFull)
  wx.setStorageSync(QUEUE_KEY, queue)
  return queue.length
}

// 默认只展示当前账号任务；换号后不得看到或补传上一个账号的数据。
function getQueue() {
  const ownerId = getAccountId()
  return ownerId ? readQueue().filter((t) => t.ownerId === ownerId) : []
}

// 无属主旧任务（升级前入队）永不自动补传，仅供人工确认删除。
function getOrphanQueue() {
  return readQueue().filter((t) => !t.ownerId)
}

function removeOrphanTask(taskId) {
  const queue = readQueue()
  const next = queue.filter((t) => !(t.id === taskId && !t.ownerId))
  wx.setStorageSync(QUEUE_KEY, next)
}

function requireTaskOwner(task) {
  if (getAccountId() !== task.ownerId) {
    throw new Error('账号已切换，任务已保留')
  }
}

let flushing = null

// 逐条重放：成功一条移除一条；失败保留。持久化时合并快照之后的新任务，禁止旧快照覆盖新入队数据。
async function doFlushQueue() {
  const ownerId = getAccountId()
  if (!ownerId) return { success: 0, fail: 0 }
  const queue = readQueue().filter((t) => t.ownerId === ownerId)
  const snapshotIds = new Set(queue.map((t) => t.id))
  let success = 0
  const failed = []
  const persist = (nextQueue) => {
    // 快照外的任务含其他账号/无属主旧任务及 flush 期间新入队任务，原样合并保留。
    const added = readQueue().filter((t) => !snapshotIds.has(t.id))
    wx.setStorageSync(QUEUE_KEY, nextQueue.concat(added))
  }
  for (let idx = 0; idx < queue.length; idx++) {
    const task = queue[idx]
    // 启动快照不代表发送时仍是同一账号：退出/换号后剩余任务必须保留待原属主补传。
    if (getAccountId() !== task.ownerId) {
      failed.push(task)
      persist(failed.concat(queue.slice(idx + 1)))
      continue
    }
    try {
      if (Array.isArray(task.photoPaths) && task.photoPaths.length > 0) {
        const fileIds = task.uploadedFileIds || []
        while (fileIds.length < task.photoPaths.length) {
          const r = await uploadImage(task.photoPaths[fileIds.length]) // 按序续传未完成的
          requireTaskOwner(task)
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
          requireTaskOwner(task)
          upload.fileId = r.fileId
          persist(failed.concat([task], queue.slice(idx + 1)))
        }
        task.data[upload.field] = upload.fileId
      }
      requireTaskOwner(task)
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
  getOrphanQueue,
  removeOrphanTask,
  flushQueue
}
