import { test } from '@playwright/test'
import { expect, journeyLabels, required, runJourney } from '../journey.ts'

// Public page smoke check. It asserts landmarks, not wording: a copy change is not an outage.
// SMOKE_EXPECT_LINK optionally names one link that must be present (a wordmark, say).
// JOURNEY_URL is read inside the journey, so a missing one pushes up=0.
test('public page smoke', async ({ page }) => {
  const expectLink = process.env.SMOKE_EXPECT_LINK
  await runJourney({ ...journeyLabels('smoke'), loginExpected: false }, async () => {
    const res = await page.goto(required('JOURNEY_URL'), { waitUntil: 'domcontentloaded' })
    expect(res?.status(), 'page answers 200').toBe(200)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByRole('navigation').first()).toBeVisible()
    if (expectLink) {
      await expect(page.getByRole('link', { name: expectLink, exact: true })).toBeVisible()
    }
  })
})
