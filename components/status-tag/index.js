// 状态标签（M2）：tone∈amber/signal/ok/dim，映射原型 .tag-*（状态色：橙红=困人急修/黄=进行中/绿=合格/灰=待办）
Component({
  options: { addGlobalClass: true },
  properties: {
    text: { type: String, value: '' },
    tone: { type: String, value: 'dim' }
  }
})