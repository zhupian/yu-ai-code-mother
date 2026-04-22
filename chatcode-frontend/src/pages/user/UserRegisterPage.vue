<template>
  <div id="userRegisterPage" class="auth-page">
    <div class="auth-card">
      <h1 class="auth-title">注册</h1>

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

        <a-form-item
          name="checkPassword"
          :rules="[
            { required: true, message: '请确认您的密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
            { validator: validatePasswordMatch, trigger: 'blur' },
          ]"
        >
          <a-input-password
            v-model:value="formState.checkPassword"
            size="large"
            class="auth-input"
            placeholder="确认您的密码"
          >
            <template #prefix>
              <LockOutlined class="input-icon" />
            </template>
          </a-input-password>
        </a-form-item>

        <a-form-item class="submit-item submit-item--register">
          <a-button
            type="primary"
            html-type="submit"
            size="large"
            class="auth-button"
            :class="{ 'auth-button-active': canSubmit }"
            block
          >
            注册
          </a-button>
        </a-form-item>
      </a-form>

      <div class="switch-tip">
        已有账号？
        <RouterLink to="/user/login" class="switch-link">返回登录</RouterLink>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { userRegister } from '@/api/userController.ts'

const router = useRouter()

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const canSubmit = computed(() => {
  return (
    (formState.userAccount?.length || 0) >= 2 &&
    (formState.userPassword?.length || 0) >= 8 &&
    (formState.checkPassword?.length || 0) >= 8 &&
    formState.userPassword === formState.checkPassword
  )
})

/**
 * 确认密码必须与密码一致；返回 Promise 是 Ant Design Vue 自定义校验推荐形态。
 */
const validatePasswordMatch = async (_rule: unknown, value: string) => {
  if (value && value !== formState.userPassword) {
    return Promise.reject('两次输入的密码不一致')
  }
  return Promise.resolve()
}

const handleSubmit = async (values: API.UserRegisterRequest) => {
  const res = await userRegister(values)
  if (res.data.code === 0) {
    message.success('注册成功，请登录')
    await router.push({ path: '/user/login', replace: true })
  } else {
    message.error('注册失败，' + res.data.message)
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

.submit-item {
  margin-bottom: 24px;
}

.submit-item--register {
  margin-top: 36px;
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
  box-shadow: 0 4px 14px 0 rgba(22, 119, 255, 0.39) !important;
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
