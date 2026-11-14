// 骨架屏（M2，屏 14）：首屏占位，纯 CSS 扫光（.skel），reduced-motion 静态降级；rows=[{h,flex}]。
Component({
  options: { addGlobalClass: true },
  properties: {
    rows: { type: Array, value: [] }
  }
})