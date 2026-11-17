import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由很薄：一个工作区 + 一个显式的组件预览页。
 *
 * 预览页单独成路由（而不是藏在主界面里的开关），
 * 目的是让"这是示例，不是真实功能"这件事在 URL 上就可见、可分享、可审计。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'workspace',
      component: () => import('@/views/TaskWorkspaceView.vue'),
      meta: { title: '音乐 Agent 工作台' },
    },
    {
      path: '/preview/approval',
      name: 'approval-preview',
      component: () => import('@/views/ApprovalPreviewView.vue'),
      meta: { title: '审批卡预览' },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? '音乐 Agent 工作台'
  document.title = `${title} · Cadence`
})

export default router
