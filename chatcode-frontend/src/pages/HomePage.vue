<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  addApp,
  deployApp as deployAppApi,
  getAppVoById,
  listGoodAppVoByPage,
  listMyAppVoByPage,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { userLogout } from '@/api/userController'
import request from '@/request'
import { API_BASE_URL, getDeployUrl, getStaticPreviewUrl } from '@/config/env'
import { CodeGenTypeEnum } from '@/utils/codeGenTypes'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'
import { takeRememberedWorkbenchAppId } from '@/utils/workbenchNavigation'
import AppCard from '@/components/AppCard.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'

type ActivePanel = 'myApps' | 'featured' | null

interface Message {
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  createTime?: string
}

const router = useRouter()
const loginUserStore = useLoginUserStore()

// 用户输入的应用需求。主页默认保持极简，只在点击快捷入口后展开作品/案例。
const userPrompt = ref('')
const creating = ref(false)
const isGenerating = ref(false)
const sidebarOpen = ref(false)
const sidebarCollapsed = ref(false)
const activePanel = ref<ActivePanel>(null)

// 最近应用：既作为左侧历史来源，也作为点击「作品」后的展示数据。
const myApps = ref<API.AppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 8,
  total: 0,
})

// 精选案例只在用户点击「案例」后展示；数据仍可预加载，保证交互顺滑。
const featuredApps = ref<API.AppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

// 首页内生成相关状态：后端接口仍需要 appId，但不会再跳转到 /app/chat/:id。
const currentAppId = ref<string>()
const appInfo = ref<API.AppVO>()
const generatedContent = ref('')
const generatedPrompt = ref('')
const codeStreamRef = ref<HTMLElement>()
const messages = ref<Message[]>([])
const userInput = ref('')
const messagesContainer = ref<HTMLElement>()
const loadingWorkspace = ref(false)
const loadingHistory = ref(false)
const hasMoreHistory = ref(false)
const lastCreateTime = ref<string>()
const historyLoaded = ref(false)

// 预览、下载、部署、编辑相关状态。
const previewOpen = ref(false)
const previewUrl = ref('')
const previewReady = ref(false)
const downloading = ref(false)
const deploying = ref(false)
const deployModalVisible = ref(false)
const deployUrl = ref('')
const isEditMode = ref(false)
const selectedElementInfo = ref<ElementInfo | null>(null)

const promptInputRef = ref()
const panelRef = ref<HTMLElement>()

const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo: ElementInfo) => {
    selectedElementInfo.value = elementInfo
  },
})

const handlePreviewMessage = (event: MessageEvent) => {
  visualEditor.handleIframeMessage(event)
}

const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const displayName = computed(() => loginUserStore.loginUser.userName || '我的空间')
const historyApps = computed(() => myApps.value.slice(0, 8))
const hasGeneratedContent = computed(() => generatedContent.value.trim().length > 0)
const isOwner = computed(() => appInfo.value?.userId === loginUserStore.loginUser.id)
const workspaceActive = computed(() =>
  Boolean(currentAppId.value || messages.value.length || hasGeneratedContent.value || isGenerating.value),
)
const composerValue = computed({
  get: () => (currentAppId.value ? userInput.value : userPrompt.value),
  set: (value: string) => {
    if (currentAppId.value) {
      userInput.value = value
    } else {
      userPrompt.value = value
    }
  },
})
const composerDisabled = computed(() =>
  creating.value || isGenerating.value || Boolean(currentAppId.value && !isOwner.value),
)

const extractedCode = computed(() => {
  const content = generatedContent.value
  const fencedCodeMatch = content.match(/```(?:html|vue|javascript|js|css|typescript|ts)?\s*([\s\S]*?)```/i)
  return (fencedCodeMatch?.[1] || content).trim()
})

const disableEditModeIfNeeded = () => {
  if (isEditMode.value) {
    visualEditor.disableEditMode()
    isEditMode.value = false
  }
}

const resetHistoryState = () => {
  messages.value = []
  loadingHistory.value = false
  hasMoreHistory.value = false
  lastCreateTime.value = undefined
  historyLoaded.value = false
}

const resetWorkspaceState = (options: { keepPrompt?: boolean } = {}) => {
  const promptSnapshot = userPrompt.value
  disableEditModeIfNeeded()
  currentAppId.value = undefined
  appInfo.value = undefined
  generatedContent.value = ''
  generatedPrompt.value = ''
  userInput.value = ''
  previewOpen.value = false
  previewUrl.value = ''
  previewReady.value = false
  deployUrl.value = ''
  selectedElementInfo.value = null
  resetHistoryState()
  if (!options.keepPrompt) {
    userPrompt.value = ''
  } else {
    userPrompt.value = promptSnapshot
  }
}

const focusPrompt = async () => {
  if (creating.value || isGenerating.value) {
    message.warning('当前正在生成，请稍后再开启新对话')
    return
  }
  resetWorkspaceState()
  sidebarOpen.value = false
  activePanel.value = null
  await nextTick()
  promptInputRef.value?.focus?.()
}

const submitComposer = () => {
  if (currentAppId.value) {
    sendMessage()
    return
  }
  createApp()
}

const revealPanel = async (panel: Exclude<ActivePanel, null>) => {
  sidebarOpen.value = false
  activePanel.value = panel
  if (panel === 'featured' && featuredApps.value.length === 0) {
    await loadFeaturedApps()
  }
  await nextTick()
  panelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const goAdmin = (path: string) => {
  sidebarOpen.value = false
  router.push(path)
}

const toggleSidebarCollapsed = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}

const scrollCodeToBottom = async () => {
  await nextTick()
  if (codeStreamRef.value) {
    codeStreamRef.value.scrollTop = codeStreamRef.value.scrollHeight
  }
}

const scrollMessagesToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 创建应用：只拿 appId，不再跳转路由；随后直接在首页用 SSE 流式生成代码。
const createApp = async () => {
  const prompt = userPrompt.value.trim()
  if (!prompt || creating.value || isGenerating.value) {
    if (!prompt) {
      message.warning('请输入应用描述')
    }
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }

  creating.value = true
  resetWorkspaceState({ keepPrompt: true })
  activePanel.value = null
  generatedPrompt.value = prompt

  try {
    const res = await addApp({ initPrompt: prompt })
    if (res.data.code === 0 && res.data.data) {
      currentAppId.value = String(res.data.data)
      await fetchCurrentAppInfo()
      await sendInitialMessage(prompt)
      await loadMyApps()
    } else {
      message.error('创建失败：' + res.data.message)
    }
  } catch (error) {
    console.error('创建应用失败：', error)
    message.error('创建失败，请重试')
  } finally {
    creating.value = false
  }
}

const sendInitialMessage = async (prompt: string) => {
  messages.value.push({
    type: 'user',
    content: prompt,
  })

  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  generatedPrompt.value = prompt
  generatedContent.value = ''
  await scrollMessagesToBottom()
  await generateCode(prompt, aiMessageIndex)
}

const clearSelectedElement = () => {
  selectedElementInfo.value = null
  visualEditor.clearSelection()
}

const sendMessage = async () => {
  if (!currentAppId.value) {
    message.warning('请先选择或创建一个应用')
    return
  }
  if (!isOwner.value) {
    message.warning('无法在别人的作品下继续对话')
    return
  }
  if (!userInput.value.trim() || isGenerating.value) {
    return
  }

  let prompt = userInput.value.trim()
  if (selectedElementInfo.value) {
    let elementContext = `\n\n选中元素信息：`
    if (selectedElementInfo.value.pagePath) {
      elementContext += `\n- 页面路径: ${selectedElementInfo.value.pagePath}`
    }
    elementContext += `\n- 标签: ${selectedElementInfo.value.tagName.toLowerCase()}\n- 选择器: ${
      selectedElementInfo.value.selector
    }`
    if (selectedElementInfo.value.textContent) {
      elementContext += `\n- 当前内容: ${selectedElementInfo.value.textContent.substring(0, 100)}`
    }
    prompt += elementContext
  }

  userInput.value = ''
  messages.value.push({
    type: 'user',
    content: prompt,
  })

  if (selectedElementInfo.value) {
    clearSelectedElement()
    disableEditModeIfNeeded()
  }

  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  generatedPrompt.value = prompt
  generatedContent.value = ''
  await scrollMessagesToBottom()
  await generateCode(prompt, aiMessageIndex)
}

const getInputPlaceholder = () => {
  if (selectedElementInfo.value) {
    return `正在编辑 ${selectedElementInfo.value.tagName.toLowerCase()} 元素，描述你想要的修改...`
  }
  if (currentAppId.value) {
    return isOwner.value ? '继续描述你的修改或新需求，主页会在当前工作台继续生成' : '无法在别人的作品下继续对话'
  }
  return '给 ChatCode 发送消息，例如：帮我生成一个带后台管理的企业官网'
}

const generateCode = async (prompt: string, aiMessageIndex?: number) => {
  if (!currentAppId.value) {
    message.error('应用ID不存在')
    return
  }

  let eventSource: EventSource | null = null
  let streamCompleted = false
  isGenerating.value = true

  try {
    const baseURL = request.defaults.baseURL || API_BASE_URL
    const params = new URLSearchParams({
      appId: currentAppId.value,
      message: prompt,
    })
    const url = `${baseURL}/app/chat/gen/code?${params}`

    eventSource = new EventSource(url, {
      withCredentials: true,
    })

    eventSource.onmessage = async (event) => {
      if (streamCompleted) return
      try {
        const parsed = JSON.parse(event.data)
        const content = parsed.d
        if (content !== undefined && content !== null) {
          generatedContent.value += content
          if (aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
            messages.value[aiMessageIndex].content += content
            messages.value[aiMessageIndex].loading = false
          }
          await scrollCodeToBottom()
          await scrollMessagesToBottom()
        }
      } catch (error) {
        console.error('解析消息失败:', error)
        handleGenerateError(error, aiMessageIndex)
      }
    }

    eventSource.addEventListener('done', async () => {
      if (streamCompleted) return
      streamCompleted = true
      isGenerating.value = false
      if (aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
        messages.value[aiMessageIndex].loading = false
      }
      eventSource?.close()
      await afterGenerateFinished()
    })

    eventSource.onerror = async () => {
      if (streamCompleted || !isGenerating.value) return
      if (eventSource?.readyState === EventSource.CONNECTING) {
        streamCompleted = true
        isGenerating.value = false
        if (aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
          messages.value[aiMessageIndex].loading = false
        }
        eventSource?.close()
        await afterGenerateFinished()
      } else {
        handleGenerateError(new Error('SSE连接错误'), aiMessageIndex)
      }
    }
  } catch (error) {
    console.error('创建 EventSource 失败：', error)
    handleGenerateError(error, aiMessageIndex)
  }
}

const afterGenerateFinished = async () => {
  // 后端通常在流结束后落盘，稍等一下再刷新应用信息和预览地址。
  setTimeout(async () => {
    await fetchCurrentAppInfo()
    updatePreviewUrl()
    await loadMyApps()
  }, 1000)
}

const handleGenerateError = (error: unknown, aiMessageIndex?: number) => {
  console.error('生成代码失败：', error)
  isGenerating.value = false
  if (!generatedContent.value) {
    generatedContent.value = '抱歉，生成过程中出现了错误，请重试。'
  }
  if (aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
    messages.value[aiMessageIndex].content = generatedContent.value
    messages.value[aiMessageIndex].loading = false
  }
  message.error('生成失败，请重试')
}

