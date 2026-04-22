/**
 * 主页工作台的历史会话中转工具。
 *
 * 约束：
 * - `/app/chat/:id` 不再作为主要页面存在，最终地址必须回到 `/`。
 * - 旧入口、后台表格、编辑页等跨路由来源仍需要把 appId 带回主页。
 * - 使用 sessionStorage 只保存一次性 appId，避免刷新后反复覆盖主页当前状态。
 */
export const WORKBENCH_APP_ID_STORAGE_KEY = 'chatcode.pendingWorkbenchAppId'

export const rememberWorkbenchAppId = (appId: unknown) => {
  if (typeof window === 'undefined' || appId === undefined || appId === null) {
    return
  }

  const normalizedAppId = Array.isArray(appId) ? appId[0] : appId
  if (normalizedAppId === undefined || normalizedAppId === null || normalizedAppId === '') {
    return
  }

  window.sessionStorage.setItem(WORKBENCH_APP_ID_STORAGE_KEY, String(normalizedAppId))
}

export const takeRememberedWorkbenchAppId = () => {
  if (typeof window === 'undefined') {
    return undefined
  }

  const appId = window.sessionStorage.getItem(WORKBENCH_APP_ID_STORAGE_KEY) || undefined
  window.sessionStorage.removeItem(WORKBENCH_APP_ID_STORAGE_KEY)
  return appId
}
