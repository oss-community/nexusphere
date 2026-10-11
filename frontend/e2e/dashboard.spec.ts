import { readFile } from 'node:fs/promises'
import { expect, test, type Page, type Route } from '@playwright/test'

const ISSUER = 'http://idp.test/realms/nexusphere'
const CLIENT_ID = 'nexusphere-ledger'
const TOKEN = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.signature'
const DAY = 24 * 60 * 60 * 1000

function utcDay(offset: number): string {
  const today = new Date()
  return new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) + offset * DAY)
    .toISOString().slice(0, 10)
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function activity(days: number) {
  return {
    since: `${utcDay(1 - days)}T00:00:00Z`,
    total: 5,
    allowed: 3,
    denied: 1,
    failed: 1,
    days: [
      { day: utcDay(-2), total: 1, allowed: 1, denied: 0, failed: 0 },
      { day: utcDay(0), total: 4, allowed: 2, denied: 1, failed: 1 },
    ],
    agents: [
      { agentId: 'invoice-agent', total: 4, allowed: 2, denied: 1, failed: 1, lastAt: `${utcDay(0)}T00:30:00Z` },
      { agentId: 'travel-agent', total: 1, allowed: 1, denied: 0, failed: 0, lastAt: `${utcDay(-2)}T12:00:00Z` },
    ],
  }
}

function entry(sequence: number, recordedAt: string, agentId: string, decision: string, outcome: string) {
  return {
    format: 'nexusphere-ledger/evidence/v1', id: `e-${sequence}`, sequence, occurredAt: recordedAt, recordedAt,
    agentId, principalId: 'alice', action: 'tools/call', target: `demo/target-${sequence}`, decision, reason: null,
    delegationId: null, inputHash: null, outputHash: null, outcome, correlationId: null, attributes: {},
    erased: false, salts: null, commitments: {}, previousHash: 'p', hash: `h-${sequence}`,
  }
}

function pendingApproval(id: string) {
  return {
    id, agentId: 'invoice-agent', principalId: 'alice', action: 'pay_invoice', target: `${id}-target`,
    inputHash: null, policyHash: 'b'.repeat(64), policies: ['invoices'], approver: 'principal', status: 'PENDING',
    requestedAt: `${utcDay(0)}T00:00:00Z`, expiresAt: new Date(Date.now() + 3600000).toISOString(),
    resolvedAt: null, method: null, reason: null, evidenceId: null, used: false,
  }
}

async function signInAsAlice(page: Page) {
  await page.addInitScript(([key, user]) => sessionStorage.setItem(key, user), [
    `oidc.user:${ISSUER}:${CLIENT_ID}`,
    JSON.stringify({
      access_token: TOKEN,
      token_type: 'Bearer',
      scope: 'openid profile email',
      profile: { sub: 'subject-alice', iss: ISSUER, aud: CLIENT_ID, exp: 0, iat: 0, name: 'Alice' },
      expires_at: Math.floor(Date.now() / 1000) + 3600,
    }),
  ])
}

async function mockLedger(page: Page) {
  const pending = new Map([['ap-1', pendingApproval('ap-1')], ['ap-2', pendingApproval('ap-2')]])
  const authorizations: string[] = []
  const posts: { path: string; body: unknown }[] = []
  await page.route(/\/(api|public)\//, (route) => json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404))
  await page.route('**/public/v1/oidc', (route) => json(route, { issuer: ISSUER, clientId: CLIENT_ID }))
  await page.route('**/api/v1/principal**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    authorizations.push(request.headers().authorization ?? '')
    if (request.method() === 'POST') {
      posts.push({ path: url.pathname, body: request.postDataJSON() })
    }
    const path = `${request.method()} ${url.pathname}`
    if (path === 'GET /api/v1/principal') {
      return json(route, { principalId: 'alice', name: 'Alice', issuer: ISSUER, subject: 'subject-alice',
        authTime: null, expiresAt: null })
    }
    if (path === 'GET /api/v1/principal/activity') {
      return json(route, activity(Number(url.searchParams.get('days') ?? 30)))
    }
    if (path === 'GET /api/v1/principal/approvals') {
      return json(route, { items: [...pending.values()] })
    }
    const answered = /^POST \/api\/v1\/principal\/approvals\/([^/]+)\/(approve|deny)$/.exec(path)
    if (answered) {
      const approval = pending.get(answered[1])!
      pending.delete(answered[1])
      return json(route, { ...approval, status: answered[2] === 'approve' ? 'APPROVED' : 'DENIED', method: 'principal' })
    }
    if (path === 'GET /api/v1/principal/grants') {
      return json(route, { items: [], nextAfter: null })
    }
    if (path === 'GET /api/v1/principal/evidence/recent') {
      return json(route, url.searchParams.get('before') === '8'
        ? { items: [entry(7, `${utcDay(-2)}T12:00:00Z`, 'travel-agent', 'ALLOW', 'SUCCEEDED')], nextBefore: null }
        : {
            items: [
              entry(9, `${utcDay(0)}T00:30:00Z`, 'invoice-agent', 'ALLOW', 'SUCCEEDED'),
              entry(8, `${utcDay(0)}T00:10:00Z`, 'invoice-agent', 'DENY', 'DENIED'),
            ],
            nextBefore: 8,
          })
    }
    if (path === 'POST /api/v1/principal/packages') {
      const scope = request.postDataJSON() ?? {}
      return json(route, {
        format: 'nexusphere-ledger/package/v1',
        scope: { agentId: scope.agentId ?? null, principalId: 'alice', fromSequence: 1, toSequence: 9 },
        checkpoint: { sequence: 9 },
        entries: [],
      })
    }
    return json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404)
  })
  return { authorizations, posts, pending }
}