const fetchCurrentAppInfo = async () => {
  if (!currentAppId.value) return
  try {
    const res = await getAppVoById({ id: currentAppId.value as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data
      return true
    }
  } catch (error) {
    console.error('获取应用信息失败：', error)
  }
  return false
}

const syncGeneratedContentFromMessages = () => {
  const latestAiMessage = [...messages.value].reverse().find((item) => item.type === 'ai' && item.content.trim())
  const latestUserMessage = [...messages.value]
    .reverse()
    .find((item) => item.type === 'user' && item.content.trim())
  generatedContent.value = latestAiMessage?.content || ''
  generatedPrompt.value = latestUserMessage?.content || appInfo.value?.initPrompt || ''
}

const loadChatHistory = async (isLoadMore = false) => {
  if (!currentAppId.value || loadingHistory.value) return

  if (!isLoadMore) {
    resetHistoryState()
    generatedContent.value = ''
    generatedPrompt.value = appInfo.value?.initPrompt || ''
  }

  loadingHistory.value = true
  try {
    const params: API.listAppChatHistoryParams = {
      appId: currentAppId.value as unknown as number,
      pageSize: 10,
    }
    // 历史接口按时间倒序返回；加载更多时传当前最老一条记录的 createTime 作为游标。
    if (isLoadMore && lastCreateTime.value) {
      params.lastCreateTime = lastCreateTime.value
    }

    const res = await listAppChatHistory(params)
    if (res.data.code === 0 && res.data.data) {
      const chatHistories = res.data.data.records || []
      if (chatHistories.length > 0) {
        const historyMessages: Message[] = chatHistories
          .map((chat) => ({
            type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
            content: chat.message || '',
            createTime: chat.createTime,
          }))
          .reverse()

        if (isLoadMore) {
          messages.value.unshift(...historyMessages)
        } else {
          messages.value = historyMessages
        }

        lastCreateTime.value = chatHistories[chatHistories.length - 1]?.createTime
        hasMoreHistory.value = chatHistories.length === 10
        syncGeneratedContentFromMessages()
        await scrollMessagesToBottom()
      } else if (!isLoadMore) {
        messages.value = []
        hasMoreHistory.value = false
      } else {
        hasMoreHistory.value = false
      }
      historyLoaded.value = true
    } else {
      message.error('加载对话历史失败：' + res.data.message)
    }
  } catch (error) {
    console.error('加载对话历史失败：', error)
    message.error('加载对话历史失败')
  } finally {
    loadingHistory.value = false
  }
}

const loadMoreHistory = async () => {
  await loadChatHistory(true)
}

const loadWorkspaceApp = async (appId: string | number | undefined) => {
  if (!appId || loadingWorkspace.value) return
  if (isGenerating.value) {
    message.warning('当前正在生成，请稍后再切换历史对话')
    return
  }

  loadingWorkspace.value = true
  resetWorkspaceState()
  sidebarOpen.value = false
  activePanel.value = null
  currentAppId.value = String(appId)

  try {
    const loaded = await fetchCurrentAppInfo()
    if (!loaded) {
      message.error('获取应用信息失败')
      resetWorkspaceState()
      return
    }

    await loadChatHistory()

    if (messages.value.length > 0 || generatedContent.value) {
      updatePreviewUrl()
    }

    // 与旧详情页保持一致：自己的空应用首次进入时，自动用初始提示词触发一次生成。
    if (appInfo.value?.initPrompt && isOwner.value && messages.value.length === 0 && historyLoaded.value) {
      await sendInitialMessage(appInfo.value.initPrompt)
    }
  } catch (error) {
    console.error('加载工作台应用失败：', error)
    message.error('加载历史对话失败，请重试')
    resetWorkspaceState()
  } finally {
    loadingWorkspace.value = false
  }
}

const updatePreviewUrl = () => {
  if (!currentAppId.value) return
  const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
  previewUrl.value = getStaticPreviewUrl(codeGenType, currentAppId.value)
  previewReady.value = false
}

const copyCode = async () => {
  if (!hasGeneratedContent.value) {
    message.warning('暂无可复制的代码')
    return
  }
  try {
    await navigator.clipboard.writeText(extractedCode.value)
    message.success('代码已复制')
  } catch (error) {
    console.error('复制失败：', error)
    message.error('复制失败')
  }
}

const downloadCode = async () => {
  if (!currentAppId.value) {
    message.error('应用ID不存在')
    return
  }
  downloading.value = true
  try {
    const baseURL = request.defaults.baseURL || ''
    const url = `${baseURL}/app/download/${currentAppId.value}`
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`下载失败: ${response.status}`)
    }
    const contentDisposition = response.headers.get('Content-Disposition')
    const fileName = contentDisposition?.match(/filename="(.+)"/)?.[1] || `app-${currentAppId.value}.zip`
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    console.error('下载失败：', error)
    message.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

const runPreview = async () => {
  if (!currentAppId.value) {
    message.warning('请先生成代码')
    return
  }
  if (isGenerating.value) {
    message.warning('代码还在生成中，请稍后运行')
    return
  }
  await fetchCurrentAppInfo()
  updatePreviewUrl()
  previewOpen.value = true
  sidebarCollapsed.value = true
}

const deployApp = async () => {
  if (!currentAppId.value) {
    message.error('应用ID不存在')
    return
  }
  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: currentAppId.value as unknown as number,
    })
    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } catch (error) {
    console.error('部署失败：', error)
    message.error('部署失败，请重试')
  } finally {
    deploying.value = false
  }
}

const openInNewTab = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank')
  }
}

const openDeployedSite = () => {
  if (deployUrl.value) {
    window.open(deployUrl.value, '_blank')
  }
}

const onIframeLoad = () => {
  previewReady.value = true
  const iframe = document.querySelector('.home-preview-iframe') as HTMLIFrameElement
  if (iframe) {
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
  }
}

