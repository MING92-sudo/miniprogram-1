// 同步状态注（M2，屏 01）：监管平台数据同步状态
Component({
  options: { addGlobalClass: true },
  properties: {
    title: { type: String, value: '监管平台数据同步正常' },
    desc: { type: String, value: '' },
    status: { type: String, value: '已同步' }
  }
})