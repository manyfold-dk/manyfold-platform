import { watchEffect } from 'vue'

// For routes whose title depends on loaded content. The router's afterEach sets
// the static titles first; this runs after it, because watchers flush later.
export function useDocumentTitle(title: () => string) {
  watchEffect(() => {
    if (typeof document === 'undefined') return
    document.title = `${title()} · Manyfold`
  })
}