const toggleEditMode = () => {
  const iframe = document.querySelector('.home-preview-iframe') as HTMLIFrameElement
  if (!iframe) {
    message.warning('请等待页面加载完成')
    return
  }
  if (!previewReady.value) {
    message.warning('请等待页面加载完成')
    return
  }
  const newEditMode = visualEditor.toggleEditMode()
  isEditMode.value = newEditMode
}

const closePreview = () => {
  disableEditModeIfNeeded()
  previewOpen.value = false
}

const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    myApps.value = []
    myAppsPage.total = 0
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载我的作品失败：', error)
  }
}

const loadFeaturedApps = async () => {
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载精选案例失败：', error)
  }
}

const viewChat = async (appId: string | number | undefined) => {
  if (appId) {
    await loadWorkspaceApp(appId)
  }
}

const viewWork = (app: API.AppVO) => {
  if (app.deployKey) {
    window.open(getDeployUrl(app.deployKey), '_blank')
  }
}

onMounted(async () => {
  window.addEventListener('message', handlePreviewMessage)
  await loadMyApps()
  loadFeaturedApps()
  const rememberedAppId = takeRememberedWorkbenchAppId()
  if (rememberedAppId) {
    await loadWorkspaceApp(rememberedAppId)
  }
})

onUnmounted(() => {
  window.removeEventListener('message', handlePreviewMessage)
})
</script>

