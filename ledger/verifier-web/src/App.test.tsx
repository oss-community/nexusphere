import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { LEDGER_KEY, buildPackage, entries, witnessNoteKey } from '../../sdk/typescript/test/support'

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

  it('reports files that are not packages', async () => {
    const user = userEvent.setup()
    render(<App />)
    expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled()
    await user.upload(screen.getByLabelText('Package file'), new File(['not json'], 'broken.json'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The file is not JSON.')
  })
})
