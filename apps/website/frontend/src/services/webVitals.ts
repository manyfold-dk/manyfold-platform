import { onCLS, onINP, onLCP, onFCP, onTTFB, type Metric } from 'web-vitals'
import { ref, readonly } from 'vue'
import type { WebVitalEntry } from '@/types'

interface WebVitalsBatch {
  entries: WebVitalEntry[]
  userAgent: string
  url: string
}

const BATCH_SIZE = 5
const FLUSH_INTERVAL_MS = 10000
const RAW_API_BASE = (import.meta.env.VITE_API_BASE || '/api/v1').trim().replace(/\/$/, '')
const API_BASE =
  import.meta.env.DEV && (/^https?:\/\//.test(RAW_API_BASE) || /^\/\//.test(RAW_API_BASE))
    ? '/api/v1'
    : RAW_API_BASE
const API_ENDPOINT = `${API_BASE}/metrics/vitals`
const LOCAL_STORAGE_KEY = 'manyfold-web-vitals'
const LOCAL_STORAGE_MAX_ENTRIES = 200
const localVitals = ref<WebVitalEntry[]>(loadLocalVitals())

function normalizeMetricEntry(entry: WebVitalEntry): WebVitalEntry {
  return {
    ...entry,
    value: Number(entry.value.toFixed(2)),
    delta: Number(entry.delta.toFixed(2))
  }
}

function loadLocalVitals(): WebVitalEntry[] {
  if (typeof localStorage === 'undefined') {
    return []
  }

  try {
    const raw = localStorage.getItem(LOCAL_STORAGE_KEY)
    if (!raw) {
      return []
    }
    const parsed = JSON.parse(raw) as WebVitalEntry[]
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed.slice(0, LOCAL_STORAGE_MAX_ENTRIES).map(normalizeMetricEntry)
  } catch {
    return []
  }
}

function persistLocalVitals(entries: WebVitalEntry[]): void {
  if (typeof localStorage === 'undefined') {
    return
  }
  try {
    localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(entries))
  } catch {
    // Ignore storage failures in private mode and when quota is full
  }
}

function recordLocalVital(entry: WebVitalEntry): void {
  const normalized = normalizeMetricEntry(entry)
  localVitals.value = [normalized, ...localVitals.value].slice(0, LOCAL_STORAGE_MAX_ENTRIES)
  persistLocalVitals(localVitals.value)
}

export function clearLocalVitals(): void {
  localVitals.value = []
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem(LOCAL_STORAGE_KEY)
  }
}

export const localVitalHistory = readonly(localVitals)

class WebVitalsCollector {
  private batch: WebVitalEntry[] = []
  private currentRoute: string = '/'

  private shouldSendToBackend(): boolean {
    if (import.meta.env.DEV && !import.meta.env.VITE_ENABLE_WEB_VITALS) {
      return false
    }
    return true
  }

  setRoute(route: string): void {
    this.currentRoute = route
  }

  private handleMetric(metric: Metric): void {
    const entry: WebVitalEntry = {
      name: metric.name,
      value: metric.value,
      rating: metric.rating,
      delta: metric.delta,
      id: metric.id,
      navigationType: metric.navigationType,
      route: this.currentRoute,
      timestamp: Date.now()
    }
    recordLocalVital(entry)

    if (!this.shouldSendToBackend()) {
      if (import.meta.env.DEV) {
        console.debug('[WebVitals]', metric.name, metric.value.toFixed(2), metric.rating)
      }
      return
    }

    this.batch.push(entry)

    if (this.batch.length >= BATCH_SIZE) {
      this.flush()
    }
  }

  private flush(): void {
    if (this.batch.length === 0) return

    const payload: WebVitalsBatch = {
      entries: [...this.batch],
      userAgent: navigator.userAgent,
      url: window.location.href
    }

    this.batch = []
    this.send(payload)
  }

  private send(payload: WebVitalsBatch): void {
    const body = JSON.stringify(payload)

    if (navigator.sendBeacon) {
      const blob = new Blob([body], { type: 'application/json' })
      const success = navigator.sendBeacon(API_ENDPOINT, blob)
      if (!success) {
        this.sendViaFetch(body)
      }
    } else {
      this.sendViaFetch(body)
    }
  }

  private sendViaFetch(body: string): void {
    fetch(API_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true
    }).catch((err) => {
      console.warn('[WebVitals] Failed to send metrics:', err)
    })
  }

  private setupFlushTimer(): void {
    window.setInterval(() => {
      this.flush()
    }, FLUSH_INTERVAL_MS)

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') {
        this.flush()
      }
    })
  }

  init(): void {
    if (this.shouldSendToBackend()) {
      this.setupFlushTimer()
    }

    onCLS(this.handleMetric.bind(this))
    onINP(this.handleMetric.bind(this))
    onLCP(this.handleMetric.bind(this))
    onFCP(this.handleMetric.bind(this))
    onTTFB(this.handleMetric.bind(this))
  }
}

export const webVitals = new WebVitalsCollector()