<template>
  <div
    id="homePage"
    :class="{
      'sidebar-open': sidebarOpen,
      'sidebar-collapsed': sidebarCollapsed,
      'preview-open': previewOpen,
    }"
  >
    <aside class="home-sidebar">
      <div class="brand-row">
        <RouterLink to="/" class="brand-mark">
          <span class="brand-logo">鲸</span>
          <span class="sidebar-expanded-only">chatcode</span>
        </RouterLink>
        <button
          class="icon-button collapse-button"
          type="button"
          :title="sidebarCollapsed ? '展开导航栏' : '收起导航栏'"
          @click="toggleSidebarCollapsed"
        >
          {{ sidebarCollapsed ? '☰' : '▯' }}
        </button>
      </div>

      <button class="new-chat" type="button" :title="sidebarCollapsed ? '开启新对话' : undefined" @click="focusPrompt">
        <span>⊕</span>
        <span class="sidebar-expanded-only">开启新对话</span>
      </button>

      <div class="side-scroll">
        <section class="side-section history-primary">
          <p class="section-label">历史对话</p>
          <button
            v-for="app in historyApps"
            :key="app.id"
            class="history-item"
            :class="{
              active: currentAppId === String(app.id),
              loading: loadingWorkspace && currentAppId === String(app.id),
            }"
            type="button"
            @click="viewChat(app.id)"
          >
            <span class="history-title">{{ app.appName || '未命名应用' }}</span>
            <span class="history-meta">
              <span>{{ app.codeGenType || '应用' }}</span>
              <span>{{ currentAppId === String(app.id) ? '当前会话' : '继续对话' }}</span>
            </span>
          </button>
          <div v-if="loginUserStore.loginUser.id && historyApps.length === 0" class="empty-history">
            暂无历史对话
          </div>
          <button
            v-if="!loginUserStore.loginUser.id"
            class="empty-history login-link"
            type="button"
            @click="router.push('/user/login')"
          >
            登录后查看历史
          </button>
        </section>

        <section class="side-section">
          <p class="section-label">快捷入口</p>
          <div class="quick-links">
            <button
              class="nav-item"
              :class="{ active: activePanel === 'myApps' }"
              type="button"
              @click="revealPanel('myApps')"
            >
              <span class="nav-icon">🧩</span><span>作品</span>
            </button>
            <button
              class="nav-item"
              :class="{ active: activePanel === 'featured' }"
              type="button"
              @click="revealPanel('featured')"
            >
              <span class="nav-icon">🌍</span><span>案例</span>
            </button>
            <template v-if="isAdmin">
              <button class="nav-item" type="button" @click="goAdmin('/admin/appManage')">
                <span class="nav-icon">🛠</span><span>应用</span>
              </button>
              <button class="nav-item" type="button" @click="goAdmin('/admin/userManage')">
                <span class="nav-icon">👥</span><span>用户</span>
              </button>
              <button class="nav-item" type="button" @click="goAdmin('/admin/chatManage')">
                <span class="nav-icon">💬</span><span>对话</span>
              </button>
            </template>
          </div>
        </section>
      </div>

      <div class="sidebar-footer">
        <a-dropdown v-if="loginUserStore.loginUser.id" trigger="click" placement="topLeft">
          <button class="user-trigger" type="button" :title="sidebarCollapsed ? displayName : undefined">
            <span class="avatar-line">
              <a-avatar :src="loginUserStore.loginUser.userAvatar" :size="34">👤</a-avatar>
              <span class="sidebar-expanded-only">{{ displayName }}</span>
            </span>
            <span class="sidebar-expanded-only">•••</span>
          </button>
          <template #overlay>
            <a-menu>
              <a-menu-item @click="doLogout">退出登录</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <RouterLink v-else to="/user/login" class="footer-link">
          <span>👤</span>
          <span class="sidebar-expanded-only">登录</span>
        </RouterLink>
      </div>
    </aside>

    <main class="workspace">
      <div class="mobile-topbar">
        <button class="icon-button" type="button" @click="sidebarOpen = true">☰</button>
        <RouterLink to="/" class="brand-mark">
          <span class="brand-logo">鲸</span>
          <span>chatcode</span>
        </RouterLink>
        <button class="icon-button" type="button" @click="focusPrompt">＋</button>
      </div>

      <section class="hero" :class="{ 'hero--with-panel': activePanel || workspaceActive }">
        <h1 class="hero-title">
          <span class="brand-logo">鲸</span>
          <span>{{ currentAppId ? '继续完善你的应用' : '开始创建你的应用' }}</span>
        </h1>

        <div class="composer">
          <a-textarea
            ref="promptInputRef"
            v-model:value="composerValue"
            :placeholder="getInputPlaceholder()"
            :maxlength="1000"
            :bordered="false"
            class="composer-input"
            :disabled="composerDisabled"
            @keydown.enter.ctrl.prevent="submitComposer"
          />
          <div class="composer-actions">
            <a-button
              type="primary"
              shape="circle"
              class="send-button"
              :loading="creating || isGenerating"
              :disabled="composerDisabled"
              @click="submitComposer"
            >
              <template #icon>↑</template>
            </a-button>
          </div>
        </div>

        <div class="hint-line">
          {{
            currentAppId
              ? '历史对话已在主页内打开，继续输入即可追加生成；运行预览会在右侧侧边栏打开。'
              : '输入一句话描述需求，代码会在当前主页内持续生成，不会跳转页面。'
          }}
        </div>
      </section>

      <section v-if="hasGeneratedContent || isGenerating" class="code-panel">
        <div class="answer-text">
          {{ generatedPrompt ? `以下是「${generatedPrompt}」的生成结果：` : '以下是生成结果：' }}
        </div>
        <div class="code-card">
          <div class="code-toolbar">
            <span class="code-language">{{ appInfo?.codeGenType || 'html' }}</span>
            <div class="code-actions">
              <button type="button" @click="copyCode" :disabled="!hasGeneratedContent">复制</button>
              <button type="button" @click="downloadCode" :disabled="!currentAppId" :class="{ loading: downloading }">
                {{ downloading ? '下载中' : '下载' }}
              </button>
              <button type="button" @click="runPreview" :disabled="!currentAppId || isGenerating">运行</button>
              <button type="button" @click="deployApp" :disabled="!currentAppId || isGenerating" :class="{ loading: deploying }">
                {{ deploying ? '部署中' : '部署' }}
              </button>
            </div>
          </div>
          <pre ref="codeStreamRef" class="code-stream"><code>{{ extractedCode || generatedContent }}</code><span v-if="isGenerating" class="cursor">|</span></pre>
        </div>
      </section>

      <section v-if="workspaceActive" class="chat-panel">
        <div class="workbench-summary">
          <div>
            <p>当前工作台</p>
            <h2>{{ appInfo?.appName || (currentAppId ? `应用 ${currentAppId}` : '新对话') }}</h2>
          </div>
          <div class="workbench-badges">
            <span>{{ appInfo?.codeGenType || '应用' }}</span>
            <span v-if="currentAppId && !isOwner">只读</span>
            <span v-else-if="currentAppId">可继续生成</span>
          </div>
        </div>

        <div v-if="selectedElementInfo" class="selected-element-card">
          <div>
            <strong>已选中 {{ selectedElementInfo.tagName.toLowerCase() }} 元素</strong>
            <p>
              <span v-if="selectedElementInfo.pagePath">页面：{{ selectedElementInfo.pagePath }} · </span>
              选择器：{{ selectedElementInfo.selector }}
            </p>
          </div>
          <button type="button" @click="clearSelectedElement">清除选择</button>
        </div>

        <div class="chat-history-card">
          <div class="chat-history-toolbar">
            <div>
              <strong>对话内容</strong>
              <span>{{ messages.length ? `${messages.length} 条消息` : '等待开始' }}</span>
            </div>
            <button
              v-if="hasMoreHistory"
              type="button"
              class="load-more-history"
              :disabled="loadingHistory"
              @click="loadMoreHistory"
            >
              {{ loadingHistory ? '加载中...' : '加载更多历史' }}
            </button>
          </div>

          <div ref="messagesContainer" class="messages-container-home">
            <div v-if="loadingHistory && messages.length === 0" class="empty-chat">
              <a-spin />
              <span>正在加载历史对话...</span>
            </div>
            <div v-else-if="messages.length === 0" class="empty-chat">
              <span>暂无对话内容，继续输入即可开始生成。</span>
            </div>
            <template v-else>
              <div
                v-for="(chatMessage, index) in messages"
                :key="`${chatMessage.createTime || 'stream'}-${index}`"
                class="message-row"
                :class="`message-row--${chatMessage.type}`"
              >
                <a-avatar
                  v-if="chatMessage.type === 'ai'"
                  :src="aiAvatar"
                  class="message-avatar"
                />
                <div class="message-bubble">
                  <MarkdownRenderer
                    v-if="chatMessage.type === 'ai' && chatMessage.content"
                    :content="chatMessage.content"
                  />
                  <span v-else-if="chatMessage.content">{{ chatMessage.content }}</span>
                  <span v-if="chatMessage.loading" class="loading-indicator">
                    <a-spin size="small" />
                    AI 正在生成...
                  </span>
                </div>
                <a-avatar
                  v-if="chatMessage.type === 'user'"
                  :src="loginUserStore.loginUser.userAvatar"
                  class="message-avatar"
                >
                  👤
                </a-avatar>
              </div>
            </template>
          </div>
        </div>
      </section>

      <section v-if="activePanel" ref="panelRef" class="content-panel">
        <div v-if="activePanel === 'myApps'">
          <div class="section-heading">
            <h2>我的作品</h2>
            <p>这里展示你创建过的应用；主页默认不展示，点击左侧「作品」后展开。</p>
          </div>
          <div v-if="myApps.length" class="app-grid">
            <AppCard
              v-for="app in myApps"
              :key="app.id"
              :app="app"
              @view-chat="viewChat"
              @view-work="viewWork"
            />
          </div>
          <div v-else class="section-empty">暂无作品，先从上方开启一次新对话吧。</div>
          <div v-if="myAppsPage.total > myAppsPage.pageSize" class="pagination-wrapper">
            <a-pagination
              v-model:current="myAppsPage.current"
              v-model:page-size="myAppsPage.pageSize"
              :total="myAppsPage.total"
              :show-size-changer="false"
              :show-total="(total: number) => `共 ${total} 个应用`"
              @change="loadMyApps"
            />
          </div>
        </div>

        <div v-else-if="activePanel === 'featured'">
          <div class="section-heading">
            <h2>精选案例</h2>
            <p>这里展示精选案例；主页默认不展示，点击左侧「案例」后展开。</p>
          </div>
          <div v-if="featuredApps.length" class="app-grid">
            <AppCard
              v-for="app in featuredApps"
              :key="app.id"
              :app="app"
              :featured="true"
              @view-chat="viewChat"
              @view-work="viewWork"
            />
          </div>
          <div v-else class="section-empty">暂无精选案例。</div>
          <div v-if="featuredAppsPage.total > featuredAppsPage.pageSize" class="pagination-wrapper">
            <a-pagination
              v-model:current="featuredAppsPage.current"
              v-model:page-size="featuredAppsPage.pageSize"
              :total="featuredAppsPage.total"
              :show-size-changer="false"
              :show-total="(total: number) => `共 ${total} 个案例`"
              @change="loadFeaturedApps"
            />
          </div>
        </div>
      </section>
    </main>

    <aside v-if="previewOpen" class="preview-drawer">
      <div class="preview-header">
        <div>
          <h3>运行预览</h3>
          <p>{{ appInfo?.appName || '生成后的网页展示' }}</p>
        </div>
        <div class="preview-actions">
          <button
            v-if="previewUrl"
            type="button"
            class="preview-link"
            :class="{ danger: isEditMode }"
            @click="toggleEditMode"
          >
            {{ isEditMode ? '退出编辑' : '编辑模式' }}
          </button>
          <button v-if="previewUrl" type="button" class="preview-link" @click="openInNewTab">新窗口打开</button>
          <button type="button" class="preview-close" @click="closePreview">×</button>
        </div>
      </div>
      <div class="preview-body">
        <div v-if="!previewUrl" class="preview-placeholder">
          <a-spin />
          <p>正在准备预览...</p>
        </div>
        <iframe
          v-else
          :src="previewUrl"
          class="home-preview-iframe"
          frameborder="0"
          @load="onIframeLoad"
        ></iframe>
      </div>
    </aside>

    <DeploySuccessModal
      v-model:open="deployModalVisible"
      :deploy-url="deployUrl"
      @open-site="openDeployedSite"
    />
  </div>
