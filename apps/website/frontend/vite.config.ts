import { execSync } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

const gitSha = (() => {
  try {
    return execSync('git rev-parse --short HEAD').toString().trim()
  } catch {
    return 'dev'
  }
})()
const buildTime = new Date().toISOString()

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiBase = env.VITE_API_BASE || ''
  const derivedApiTarget = apiBase.match(/^https?:\/\//)
    ? new URL(apiBase).origin
    : 'http://localhost:8080'

  return {
    define: {
      __APP_VERSION__: JSON.stringify(gitSha),
      __BUILD_TIME__: JSON.stringify(buildTime)
    },
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: true,
      port: 5173,
      proxy: {
        '/api': {
          target: derivedApiTarget,
          changeOrigin: true
        }
      }
    },
    test: {
      // Allow test suite to pass when no test files exist yet
      passWithNoTests: true
    }
  }
})
