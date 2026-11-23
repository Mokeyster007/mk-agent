import { createRouter, createWebHistory } from 'vue-router'

/**
 * 电商 Agent 平台路由（音乐入口已退场，电商为默认体验）。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      alias: '/shop',
      name: 'shop',
      component: () => import('@/views/ShopView.vue'),
      meta: { title: '导购与商城' },
    },
    {
      path: '/shop/admin',
      name: 'shop-admin',
      component: () => import('@/views/ShopAdminView.vue'),
      meta: { title: '商城后台' },
    },
    {
      path: '/shop/aftersale',
      name: 'shop-aftersale',
      component: () => import('@/views/AfterSaleView.vue'),
      meta: { title: '售后与退货' },
    },
    {
      path: '/shop/admin/aftersale',
      name: 'shop-admin-aftersale',
      component: () => import('@/views/AfterSaleAdminView.vue'),
      meta: { title: '售后处理' },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior(to) {
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    return undefined
  },
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? '导购与商城'
  document.title = `${title} · MkAgent 电商`
})

export default router