</template>

<style scoped>
#homePage {
  --sidebar-width: 292px;
  --preview-width: 0px;
  --brand-strong: #315df4;
  --brand-soft: #eef4ff;
  --ink: #181c25;
  --line: #e9edf4;
  --shadow-input: 0 24px 64px rgba(24, 38, 78, 0.11);
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr) var(--preview-width);
  min-height: 100vh;
  transition: grid-template-columns 0.2s ease;
  color: var(--ink);
  background: linear-gradient(180deg, #fbfdff 0%, #ffffff 48%, #f8fbff 100%);
}

#homePage.sidebar-collapsed {
  --sidebar-width: 76px;
}

#homePage.preview-open {
  --preview-width: min(46vw, 760px);
}

.home-sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 22px 16px 18px;
  border-right: 1px solid var(--line);
  background: rgba(247, 249, 252, 0.94);
  backdrop-filter: blur(18px);
  transition:
    width 0.2s ease,
    padding 0.2s ease;
}

#homePage.sidebar-collapsed .home-sidebar {
  padding: 22px 12px 18px;
}

.sidebar-expanded-only {
  transition: opacity 0.16s ease;
}

#homePage.sidebar-collapsed .sidebar-expanded-only,
#homePage.sidebar-collapsed .section-label,
#homePage.sidebar-collapsed .side-scroll {
  display: none !important;
}

.brand-row,
.mobile-topbar,
.sidebar-footer,
.avatar-line,
.brand-mark,
.nav-item,
.history-item,
.composer-actions,
.hero-title {
  display: flex;
  align-items: center;
}

.brand-row {
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px 22px;
}

#homePage.sidebar-collapsed .brand-row {
  flex-direction: column;
  justify-content: flex-start;
  padding-bottom: 18px;
}

.brand-mark {
  gap: 10px;
  color: var(--brand-strong);
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 0.02em;
  text-decoration: none;
}

