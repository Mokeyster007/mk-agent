/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  /** 后端地址前缀，默认空串（走同源 /api 代理） */
  readonly VITE_API_BASE?: string
  /** 开发身份默认值；仅开发模式使用，不代表真实登录 */
  readonly VITE_DEV_USER_ID?: string
  /** 能力模式展示位：readonly | write_approval（仅展示，不参与请求） */
  readonly VITE_CAPABILITY_MODE?: string
  /** 是否允许在开发模式里修改联调身份 */
  readonly VITE_ALLOW_DEV_IDENTITY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
