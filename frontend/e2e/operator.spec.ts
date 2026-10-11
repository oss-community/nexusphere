import { expect, test, type Route } from '@playwright/test'

const KEY = 'nexusphere-ledger-development-key-change-me'

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

test('the operator signs in with the API key and sees the overview', async ({ page }) => {
  const authorizations: string[] = []
  await page.route(/\/(api|public)\//, (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/')) {
      authorizations.push(route.request().headers().authorization ?? '')
    }
    switch (url.pathname) {
      case '/api/v1/agents':
        return json(route, { items: [], nextAfter: null })
      case '/api/v1/ledger/head':
        return json(route, { sequence: 42, hash: 'f'.repeat(64) })
      case '/api/v1/keys':
        return json(route, [{ keyId: 'key-1', algorithm: 'Ed25519', publicKey: 'PUB', status: 'ACTIVE',
          activatedAt: '2026-10-01T00:00:00Z', retiredAt: null, rotation: null, revocation: null }])
      case '/api/v1/checkpoints/latest':
        return json(route, { format: 'nexusphere-ledger/checkpoint/v1', sequence: 40, headHash: 'e'.repeat(64),
          createdAt: '2026-10-10T00:00:00Z', keyId: 'key-1', profiles: null, signature: 'sig', timestamp: null,
          timestampedAt: null, rekorLogIndex: null })
      default:
        return json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404)
    }
  })
  await page.goto('/')

  await expect(page).toHaveURL(/\/sign-in$/)
  await page.getByLabel('Operator API key').fill(KEY)
  await page.getByRole('button', { name: 'Open the ledger' }).click()

  await expect(page.getByRole('link', { name: 'Overview' })).toHaveClass(/active/)
  await expect(page.getByRole('heading', { name: 'Ledger' })).toBeVisible()
  await expect(page.locator('dt', { hasText: 'Head sequence' }).locator('+ dd')).toHaveText('42')
  await expect(page.getByRole('cell', { name: 'key-1' })).toBeVisible()
  expect(authorizations.length).toBeGreaterThan(0)
  expect(authorizations.every((value) => value === `Bearer ${KEY}`)).toBe(true)
})