.brand-logo {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  color: #fff;
  background: linear-gradient(135deg, #2f5bff, #7ba1ff);
  box-shadow: 0 10px 24px rgba(49, 93, 244, 0.23);
  font-size: 18px;
  flex: 0 0 auto;
}

.icon-button {
  width: 34px;
  height: 34px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: #6f7582;
  cursor: pointer;
  transition: 0.18s ease;
}

.icon-button:hover {
  color: var(--brand-strong);
  border-color: #cbd8ff;
}

.new-chat {
  width: 100%;
  height: 48px;
  border: 1px solid #e5eaf4;
  border-radius: 999px;
  background: #fff;
  color: #20242c;
  font-size: 16px;
  box-shadow: 0 10px 24px rgba(18, 28, 48, 0.055);
  cursor: pointer;
  transition: 0.2s ease;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

#homePage.sidebar-collapsed .new-chat {
  width: 48px;
  padding: 0;
}

.new-chat:hover {
  border-color: #c8d5ff;
  color: var(--brand-strong);
  box-shadow: 0 14px 32px rgba(79, 120, 255, 0.12);
}

.side-scroll {
  flex: 1;
  overflow: auto;
  margin: 20px -6px 14px;
  padding: 0 6px;
}

.side-section {
  margin-bottom: 20px;
}

.section-label {
  margin: 0 0 10px;
  padding: 0 10px;
  color: #8b929e;
  font-size: 13px;
  font-weight: 700;
}

.history-item,
.nav-item {
  width: 100%;
  border: 0;
  text-align: left;
  cursor: pointer;
  transition: 0.18s ease;
}

.history-item {
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  margin-bottom: 4px;
  padding: 10px 12px;
  border-radius: 12px;
  color: #242832;
  background: transparent;
}

.history-item:hover,
.history-item.active {
  background: var(--brand-soft);
  color: var(--brand-strong);
}

.history-item.loading {
  opacity: 0.66;
  cursor: progress;
}

.history-title {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
}

.history-meta {
  display: flex;
  gap: 8px;
  color: #9aa1ad;
  font-size: 12px;
}

.empty-history {
  margin: 8px 10px;
  padding: 12px;
  border-radius: 12px;
  color: #9aa1ad;
  background: rgba(255, 255, 255, 0.65);
  font-size: 13px;
}

.login-link {
  width: calc(100% - 20px);
  border: 0;
  cursor: pointer;
  text-align: center;
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.quick-links .nav-item {
  justify-content: center;
  min-height: 36px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.62);
  color: #68707f;
  font-size: 13px;
  gap: 6px;
}

.quick-links .nav-item:hover,
.quick-links .nav-item.active {
  border-color: #d9e2ff;
  background: #fff;
  color: var(--brand-strong);
}

.sidebar-footer {
  justify-content: space-between;
  gap: 12px;
  padding: 12px 10px 0;
  border-top: 1px solid var(--line);
  color: #8d94a1;
}

#homePage.sidebar-collapsed .sidebar-footer {
  justify-content: center;
  padding-inline: 0;
}

.user-trigger {
  width: 100%;
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  border-radius: 12px;
  padding: 4px 6px;
  transition: 0.18s ease;
}

.user-trigger:hover {
  background: var(--brand-soft);
  color: var(--brand-strong);
}

.avatar-line {
  min-width: 0;
  gap: 10px;
}

.avatar-line span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.footer-link {
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--brand-strong);
  font-size: 13px;
  text-decoration: none;
}

.workspace {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.mobile-topbar {
  display: none;
  justify-content: space-between;
  height: 62px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(14px);
}

.hero {
  width: min(850px, calc(100% - 48px));
  min-height: 100vh;
  margin: 0 auto;
  padding: 72px 0 88px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  transition: min-height 0.2s ease;
}

.hero--with-panel {
  min-height: 42vh;
}

.hero-title {
  justify-content: center;
  gap: 16px;
  margin: 0 0 36px;
  text-align: center;
  font-size: clamp(28px, 4vw, 38px);
  font-weight: 850;
  letter-spacing: -0.04em;
}

.hero-title .brand-logo {
  width: 42px;
  height: 42px;
  border-radius: 16px;
  font-size: 23px;
}

.composer {
  width: min(820px, 100%);
  min-height: 118px;
  border: 1px solid #e2e7f0;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--shadow-input);
  overflow: hidden;
}

.composer-input {
  height: 70px !important;
  padding: 20px 24px 6px !important;
  color: #1f2430;
  font-size: 17px;
  resize: none !important;
  box-shadow: none !important;
}

.composer-actions {
  justify-content: flex-end;
  padding: 0 16px 14px;
}

.send-button {
  width: 40px !important;
  height: 40px !important;
  border: 0;
  background: linear-gradient(135deg, #7da0ff, #4d72ff) !important;
  box-shadow: 0 12px 22px rgba(79, 120, 255, 0.28);
  font-size: 20px;
  transition: 0.18s ease;
}

.send-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 16px 28px rgba(79, 120, 255, 0.34);
}

.hint-line {
  margin-top: 18px;
  color: #9299a6;
  font-size: 14px;
}

.code-panel,
.chat-panel,
.content-panel {
  width: min(1120px, calc(100% - 48px));
  margin: -12px auto 56px;
  scroll-margin-top: 24px;
}

.answer-text {
  margin: 0 0 14px;
  color: #242832;
  font-size: 16px;
  line-height: 1.7;
}

.code-card {
  overflow: hidden;
  border-radius: 18px;
  background: #f8fafc;
  border: 1px solid #edf1f7;
  box-shadow: 0 18px 52px rgba(24, 38, 78, 0.08);
}

.code-toolbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px 0 20px;
  border-bottom: 1px solid #edf1f7;
  background: rgba(255, 255, 255, 0.7);
}

.code-language {
  color: #111827;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 14px;
}

.code-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.code-actions button {
  border: 0;
  background: transparent;
  color: #8b929e;
  cursor: pointer;
  font-size: 14px;
  transition: 0.16s ease;
}

.code-actions button:hover:not(:disabled) {
  color: var(--brand-strong);
}

.code-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.code-stream {
  max-height: 520px;
  min-height: 260px;
  margin: 0;
  padding: 22px;
  overflow: auto;
  color: #111827;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.cursor {
  color: var(--brand-strong);
  animation: blink 1s steps(2, start) infinite;
}

.chat-panel {
  width: min(1120px, calc(100% - 48px));
  margin: -22px auto 56px;
}

.workbench-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 16px;
  padding: 18px 20px;
  border: 1px solid #e7ecf5;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 18px 48px rgba(24, 38, 78, 0.06);
}

.workbench-summary p,
.workbench-summary h2 {
  margin: 0;
}

.workbench-summary p {
  color: #8b929e;
  font-size: 13px;
  font-weight: 700;
}

.workbench-summary h2 {
  margin-top: 5px;
  color: #1e293b;
  font-size: clamp(20px, 3vw, 28px);
  font-weight: 780;
}

