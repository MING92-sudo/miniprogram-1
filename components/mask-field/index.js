// 脱敏字段渲染（M2，规格 §5.5）：服务端掩码值 + △ 标记，前端不解密、不接收明文。
Component({
  options: { addGlobalClass: true },
  properties: {
    value: { type: String, value: '' },
    masked: { type: Boolean, value: false }
  }
})