import { expect, test, type Page, type Route } from '@playwright/test'

const KEY = 'nexusphere-ledger-development-key-change-me'
const ISSUER = 'http://idp.test/realms/nexusphere'
const CLIENT_ID = 'nexusphere-ledger'
const TOKEN = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbGljZSJ9.signature'

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

function citation(sequence: number, checks = { content: true, link: true, inclusion: true }) {
  return {
    sequence, id: `e-${sequence}`, occurredAt: '2026-10-11T07:15:00Z', agentId: 'invoice-agent', principalId: 'alice',
    action: 'tools/call', target: `demo/target-${sequence}`, decision: 'DENY', outcome: 'DENIED', hash: `h-${sequence}`,
    erased: false, checks,
  }
}

function answer(question: string) {
  return {
    question,
    answer: 'Two calls were denied today. Both were made by invoice-agent.',
    sentences: [
      { text: 'Two calls were denied today.', citations: [8, 9] },
      { text: 'Both were made by invoice-agent.', citations: [9] },
    ],
    droppedSentences: 1,
    citations: [citation(8), citation(9, { content: true, link: false, inclusion: true })],
    unverified: [7],
    plan: { agentId: null, principalId: null, action: null, target: null, decision: 'DENY', outcome: null,
      from: '2026-10-11T00:00:00Z', to: null },
    model: 'llama3.1',
    considered: 3,
    checkpointSize: 9,
    recordedAs: 10,
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

test('the operator asks a question and follows a citation to the cited evidence', async ({ page }) => {
  const asked: { authorization: string; body: unknown }[] = []
  await page.route(/\/(api|public)\//, (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/v1/agents') {
      return json(route, { items: [], nextAfter: null })
    }
    if (request.method() === 'POST' && url.pathname === '/api/v1/ask') {
      asked.push({ authorization: request.headers().authorization ?? '', body: request.postDataJSON() })
      return asked.length === 1
        ? json(route, { code: 'MODEL_UNAVAILABLE', message: 'The model is not available.', details: [] }, 502)
        : json(route, answer(request.postDataJSON().question))
    }
    return json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404)
  })
  await page.goto('/sign-in')
  await page.getByLabel('Operator API key').fill(KEY)
  await page.getByRole('button', { name: 'Open the ledger' }).click()
  await page.getByRole('link', { name: 'Ask' }).click()

  await expect(page).toHaveURL(/\/ask$/)
  await page.getByRole('button', { name: 'Which calls were denied today?' }).click()
  await expect(page.getByLabel('Question')).toHaveValue('Which calls were denied today?')
  await expect(page.getByText('30/500')).toBeVisible()
  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('The model is not available.')

  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  const sentences = page.getByRole('list', { name: 'Answer' }).getByRole('listitem')
  await expect(sentences).toHaveText([/Two calls were denied today\.\s*#8\s*#9/, /Both were made by invoice-agent\.\s*#9/])
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('list', { name: 'Search plan' }).getByRole('listitem'))
    .toHaveText(['Decision DENY', 'From 2026-10-11T00:00:00Z'])
  await expect(page.getByRole('status')).toHaveText('Entry #7 failed verification and was not used.')
  await expect(page.locator('.ask-footer')).toHaveText(
    'Model llama3.1 · 3 entries considered · checkpoint size 9 · Recorded as #10 · 1 sentence without valid citations was removed')

  const row8 = page.locator('#cited-8')
  const row9 = page.locator('#cited-9')
  await expect(row8.getByText('Verified')).toBeVisible()
  await expect(row9.getByText('link failed')).toBeVisible()
  await sentences.nth(1).getByRole('button', { name: '#9' }).click()
  await expect(row9).toHaveClass(/focused/)
  await expect(row8).not.toHaveClass(/focused/)
  await expect(row9).toBeInViewport()

  expect(asked).toHaveLength(2)
  expect(asked.every((a) => a.authorization === `Bearer ${KEY}`)).toBe(true)
  expect(asked[1].body).toEqual({ question: 'Which calls were denied today?' })
})

test('the principal asks about their own evidence from the dashboard', async ({ page }) => {
  await signInAsAlice(page)
  const asked: { authorization: string; body: unknown }[] = []
  await page.route(/\/(api|public)\//, (route) => json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404))
  await page.route('**/public/v1/oidc', (route) => json(route, { issuer: ISSUER, clientId: CLIENT_ID }))
  await page.route('**/api/v1/principal**', (route) => {
    const request = route.request()
    const path = `${request.method()} ${new URL(request.url()).pathname}`
    switch (path) {
      case 'GET /api/v1/principal':
        return json(route, { principalId: 'alice', name: 'Alice', issuer: ISSUER, subject: 'subject-alice',
          authTime: null, expiresAt: null })
      case 'GET /api/v1/principal/activity':
        return json(route, { since: '2026-10-11T00:00:00Z', total: 0, allowed: 0, denied: 0, failed: 0, days: [],
          agents: [] })
      case 'GET /api/v1/principal/approvals':
        return json(route, { items: [] })
      case 'GET /api/v1/principal/grants':
        return json(route, { items: [], nextAfter: null })
      case 'GET /api/v1/principal/evidence/recent':
        return json(route, { items: [], nextBefore: null })
      case 'POST /api/v1/principal/ask':
        asked.push({ authorization: request.headers().authorization ?? '', body: request.postDataJSON() })
        return json(route, { ...answer(request.postDataJSON().question), answer: 'No verified evidence matches the question.',
          sentences: [], citations: [], droppedSentences: 0, unverified: [], plan: null, checkpointSize: null })
      default:
        return json(route, { code: 'NOT_FOUND', message: 'Not mocked' }, 404)
    }
  })
  await page.goto('/my')

  await page.getByRole('link', { name: 'Ask a question' }).click()
  await expect(page).toHaveURL(/\/my\/ask$/)
  await expect(page.getByRole('link', { name: 'Ask', exact: true })).toHaveClass(/active/)
  await page.getByLabel('Question').fill('What did my agents do this week?')
  await expect(page.getByText('32/500')).toBeVisible()
  await page.getByRole('button', { name: 'Ask', exact: true }).click()

  await expect(page.getByText('No verified evidence matches the question.')).toBeVisible()
  await expect(page.locator('.ask-footer')).toHaveText('Model llama3.1 · 3 entries considered · no checkpoint · Recorded as #10')
  await expect(page.getByRole('heading', { name: 'Cited evidence' })).toHaveCount(0)
  expect(asked).toEqual([{ authorization: `Bearer ${TOKEN}`, body: { question: 'What did my agents do this week?' } }])
})
