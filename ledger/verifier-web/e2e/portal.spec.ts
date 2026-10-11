import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { LEDGER_KEY, buildPackage, buildReport, entries, signReport, witnessNoteKey, zip } from '../../sdk/typescript/test/support'

const page = new URL('../dist/index.html', import.meta.url).href

async function verify(browser: Page, pkg: unknown, witness = false) {
  await browser.getByLabel('Package file').setInputFiles({
    name: 'package.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(pkg)),
  })
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  if (witness) {
    await browser.getByLabel('Witness keys, one per line').fill((await witnessNoteKey()).vkey)
  }
  await browser.getByRole('button', { name: 'Verify' }).click()
}

function check(browser: Page, name: string) {
  return browser.getByTestId(`check-${name}`).getByRole('cell').nth(1)
}

test('investigates a package entry by entry without any network request', async ({ page: browser }) => {
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.goto(page)
  await expect(browser).toHaveTitle('Auditor Portal')
  await expect(browser.getByRole('heading', { level: 1 })).toHaveText('Auditor Portal')
  const pkg = await buildPackage(await entries(6), [1, 2, 3, 4, 5], { witness: true })
  await verify(browser, pkg, true)
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await expect(browser.getByRole('tabpanel')).toContainText('witness.example')
  await browser.getByRole('tab', { name: 'Entries' }).click()
  await expect(browser.getByTestId('entry-count')).toHaveText('5 of 5 entries')
  await browser.getByLabel('Agent').selectOption('sales-agent')
  await expect(browser.getByTestId('entry-count')).toHaveText('2 of 5 entries')
  await expect(browser.getByRole('row')).toHaveCount(3)
  await browser.getByRole('row').filter({ hasText: 'sales-agent' }).first().click()
  const detail = browser.getByRole('region', { name: 'Entry 2' })
  await expect(detail.getByTestId('entry-verdict')).toHaveText('All present checks passed')
  for (const name of ['Content hash', 'Entry hash', 'Hash link', 'Inclusion proof', 'SCITT statement', 'SCITT receipt']) {
    await expect(check(browser, name)).toHaveText('passed')
  }
  await expect(detail.getByText('attributes.tool', { exact: true })).toBeVisible()
  await browser.getByRole('tab', { name: 'Chain' }).click()
  await expect(browser.getByText('6 links, 5 disclosed')).toBeVisible()
  await browser.getByRole('tab', { name: 'Log' }).click()
  const lines = browser.getByRole('table', { name: 'Signature lines' }).getByRole('row')
  await expect(lines).toHaveCount(3)
  await expect(lines.nth(2)).toContainText('verified with the given witness key')
  expect(requests).toEqual([page])
})

test('shows the failed checks of a tampered entry', async ({ page: browser }) => {
  await browser.goto(page)
  const pkg = (await buildPackage(await entries(4), [2, 3])) as any
  pkg.links[2].entry.target = 'delete_invoice'
  await verify(browser, pkg)
  await expect(browser.getByTestId('verdict')).toHaveText('INVALID')
  await browser.getByRole('tab', { name: 'Entries' }).click()
  await browser.getByRole('button', { name: 'Open entry 3' }).click()
  await expect(browser.getByTestId('entry-verdict')).toHaveText('A check failed')
  await expect(check(browser, 'Content hash')).toHaveText('failed')
  await expect(check(browser, 'Entry hash')).toHaveText('failed')
  await expect(check(browser, 'Hash link')).toHaveText('passed')
  await expect(check(browser, 'Inclusion proof')).toHaveText('failed')
  await browser.getByRole('button', { name: 'Open entry 2' }).click()
  await expect(browser.getByTestId('entry-verdict')).toHaveText('All present checks passed')
})

test('verifies a report and opens the entries it names', async ({ page: browser }) => {
  await browser.goto(page)
  const pkg = await buildPackage(await entries(4), [2, 3])
  const report = await buildReport(pkg)
  const sections = report.sections as Record<string, unknown>[]
  const signed = await signReport({ ...report, sections: [{ ...sections[0], entries: [2, 3] }] })
  await verify(browser, pkg)
  await browser.getByRole('tab', { name: 'Report' }).click()
  await browser.getByLabel('Report file').setInputFiles({
    name: 'report.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(signed)),
  })
  await expect(browser.getByTestId('report-verdict')).toHaveText('VALID')
  const timeline = browser.getByRole('article', { name: 'Section Incident timeline' })
  await expect(timeline.getByText('SHOWN', { exact: true })).toBeVisible()
  await expect(timeline.getByText('#2 sales-agent tools/call on read_invoice')).toBeVisible()
  await timeline.getByRole('button', { name: 'Open entry 3' }).click()
  await expect(browser.getByRole('tab', { name: 'Entries' })).toHaveAttribute('aria-selected', 'true')
  await expect(browser.getByRole('region', { name: 'Entry 3' })).toBeVisible()
})

test('reads a dispute package and verifies its report', async ({ page: browser }) => {
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.goto(page)
  const pkg = await buildPackage(await entries(4), [2, 3], { witness: true })
  const archive = zip({
    'README.txt': 'Nexusphere Ledger dispute package\n',
    'package.json': JSON.stringify(pkg),
    'report.json': JSON.stringify(await buildReport(pkg)),
    'witnesses.txt': (await witnessNoteKey()).vkey + '\n',
  }, { deflate: true, descriptor: true })
  await browser.getByLabel('Package file').setInputFiles({
    name: 'dispute-4.zip',
    mimeType: 'application/zip',
    buffer: Buffer.from(archive),
  })
  await expect(browser.getByTestId('archive')).toContainText(
    'took package.json, report.json, witnesses.txt; did not use README.txt.',
  )
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  await browser.getByRole('button', { name: 'Verify' }).click()
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await browser.getByRole('tab', { name: 'Report' }).click()
  await expect(browser.getByTestId('report-verdict')).toHaveText('VALID')
  expect(requests).toEqual([page])
})

test('downloads the audit worksheet as CSV without any network request', async ({ page: browser }) => {
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.goto(page)
  await verify(browser, await buildPackage(await entries(4), [1, 2, 3]))
  await browser.getByRole('tab', { name: 'Export' }).click()
  const [download] = await Promise.all([
    browser.waitForEvent('download'),
    browser.getByRole('button', { name: 'Download audit worksheet (CSV)' }).click(),
  ])
  expect(download.suggestedFilename()).toBe('audit-worksheet-4.csv')
  const lines = readFileSync((await download.path())!, 'utf8').trimEnd().split('\r\n')
  expect(lines[0]).toBe(
    'sequence,occurredAt,agentId,principalId,action,target,decision,outcome,model,hash,contentHashCheck,' +
      'entryHashCheck,hashLinkCheck,inclusionProofCheck,statementCheck,receiptCheck',
  )
  expect(lines).toHaveLength(4)
  expect(lines[1]).toMatch(/^1,.*,passed,passed,passed,passed,passed,passed$/)
  await browser.emulateMedia({ media: 'print' })
  await browser.evaluate(() => window.dispatchEvent(new Event('beforeprint')))
  await expect(browser.locator('.print-view')).toBeVisible()
  await expect(browser.locator('.print-view')).toContainText('3 of 3 entries')
  await expect(browser.getByRole('tablist')).toBeHidden()
  expect(requests).toEqual([page])
})
