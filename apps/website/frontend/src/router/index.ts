import { createRouter, createWebHistory } from 'vue-router'
import LandingView from '@/views/LandingView.vue'

const protectedMeta = { requiresServerAuth: true }

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 80 }
    return { top: 0 }
  },
  routes: [
    {
      path: '/',
      name: 'landing',
      component: LandingView
    },
    {
      path: '/demo',
      name: 'demo',
      component: () => import('@/views/DemoView.vue'),
      meta: protectedMeta
    },
    {
      path: '/stack',
      name: 'stack',
      component: () => import('@/views/StackView.vue'),
      meta: { title: 'The platform' }
    },
    {
      path: '/small-companies',
      name: 'small-companies',
      component: () => import('@/views/SmallCompaniesView.vue'),
      meta: { title: 'For small companies' }
    },
    {
      path: '/decisions',
      name: 'decisions',
      component: () => import('@/views/DecisionsView.vue'),
      meta: { title: 'Decisions' }
    },
    {
      // No static title: the view sets it from the record (useDocumentTitle).
      path: '/decisions/:slug',
      name: 'decision',
      component: () => import('@/views/DecisionView.vue')
    },
    {
      // The About content now lives on the landing page.
      path: '/about',
      redirect: { path: '/', hash: '#about' }
    },
    {
      path: '/status',
      name: 'status',
      component: () => import('@/views/StatusView.vue'),
      meta: { title: 'Status' }
    },
    {
      path: '/platform',
      name: 'platform',
      component: () => import('@/views/HealthView.vue'),
      meta: protectedMeta
    },
    {
      path: '/platform/deep',
      name: 'platform-deep',
      component: () => import('@/views/DeepHealthView.vue'),
      meta: protectedMeta
    },
    {
      path: '/platform/fleet',
      name: 'platform-fleet',
      component: () => import('@/views/FleetMapView.vue'),
      meta: protectedMeta
    },
    {
      path: '/platform/quest',
      name: 'platform-quest',
      component: () => import('@/views/StabilityQuestView.vue'),
      meta: protectedMeta
    },
    {
      path: '/privacy',
      name: 'privacy',
      component: () => import('@/views/PrivacyView.vue'),
      meta: { title: 'Privacy' }
    }
  ]
})

router.beforeEach((to) => {
  if (!to.meta?.requiresServerAuth) return true
  if (typeof window === 'undefined') return true

  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`

  // Force a full document request so oauth2-proxy, not the SPA, decides
  // whether the protected route is accessible.
  if (currentPath !== to.fullPath) {
    window.location.assign(to.fullPath)
    return false
  }

  return true
})

const SITE_TITLE = 'Manyfold -- Kubernetes platforms, built and run in Europe'

router.afterEach((to) => {
  if (typeof document === 'undefined') return
  const title = to.meta?.title
  document.title = typeof title === 'string' ? `${title} · Manyfold` : SITE_TITLE
})

export default router
