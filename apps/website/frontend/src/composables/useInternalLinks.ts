import { useRouter } from 'vue-router'

// Rendered Markdown contains plain anchors. This click handler hands the ones
// that point inside `prefix` to the router so they do not reload the page.
export function useInternalLinks(prefix: string) {
  const router = useRouter()

  return (event: MouseEvent) => {
    if (event.defaultPrevented || event.button !== 0) return
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    const href = (event.target as HTMLElement).closest('a')?.getAttribute('href')
    if (!href?.startsWith(prefix)) return
    event.preventDefault()
    void router.push(href)
  }
}
