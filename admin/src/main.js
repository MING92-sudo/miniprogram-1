import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './ui'  // M2 自建组件库 token 层；M5 已移除 Element Plus

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')