test('shows the activity of the principal with one bar per day', async ({ page }) => {
  await signInAsAlice(page)
  const ledger = await mockLedger(page)
  await page.goto('/')

  await expect(page).toHaveURL(/\/my$/)
  await expect(page.getByRole('link', { name: 'Dashboard' })).toHaveClass(/active/)
  await expect(page.locator('.who')).toHaveText('Alice')
  const totals = page.locator('.totals')
  await expect(totals.locator('div').filter({ hasText: 'Actions' }).locator('dd')).toHaveText('5')
  await expect(totals.locator('div').filter({ hasText: 'Allowed' }).locator('dd')).toHaveText('3')
  await expect(totals.locator('div').filter({ hasText: 'Denied' }).locator('dd')).toHaveText('1')
  await expect(totals.locator('div').filter({ hasText: 'Failed' }).locator('dd')).toHaveText('1')
  await expect(page.getByRole('list', { name: 'Actions per day, last 30 days (UTC)' }).getByRole('listitem'))
    .toHaveCount(30)

  await page.getByRole('button', { name: '7 days' }).click()
  const bars = page.getByRole('list', { name: 'Actions per day, last 7 days (UTC)' }).getByRole('listitem')
  await expect(bars).toHaveCount(7)
  await expect(bars.last()).toHaveAttribute('title', `${utcDay(0)}: 4 actions, 2 allowed, 1 denied, 1 failed`)
  await expect(bars.nth(5)).toHaveAttribute('title', `${utcDay(-1)}: 0 actions, 0 allowed, 0 denied, 0 failed`)
  const heights = await bars.evaluateAll((items) => items.map((li) => li.querySelector('span')!.getBoundingClientRect().height))
  expect(heights[6]).toBeGreaterThan(heights[4])
  expect(heights[4]).toBeGreaterThan(0)
  expect(heights[5]).toBe(0)
  await expect(page.getByRole('row', { name: /travel-agent/ }).getByRole('cell').nth(1)).toHaveText('1')
  expect(ledger.authorizations.every((value) => value === `Bearer ${TOKEN}`)).toBe(true)
})

test('approving a pending approval sends it to the ledger and removes it from the queue', async ({ page }) => {
  await signInAsAlice(page)
  const ledger = await mockLedger(page)
  page.on('dialog', (dialog) => dialog.accept('looks right'))
  await page.goto('/my')

  await expect(page.getByRole('heading', { name: 'Waiting for you (2)' })).toBeVisible()
  const answered = page.waitForRequest((request) => request.method() === 'POST'
    && request.url().endsWith('/api/v1/principal/approvals/ap-1/approve'))
  await page.getByRole('row', { name: /ap-1-target/ }).getByRole('button', { name: 'Approve' }).click()
  expect((await answered).postDataJSON()).toEqual({ reason: 'looks right' })

  await expect(page.getByRole('heading', { name: 'Waiting for you (1)' })).toBeVisible()
  await expect(page.getByText('ap-1-target')).toHaveCount(0)
  await expect(page.getByText('ap-2-target')).toBeVisible()
  expect(ledger.posts.map((p) => p.path)).toEqual(['/api/v1/principal/approvals/ap-1/approve'])
})

test('exports the evidence package of the principal as a JSON file', async ({ page }) => {
  await signInAsAlice(page)
  const ledger = await mockLedger(page)
  await page.goto('/my')

  const card = page.locator('section.card').filter({ has: page.getByRole('heading', { name: 'Export my evidence' }) })
  await card.getByLabel('Agent').selectOption('invoice-agent')
  await card.getByLabel('To sequence').fill('9')
  const download = page.waitForEvent('download')
  await card.getByRole('button', { name: 'Export' }).click()
  const file = await download

  expect(file.suggestedFilename()).toBe('evidence-package-9.json')
  const pkg = JSON.parse(await readFile(await file.path(), 'utf8'))
  expect(pkg.scope.principalId).toBe('alice')
  expect(pkg.scope.agentId).toBe('invoice-agent')
  expect(ledger.posts).toEqual([{ path: '/api/v1/principal/packages', body: { agentId: 'invoice-agent', toSequence: 9 } }])
  await expect(card.getByText(/verified offline in the Auditor Portal/)).toBeVisible()
})

test('shows the newest actions first, grouped by day, and loads older ones', async ({ page }) => {
  await signInAsAlice(page)
  await mockLedger(page)
  const first = page.waitForRequest((request) => request.url().includes('/api/v1/principal/evidence/recent'))
  await page.goto('/my')

  expect(new URL((await first).url()).search).toBe('?limit=50')
  const today = page.getByRole('region', { name: utcDay(0) })
  await expect(today.getByRole('listitem')).toHaveText([
    /00:30\s*invoice-agent tools\/call demo\/target-9\s*ALLOW SUCCEEDED/,
    /00:10\s*invoice-agent tools\/call demo\/target-8\s*DENY DENIED/,
  ])
  await expect(page.getByRole('region', { name: utcDay(-2) })).toHaveCount(0)

  const older = page.waitForRequest((request) => request.url().includes('/api/v1/principal/evidence/recent?before=8'))
  await page.getByRole('button', { name: 'Load more' }).click()
  expect(new URL((await older).url()).search).toBe('?before=8&limit=50')
  await expect(page.getByRole('region', { name: utcDay(-2) }).getByRole('listitem'))
    .toHaveText([/12:00\s*travel-agent tools\/call demo\/target-7/])
  await expect(today.getByRole('listitem')).toHaveCount(2)
  await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(0)
})
