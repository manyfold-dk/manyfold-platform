import { test } from '@playwright/test'
import { expect, journeyLabels, keycloakLogin, required, runJourney } from '../journey.ts'

// Signs in through the identity provider and checks the landing view. Read-only: no action
// that changes state.
// Settings are read inside the journey: a missing one is a failed check that pushes up=0, not a
// crash that leaves the last good result standing.
test('login and landing view', async ({ page }) => {
  await runJourney({ ...journeyLabels('login'), loginExpected: true }, async () => {
    await keycloakLogin(
      page,
      required('JOURNEY_URL'),
      required('IDENTITY_HOST'),
      required('LOGIN_USER'),
      required('LOGIN_PASS'),
    )
    await expect(page.getByRole('navigation')).toBeVisible()
    await expect(page.locator('body')).not.toContainText(/error|forbidden/i)
  })
})
