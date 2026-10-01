<template>
  <el-result icon="warning" title="403" sub-title="该账号无权访问此页面（管理端写操作需维保部管理员权限）">
    <template #extra>
      <el-button type="primary" @click="$router.push('/dashboard')">返回看板</el-button>
    </template>
  </el-result>
</template>
