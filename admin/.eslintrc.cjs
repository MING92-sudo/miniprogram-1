/*
 * 管理端独立 ESLint（docs/09 §七.2：admin 目录纳入 eslint）。
 * 与小程序根配置分离：本目录为浏览器环境（无 wx 全局），ES2022。
 */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true
  },
  extends: ['plugin:vue/vue3-essential'],
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module'
  },
  globals: {
    defineProps: 'readonly',
    defineEmits: 'readonly',
    defineExpose: 'readonly'
  },
  rules: {
    'vue/multi-word-component-names': 'off',
    'no-unused-vars': ['error', { argsIgnorePattern: '^_' }]
  },
  ignorePatterns: ['dist/**', 'node_modules/**']
}
