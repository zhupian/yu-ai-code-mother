<template>
  <a-layout class="basic-layout" :class="{ 'basic-layout--full-page': isFullPageRoute }">
    <!-- 首页和登录注册页使用沉浸式布局，避免全局头尾与侧栏/卡片视觉冲突。 -->
    <GlobalHeader v-if="!isFullPageRoute" />
    <a-layout-content class="main-content">
      <router-view />
    </a-layout-content>
    <GlobalFooter v-if="!isFullPageRoute" />
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()

const isFullPageRoute = computed(() =>
  ['/', '/user/login', '/user/register'].includes(route.path),
)
</script>

<style scoped>
.basic-layout {
  min-height: 100vh;
  background: none;
}

.basic-layout--full-page {
  background: #fff;
}

.main-content {
  width: 100%;
  padding: 0;
  background: none;
  margin: 0;
}
</style>
