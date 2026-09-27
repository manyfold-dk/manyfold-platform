import { Page, expect } from '@playwright/test'
import { JourneyResult, pushJourney } from '../shared/metrics.ts'

/** A setting the CronJob must provide; the journey cannot run without it. */
export function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is not set`)
  return value
}

/** Labels shared by every journey: which front, and which tenant owns it. */
export function journeyLabels(journey: string): { front: string; journey: string; tenant: string } {
  return { front: required('FRONT'), journey, tenant: process.env.TENANT || 'platform' }
}

/**
 * Logs in through an OIDC proxy whose identity provider shows a Keycloak form: open the page,
 * follow the redirect to the provider's host, submit the credentials, and wait to be sent back.
 */
export async function keycloakLogin(
  page: Page,
  startUrl: string,
  identityHost: string,
  user: string,
  pass: string,
): Promise<void> {
  await page.goto(startUrl, { waitUntil: 'domcontentloaded' })
  await page.waitForURL((u) => u.host === identityHost, { timeout: 15_000 })
  await page.fill('#username', user)
  await page.fill('#password', pass)
  await page.click('#kc-login')
  await page.waitForURL((u) => u.host !== identityHost, { timeout: 20_000 })
}

/**
 * Runs a journey, times it and always pushes a result: up=1 when the body passed, up=0 when it
 * threw. The journey's own error is what the run reports; a failed push is logged, and fails
 * the run only when the journey itself passed.
 */
export async function runJourney(
  meta: { front: string; journey: string; tenant: string; loginExpected: boolean },
  body: () => Promise<void>,
  push: (r: JourneyResult) => Promise<void> = (r) => pushJourney(r, required('PUSHGATEWAY_URL')),
): Promise<void> {
  const start = Date.now()
  let failure: unknown
  try {
    await body()
  } catch (err) {
    failure = err
  }
  const passed = failure === undefined
  const result: JourneyResult = {
    front: meta.front,
    journey: meta.journey,
    tenant: meta.tenant,
    up: passed ? 1 : 0,
    durationSeconds: (Date.now() - start) / 1000,
    loginSuccess: passed && meta.loginExpected ? 1 : 0,
    runTimestamp: Math.floor(Date.now() / 1000),
  }
  try {
    await push(result)
  } catch (pushError) {
    console.error('synthetic: metrics push failed', pushError)
    if (passed) throw pushError
  }
  if (!passed) throw failure
}

export { expect }
