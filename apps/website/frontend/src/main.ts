import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './assets/main.css'
import { webVitals } from './services/webVitals'

const app = createApp(App)

app.use(router)

router.isReady().then(() => {
  webVitals.setRoute(router.currentRoute.value.path)
  webVitals.init()

  router.afterEach((to) => {
    webVitals.setRoute(to.path)
  })
})

app.mount('#app')
