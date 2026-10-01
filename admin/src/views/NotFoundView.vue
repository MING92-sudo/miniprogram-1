<template>
  <el-result icon="info" title="404" sub-title="页面不存在">
    <template #extra>
      <el-button type="primary" @click="$router.push('/dashboard')">返回看板</el-button>
    </template>
  </el-result>
</template>
