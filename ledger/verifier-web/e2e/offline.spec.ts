import { expect, test } from '@playwright/test'
import { sha256Hex } from '@nexusphere/ledger'
import { LEDGER_KEY, buildPackage, entries, vector } from '../../sdk/typescript/test/support'

const page = new URL('../dist/index.html', import.meta.url).href

test('verifies a package from the built file without any network request', async ({ page: browser }) => {
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.goto(page)
  const pkg = await buildPackage(await entries(5), [2, 4])
  await browser.getByLabel('Package file').setInputFiles({
    name: 'package.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(pkg)),
  })
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  await browser.getByRole('button', { name: 'Verify' }).click()
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await expect(browser.getByRole('row')).toHaveCount(3)
  const blocked = await browser.evaluate(() =>
    fetch('https://example.com/').then(
      () => 'sent',
      () => 'blocked',
    ),
  )
  expect(blocked).toBe('blocked')
  expect(requests).toEqual([page])
})

test('shows the prompt texts and the models of the disclosed entries', async ({ page: browser }) => {
  await browser.goto(page)
  const prompt = 'Pay approved invoices only.'
  const promptHash = await sha256Hex(prompt)
  const modelled = await entries(3, undefined, undefined, { 2: { name: 'model-a', version: '1', promptHash } })
  const pkg = await buildPackage(modelled, [2])
  await browser.getByLabel('Package file').setInputFiles({
    name: 'package.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ ...pkg, prompts: [{ hash: promptHash, text: prompt }] })),
  })
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  await browser.getByRole('button', { name: 'Verify' }).click()
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await expect(browser.getByText('1 prompt texts match the hashes their entries name')).toBeVisible()
  await expect(browser.getByRole('cell', { name: 'model-a 1' })).toBeVisible()
})

test('verifies an index proof from the built file without any network request', async ({ page: browser }) => {
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.goto(page)
  await browser.getByLabel('Package file').setInputFiles({
    name: 'proof.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(vector('index-proof.json').proofs[2])),
  })
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  await browser.getByRole('button', { name: 'Verify' }).click()
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await expect(browser.getByText('grant grant-never-used')).toBeVisible()
  await expect(browser.getByText('no entry up to sequence 3')).toBeVisible()
  expect(requests).toEqual([page])
})

test('verifies a track record against its index proof from the built file', async ({ page: browser }) => {
  const record = vector('agent-track-record.json')
  const requests: string[] = []
  browser.on('request', (request) => requests.push(request.url()))
  await browser.clock.setFixedTime(new Date('2026-10-10T00:00:00Z'))
  await browser.goto(page)
  await browser.getByLabel('Or paste a track record').fill(record.presentedWithOutcomesOnly)
  await browser.getByLabel('Ledger public key').fill(LEDGER_KEY.publicKey.encoded)
  await browser.getByLabel('Index proof file').setInputFiles({
    name: 'proof.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(record.indexProof)),
  })
  await browser.getByRole('button', { name: 'Verify' }).click()
  await expect(browser.getByTestId('verdict')).toHaveText('VALID')
  await expect(browser.getByText('DENIED 1, SUCCEEDED 1')).toBeVisible()
  await expect(browser.getByText('withheld')).toHaveCount(2)
  await expect(browser.getByText('2 entries at checkpoint 3, matches the track record')).toBeVisible()
  expect(requests).toEqual([page])
})
