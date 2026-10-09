import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { KeyRevocation, KeyRotation, PrivateKey } from '@nexusphere/ledger'
import { LEDGER_KEY, buildPackage, entries, keyRecord, witnessNoteKey } from '../../sdk/typescript/test/support'

const PINNED = LEDGER_KEY.publicKey.encoded

async function upload(pkg: unknown, name = 'package.json') {
  const user = userEvent.setup()
  render(<App />)
  await user.upload(screen.getByLabelText('Package file'), new File([JSON.stringify(pkg)], name))
  return user
}

describe('App', async () => {
  const chain = await entries(4)

  it('verifies a pinned package with a witness and lists the disclosed entries', async () => {
    const user = await upload(await buildPackage(chain, [2, 3], { witness: true }))
    expect(screen.getByText('package.json')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.type(screen.getByLabelText('Witness keys, one per line'), (await witnessNoteKey()).vkey)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(screen.getByText('witness.example')).toBeInTheDocument()
    expect(screen.getByText('ledger.example, tree size 4')).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(3)
  })

  it('warns when the package is checked without a pinned key', async () => {
    const user = await upload(await buildPackage(chain, [1]))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByRole('note')).toHaveTextContent('Not pinned')
  })

  it('shows the problems of a changed package', async () => {
    const changed = (await buildPackage(chain, [3])) as any
    changed.links[2].entry.target = 'delete_invoice'
    const user = await upload(changed)
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
    expect(screen.getByText('sequence 3: the disclosed entry does not match its content hash')).toBeInTheDocument()
  })

  it('fails on a missing witness', async () => {
    const user = await upload(await buildPackage(chain, [1]))
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.type(screen.getByLabelText('Witness keys, one per line'), (await witnessNoteKey()).vkey)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
  })

  it('applies a revocation from the key list', async () => {
    const next = await PrivateKey.generate()
    const rotation = await KeyRotation.issue(next, LEDGER_KEY.keyId, LEDGER_KEY, '2025-10-11T00:00:00Z')
    const revocation = await KeyRevocation.issue(
      LEDGER_KEY.keyId,
      '2025-10-10T00:00:00Z',
      '2025-10-11T00:00:00Z',
      'key leaked',
      next,
    )
    const revoked = keyRecord(LEDGER_KEY, 'REVOKED')
    revoked.revocation = { ...revocation, format: 'nexusphere-ledger/key-revocation/v1' }
    const keyList = [revoked, keyRecord(next, 'ACTIVE', rotation)]
    const user = await upload(await buildPackage(chain, [2], { witness: true }))
    await user.upload(screen.getByLabelText('Key list file'), new File([JSON.stringify(keyList)], 'keys.json'))
    await user.type(screen.getByLabelText('Ledger public key'), next.publicKey.encoded)
    await user.type(screen.getByLabelText('Witness keys, one per line'), (await witnessNoteKey()).vkey)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('keys.json')).toBeInTheDocument()
    expect(screen.getByText(new RegExp(`^${LEDGER_KEY.keyId}, the witnesses prove`))).toBeInTheDocument()
  })

  it('reports files that are not packages', async () => {
    const user = userEvent.setup()
    render(<App />)
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled()
    await user.upload(screen.getByLabelText('Package file'), new File(['not json'], 'broken.json'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The file is not JSON.')
  })
})