.workbench-badges {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.workbench-badges span {
  padding: 6px 10px;
  border: 1px solid #dbe5ff;
  border-radius: 999px;
  background: #f4f7ff;
  color: var(--brand-strong);
  font-size: 12px;
  font-weight: 700;
}

.selected-element-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid #cfe0ff;
  border-radius: 16px;
  background: #f5f8ff;
  color: #26406f;
}

.selected-element-card p {
  margin: 4px 0 0;
  color: #65728a;
  font-size: 13px;
}

.selected-element-card button,
.load-more-history {
  border: 0;
  border-radius: 999px;
  background: #fff;
  color: var(--brand-strong);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}

.selected-element-card button {
  padding: 8px 12px;
}

.chat-history-card {
  overflow: hidden;
  border: 1px solid #edf1f7;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 52px rgba(24, 38, 78, 0.075);
}

.chat-history-toolbar {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px;
  border-bottom: 1px solid #edf1f7;
  background: rgba(248, 250, 252, 0.72);
}

.chat-history-toolbar div {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.chat-history-toolbar strong {
  color: #1f2937;
}

.chat-history-toolbar span {
  color: #8b929e;
  font-size: 13px;
}

.load-more-history {
  padding: 7px 12px;
  border: 1px solid #dbe5ff;
}

.load-more-history:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.messages-container-home {
  max-height: 520px;
  overflow: auto;
  padding: 18px;
  scroll-behavior: smooth;
}

.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.message-row--user {
  justify-content: flex-end;
}

.message-row--ai {
  justify-content: flex-start;
}

.message-avatar {
  flex: 0 0 auto;
  margin-top: 3px;
}

.message-bubble {
  max-width: min(760px, 76%);
  padding: 12px 14px;
  border-radius: 16px;
  color: #263043;
  line-height: 1.7;
  word-break: break-word;
}

.message-row--user .message-bubble {
  border-top-right-radius: 6px;
  background: linear-gradient(135deg, #5078ff, #7597ff);
  color: #fff;
}

.message-row--ai .message-bubble {
  border-top-left-radius: 6px;
  background: #f6f8fc;
  border: 1px solid #edf1f7;
}

.loading-indicator,
.empty-chat {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #8b929e;
}

.empty-chat {
  width: 100%;
  justify-content: center;
  min-height: 120px;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.section-heading {
  margin-bottom: 24px;
}

.section-heading h2 {
  margin: 0 0 6px;
  color: #1e293b;
  font-size: 28px;
  font-weight: 760;
}

.section-heading p {
  margin: 0;
  color: #88909d;
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 22px;
}

.section-empty {
  padding: 28px;
  border: 1px dashed #dce4f0;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.72);
  color: #8b929e;
  text-align: center;
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

.preview-drawer {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--line);
  background: #fff;
  box-shadow: -18px 0 46px rgba(24, 38, 78, 0.08);
  min-width: 0;
}

.preview-header {
  min-height: 74px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
}

.preview-header h3 {
  margin: 0;
  font-size: 16px;
}

.preview-header p {
  margin: 4px 0 0;
  color: #8b929e;
  font-size: 12px;
}

.preview-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.preview-link,
.preview-close {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: #68707f;
  font-size: 13px;
}

.preview-link:hover,
.preview-close:hover {
  color: var(--brand-strong);
}

.preview-link.danger {
  color: #ff4d4f;
}

.preview-close {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  font-size: 20px;
  line-height: 1;
}

.preview-body {
  flex: 1;
  min-height: 0;
  background: #f8fafc;
}

.preview-placeholder {
  height: 100%;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 12px;
  color: #8b929e;
}

.home-preview-iframe {
  width: 100%;
  height: 100%;
  background: #fff;
}

@media (max-width: 1180px) {
  #homePage.preview-open {
    --preview-width: min(52vw, 680px);
  }
}

@media (max-width: 980px) {
  #homePage {
    --sidebar-width: 252px;
  }
}

@media (max-width: 760px) {
  #homePage,
  #homePage.sidebar-collapsed,
  #homePage.preview-open {
    --sidebar-width: 292px;
    --preview-width: 0px;
    grid-template-columns: 1fr;
  }

  .home-sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(86vw, 310px);
    transform: translateX(-105%);
    transition: 0.25s ease;
    box-shadow: 24px 0 60px rgba(18, 28, 48, 0.18);
  }

  #homePage.sidebar-open .home-sidebar {
    transform: translateX(0);
  }

  #homePage.sidebar-collapsed .home-sidebar {
    padding: 22px 16px 18px;
  }

  #homePage.sidebar-collapsed .sidebar-expanded-only,
  #homePage.sidebar-collapsed .section-label,
  #homePage.sidebar-collapsed .side-scroll {
    display: initial !important;
  }

  #homePage.sidebar-collapsed .brand-row {
    flex-direction: row;
    justify-content: space-between;
    padding-bottom: 22px;
  }

  #homePage.sidebar-collapsed .new-chat {
    width: 100%;
  }

  .mobile-topbar {
    display: flex;
  }

  .hero,
  .hero--with-panel {
    width: min(100% - 28px, 820px);
    min-height: calc(100vh - 62px);
    padding: 72px 0 56px;
    justify-content: flex-start;
  }

  .hero-title {
    flex-direction: column;
    font-size: 28px;
  }

  .composer {
    min-height: 112px;
  }

  .composer-input {
    height: 68px !important;
    font-size: 16px;
  }

  .code-panel,
  .chat-panel,
  .content-panel {
    width: min(100% - 28px, 820px);
    margin-top: 0;
  }

  .code-toolbar {
    height: auto;
    align-items: flex-start;
    gap: 10px;
    padding: 12px 14px;
  }

  .code-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .app-grid {
    grid-template-columns: 1fr;
  }

  .preview-drawer {
    position: fixed;
    inset: 0;
    z-index: 30;
    width: 100vw;
  }
}
</style>
