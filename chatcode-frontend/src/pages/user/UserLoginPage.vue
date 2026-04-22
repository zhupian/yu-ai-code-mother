<template>
  <div id="userLoginPage" class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">登录</h1>

      <a-form class="auth-form" :model="formState" layout="vertical" autocomplete="off" @finish="handleSubmit">
        <a-form-item
          name="userAccount"
          :rules="[
            { required: true, message: '请输入您的账号' },
            { min: 2, message: '账号长度不能小于 2 位' },
          ]"
        >
          <a-input v-model:value="formState.userAccount" size="large" class="auth-input" placeholder="输入您的账号">
            <template #prefix>
              <UserOutlined class="input-icon" />
            </template>
          </a-input>
        </a-form-item>

        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入您的密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.userPassword"
            size="large"
            class="auth-input"
            placeholder="输入您的密码"
          >
            <template #prefix>
              <LockOutlined class="input-icon" />
            </template>
          </a-input-password>
        </a-form-item>

        <div class="auth-actions">
          <a class="forgot-password" href="javascript:void(0)">忘记密码</a>
        </div>

        <a-form-item class="submit-item">
          <a-button
            type="primary"
            html-type="submit"
            size="large"
            class="auth-button"
            :class="{ 'auth-button-active': canSubmit }"
            block
          >
            登录
          </a-button>
        </a-form-item>
      </a-form>

      <div class="switch-tip">
        没有账号？
        <RouterLink to="/user/register" class="switch-link">注册</RouterLink>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { userLogin } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const canSubmit = computed(() => {
  return (formState.userAccount?.length || 0) >= 2 && (formState.userPassword?.length || 0) >= 8
})

/**
 * 登录成功后刷新全局登录态，再回到沉浸式首页。
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  const res = await userLogin(values)
  if (res.data.code === 0 && res.data.data) {
    await loginUserStore.fetchLoginUser()
    message.success('登录成功')
    await router.push({ path: '/', replace: true })
  } else {
    message.error('登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  background: linear-gradient(180deg, #fbfdff 0%, #ffffff 48%, #f8fbff 100%);
}

.auth-card {
  width: 100%;
  max-width: 640px;
  padding: 56px 72px 48px;
  background: #ffffff;
  border-radius: 28px;
  box-shadow: 0 12px 36px rgb(15 23 42 / 8%);
}

.auth-title {
  margin: 0 0 40px;
  text-align: center;
  color: #1f2937;
  font-size: 28px;
  font-weight: 700;
}

.auth-form {
  width: 100%;
}

.auth-actions {
  margin-top: -8px;
  margin-bottom: 24px;
  text-align: left;
}

.forgot-password {
  color: #4f6bff;
  font-size: 14px;
}

.submit-item {
  margin-bottom: 24px;
}

.auth-button {
  height: 56px;
  border: none;
  border-radius: 999px;
  background: linear-gradient(135deg, #dfe3eb 0%, #d9dde6 100%);
  box-shadow: none;
  color: #ffffff;
  font-size: 24px;
  font-weight: 500;
  transition: all 0.3s;
}

.auth-button-active {
  background: #1677ff !important;
  box-shadow: 0 4px 14px 0 rgba(22, 119, 255, 0.39);
}

.switch-tip {
  text-align: center;
  color: #111827;
  font-size: 16px;
}

.switch-link {
  margin-left: 8px;
  padding-bottom: 2px;
  border-bottom: 2px solid #1677ff;
  color: #1677ff;
  font-weight: 500;
  text-decoration: none;
}

.input-icon {
  color: #3f3f46;
  font-size: 18px;
}

:deep(.ant-form-item) {
  margin-bottom: 20px;
}

:deep(.ant-form-item-explain-error) {
  padding-left: 8px;
}

:deep(.ant-input-affix-wrapper),
:deep(.ant-input-password) {
  height: 68px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 18px;
  background: #f5f7fc;
  box-shadow: none;
}

:deep(.ant-input-affix-wrapper:hover),
:deep(.ant-input-password:hover),
:deep(.ant-input-affix-wrapper:focus),
:deep(.ant-input-password:focus),
:deep(.ant-input-affix-wrapper-focused),
:deep(.ant-input-password-focused) {
  border-color: #cfd7e6;
  background: #f5f7fc;
  box-shadow: none;
}

:deep(.ant-input),
:deep(.ant-input-password input) {
  background: transparent;
  color: #111827;
  font-size: 18px;
}

:deep(.ant-input::placeholder),
:deep(.ant-input-password input::placeholder) {
  color: #c2c9d6;
}

@media (max-width: 768px) {
  .auth-card {
    padding: 40px 24px 32px;
    border-radius: 20px;
  }

  .auth-title {
    margin-bottom: 32px;
    font-size: 24px;
  }

  .auth-button {
    font-size: 20px;
  }
}
</style>
