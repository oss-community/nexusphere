import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { KeyRevocation, KeyRotation, PrivateKey, sha256Hex } from '@nexusphere/ledger'
import {
  LEDGER_KEY,
  buildPackage,
  entries,
  erase,
  keyRecord,
  rekorPackage,
  timestampedPackage,
  vector,
  witnessNoteKey,
} from '../../sdk/typescript/test/support'

const PINNED = LEDGER_KEY.publicKey.encoded

async function upload(pkg: unknown, name = 'package.json') {
  const user = userEvent.setup()
  render(<App />)
  await user.upload(
    screen.getByLabelText('Package file'),
    new File([typeof pkg === 'string' ? pkg : JSON.stringify(pkg)], name),
  )
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

  it('marks erased entries and keeps the package valid', async () => {
    const pkg = (await buildPackage(chain, [2, 3])) as any
    pkg.links[2].entry = await erase(chain[2])
    const user = await upload(pkg)
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getAllByText('erased')).toHaveLength(2)
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

  it('checks the checkpoint timestamp against the pinned authority chain', async () => {
    const certificates = vector('timestamp.json').certificates as string[]
    const user = await upload(await timestampedPackage())
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.upload(
      screen.getByLabelText('Timestamp authority chain file'),
      new File([certificates.slice(1).join('')], 'tsa-chain.pem'),
    )
    await user.click(screen.getByLabelText('Require a timestamp'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('tsa-chain.pem')).toBeInTheDocument()
    expect(
      screen.getByText('2026-10-10T21:38:35Z by CN=Test TSA Timestamping,O=local (pinned)'),
    ).toBeInTheDocument()
  })

  it('refuses a package without the required timestamp', async () => {
    const user = await upload(await buildPackage(chain, [1]))
    await user.click(screen.getByLabelText('Require a timestamp'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
    expect(screen.getByText('checkpoint 4 has no timestamp')).toBeInTheDocument()
  })

  it('checks the Rekor entry of the checkpoint against the pinned log key', async () => {
    const rekor = vector('rekor.json')
    const user = await upload(await rekorPackage())
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.type(screen.getByLabelText('Rekor log keys, one per line'), rekor.rekorVerifierKey)
    await user.click(screen.getByLabelText('Require a Rekor entry'))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText(`entry ${rekor.entry.logIndex} in rekor.local (pinned)`)).toBeInTheDocument()
  })

  it('shows the prompt texts and the models of the disclosed entries', async () => {
    const prompt = 'Pay approved invoices only.'
    const promptHash = await sha256Hex(prompt)
    const modelled = await entries(4, undefined, undefined, { 2: { name: 'model-a', version: '2026-09-01', promptHash } })
    const pkg = (await buildPackage(modelled, [2, 3])) as any
    pkg.prompts = [{ hash: promptHash, text: prompt }]
    const user = await upload(pkg)
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('Prompts')).toBeInTheDocument()
    expect(screen.getByText('1 prompt texts match the hashes their entries name')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Model' })).toBeInTheDocument()
    expect(screen.getByText('model-a 2026-09-01')).toBeInTheDocument()
  })

  it('shows the policy set texts the disclosed entries name', async () => {
    const policies = 'permit(principal, action, resource);'
    const policyHash = await sha256Hex(policies)
    const governed = await entries(4, undefined, undefined, {}, { 2: { 'policy.hash': policyHash } })
    const pkg = (await buildPackage(governed, [2, 3])) as any
    pkg.policies = [{ hash: policyHash, text: policies }]
    const user = await upload(pkg)
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('Policies')).toBeInTheDocument()
    expect(screen.getByText('1 policy set texts match the hashes their entries name')).toBeInTheDocument()
    expect(screen.queryByText('Prompts')).not.toBeInTheDocument()
  })

  it('shows a policy set that does not match its text as a problem', async () => {
    const policyHash = await sha256Hex('permit(principal, action, resource);')
    const governed = await entries(4, undefined, undefined, {}, { 2: { 'policy.hash': policyHash } })
    const pkg = (await buildPackage(governed, [2, 3])) as any
    pkg.policies = [{ hash: policyHash, text: 'forbid(principal, action, resource);' }]
    const user = await upload(pkg)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
    expect(screen.getByText(`policy set ${policyHash} does not match its text`)).toBeInTheDocument()
    expect(screen.queryByText('Policies')).not.toBeInTheDocument()
  })

  it('shows no prompts line and no model column without them', async () => {
    const user = await upload(await buildPackage(chain, [2]))
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.queryByText('Prompts')).not.toBeInTheDocument()
    expect(screen.queryByText('Policies')).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Model' })).not.toBeInTheDocument()
  })

  it('verifies an index proof pinned to the ledger key', async () => {
    const proof = vector('index-proof.json').proofs[0]
    const user = await upload(proof, 'proof.json')
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(screen.getByText('agent invoice-agent')).toBeInTheDocument()
    expect(screen.getByText(`3, head ${proof.index.headHash}`)).toBeInTheDocument()
    expect(screen.getByText(LEDGER_KEY.keyId)).toBeInTheDocument()
    expect(screen.getByText('2 entries, sequences 1 to 2')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows that a term never occurred and warns without a pinned key', async () => {
    const user = await upload(vector('index-proof.json').proofs[2], 'proof.json')
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
    expect(screen.getByText('grant grant-never-used')).toBeInTheDocument()
    expect(screen.getByText('no entry up to sequence 3')).toBeInTheDocument()
    expect(screen.getByText(`${LEDGER_KEY.keyId}, not pinned`)).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('Not pinned')
  })

  it('shows the problems of a changed index proof and of another key', async () => {
    const proof = structuredClone(vector('index-proof.json').proofs[0])
    proof.leaf.count = 1
    const user = await upload(proof, 'proof.json')
    await user.type(screen.getByLabelText('Ledger public key'), PINNED)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
    expect(screen.getByText('the proof does not lead to the signed root')).toBeInTheDocument()
    await user.clear(screen.getByLabelText('Ledger public key'))
    await user.type(screen.getByLabelText('Ledger public key'), (await PrivateKey.generate()).publicKey.encoded)
    await user.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByText(/which the proof does not trust/)).toBeInTheDocument()
  })

  describe('track record', () => {
    const record = vector('agent-track-record.json')

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-10-10T00:00:00Z'))
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('verifies a track record and cross-checks it with the index proof', async () => {
      const user = await upload(record.token, 'record.txt')
      await user.type(screen.getByLabelText('Ledger public key'), PINNED)
      await user.upload(
        screen.getByLabelText('Index proof file'),
        new File([JSON.stringify(record.indexProof)], 'proof.json'),
      )
      await user.click(screen.getByRole('button', { name: 'Verify' }))
      expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
      expect(screen.queryByRole('note')).not.toBeInTheDocument()
      expect(screen.getByText(record.claims.jti)).toBeInTheDocument()
      expect(screen.getByText('https://ledger.example')).toBeInTheDocument()
      expect(screen.getByText('invoice-agent')).toBeInTheDocument()
      expect(screen.getByText('any')).toBeInTheDocument()
      expect(screen.getByText('2026-10-01T08:10:00Z to 2027-10-01T08:00:00Z')).toBeInTheDocument()
      expect(screen.getByText(record.claims.record.checkpoint.indexRoot)).toBeInTheDocument()
      expect(
        screen.getByText('2, sequences 1 to 2, 2026-10-01T08:00:00.128456Z to 2026-10-01T08:00:01.128456Z'),
      ).toBeInTheDocument()
      expect(screen.getByText('DENIED 1, SUCCEEDED 1')).toBeInTheDocument()
      expect(screen.getByText('tools/call 2')).toBeInTheDocument()
      expect(screen.getByText('none')).toBeInTheDocument()
      expect(screen.getByText(LEDGER_KEY.keyId)).toBeInTheDocument()
      expect(screen.getByText('2 entries at checkpoint 3, matches the track record')).toBeInTheDocument()
    })

    it('shows withheld claims of a pasted presentation and warns without a pinned key', async () => {
      const user = userEvent.setup()
      render(<App />)
      await user.click(screen.getByLabelText('Or paste a track record'))
      await user.paste(record.presentedWithOutcomesOnly)
      await user.upload(screen.getByLabelText('Key list file'), new File([JSON.stringify([keyRecord(LEDGER_KEY)])], 'keys.json'))
      await user.click(screen.getByRole('button', { name: 'Verify' }))
      expect(await screen.findByTestId('verdict')).toHaveTextContent('VALID')
      expect(screen.getByRole('note')).toHaveTextContent('Not pinned')
      expect(screen.getAllByText('withheld')).toHaveLength(2)
      expect(screen.getByText(`${LEDGER_KEY.keyId}, not pinned`)).toBeInTheDocument()
    })

    it('shows the problems of another key and of a proof that does not match', async () => {
      const user = await upload(record.token, 'record.txt')
      await user.type(screen.getByLabelText('Ledger public key'), PINNED)
      await user.upload(
        screen.getByLabelText('Index proof file'),
        new File([JSON.stringify(vector('index-proof.json').proofs[2])], 'proof.json'),
      )
      await user.click(screen.getByRole('button', { name: 'Verify' }))
      expect(await screen.findByTestId('verdict')).toHaveTextContent('INVALID')
      expect(screen.getByText('The index proof is not about the agent invoice-agent')).toBeInTheDocument()
      expect(screen.getByText('0 entries at checkpoint 3, does not match the track record')).toBeInTheDocument()
      await user.clear(screen.getByLabelText('Ledger public key'))
      await user.type(screen.getByLabelText('Ledger public key'), (await PrivateKey.generate()).publicKey.encoded)
      await user.click(screen.getByRole('button', { name: 'Verify' }))
      expect(await screen.findByText(/which the ledger keys do not reach/)).toBeInTheDocument()
    })
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
