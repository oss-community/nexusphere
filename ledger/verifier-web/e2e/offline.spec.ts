import { expect, test } from '@playwright/test'
import { LEDGER_KEY, buildPackage, entries } from '../../sdk/typescript/test/support'

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
