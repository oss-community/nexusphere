import { Disclosure, PrivateKey, SdJwt, confirmationJwk, presentBound } from '@nexusphere/ledger'
import { LEDGER_KEY, keyRecord, vector } from '../../sdk/typescript/test/support'
import { verify } from './verify'
import type { TrackRecordVerification } from './verify'
import { TRACK_RECORD_TYPE, isTrackRecord, verifyTrackRecord } from './trackrecord'

const record = vector('agent-track-record.json')
const PINNED = LEDGER_KEY.publicKey.encoded
const NOW = 1791000000

async function issue(
  changes: Record<string, unknown> = {},
  header: Record<string, unknown> = {},
  recordChanges: Record<string, unknown> = {},
) {
  const { record: claimed, ...payload } = structuredClone(record.claims)
  const { outcomes, principals, actions, ...rest } = claimed
  const disclosures = [
    Disclosure.of('outcomes', outcomes),
    Disclosure.of('principals', principals),
    Disclosure.of('actions', actions),
  ]
  const _sd = await Promise.all(disclosures.map((disclosure) => disclosure.digest()))
  return SdJwt.issue(
    { alg: 'EdDSA', typ: TRACK_RECORD_TYPE, kid: LEDGER_KEY.keyId, ...header },
    { ...payload, record: { ...rest, ...recordChanges, _sd }, _sd_alg: 'sha-256', ...changes },
    disclosures,
    LEDGER_KEY,
  )
}

async function check(token: string, options: { pinnedPublicKey?: string | null; now?: number } = {}) {
  return verifyTrackRecord(token, {
    pinnedPublicKey: options.pinnedPublicKey === undefined ? PINNED : options.pinnedPublicKey,
    keyList: [],
    now: options.now ?? NOW,
  })
}

async function withProof(token: string, proof: unknown = record.indexProof) {
  return (await verify({
    packageText: token,
    publicKey: PINNED,
    witnesses: '',
    requiredWitnesses: '',
    indexProofText: JSON.stringify(proof),
    now: NOW,
  })) as TrackRecordVerification
}

describe('track record', () => {
  it('recognises a track record by its vct', async () => {
    expect(isTrackRecord(record.token)).toBe(true)
    expect(isTrackRecord(JSON.stringify(record.indexProof))).toBe(false)
    expect(isTrackRecord('not a token')).toBe(false)
  })

  it('verifies the vector and reads its claims', async () => {
    const result = await check(record.token)
    expect(result.problems).toEqual([])
    expect(result.keyId).toBe(LEDGER_KEY.keyId)
    expect(result.claims).toMatchObject({
      id: record.claims.jti,
      issuer: 'https://ledger.example',
      agentId: 'invoice-agent',
      audience: null,
      checkpointSequence: 3,
      indexRoot: record.claims.record.checkpoint.indexRoot,
      total: 2,
      firstSequence: 1,
      lastSequence: 2,
      outcomes: { DENIED: 1, SUCCEEDED: 1 },
      principals: 1,
      actions: { 'tools/call': 2 },
      holderKey: null,
    })
  })

  it('withholds the claims the presentation does not disclose', async () => {
    const result = await check(record.presentedWithOutcomesOnly)
    expect(result.problems).toEqual([])
    expect(result.claims).toMatchObject({ outcomes: { DENIED: 1, SUCCEEDED: 1 }, principals: null, actions: null })
  })

  it('rejects another key, a wrong type, an expired record and a missing key', async () => {
    const other = (await PrivateKey.generate()).publicKey.encoded
    expect((await check(record.token, { pinnedPublicKey: other })).problems[0]).toMatch(/which the ledger keys do not reach/)
    expect((await check(await issue({}, { typ: 'vc+sd-jwt' }))).problems).toEqual(['The token is not an EdDSA dc+sd-jwt'])
    expect((await check(record.token, { now: record.claims.exp + 61 })).problems[0]).toMatch(/^The track record expired at/)
    expect((await check(record.token, { now: record.claims.iat - 61 })).problems[0]).toMatch(/is valid from/)
    expect((await check(record.token, { pinnedPublicKey: null })).problems[0]).toMatch(/^No ledger key is given/)
  })

  it('rejects a changed signature and an unreferenced disclosure', async () => {
    const [jwt, ...rest] = record.token.split('.')
    const changed = [jwt, rest[0], (rest[1][0] === 'A' ? 'B' : 'A') + rest[1].slice(1)].join('.')
    expect((await check(changed)).problems).toEqual(["The signature does not match the issuer's key"])
    const extra = record.token + Disclosure.of('outcomes', { SUCCEEDED: 9 }).encoded + '~'
    expect((await check(extra)).claims).toBeNull()
  })

  it('trusts the ledger key from the key list without a pinned key', async () => {
    const result = await verifyTrackRecord(record.token, { pinnedPublicKey: null, keyList: [keyRecord(LEDGER_KEY)], now: NOW })
    expect(result.problems).toEqual([])
  })

  it('checks the key binding of a record bound to the agent key', async () => {
    const agent = await PrivateKey.generate()
    const bound = await issue({ aud: 'https://bank.example', cnf: { jwk: confirmationJwk(agent.publicKey) } })
    expect((await check(bound)).problems).toEqual(['The track record is bound to a key of the agent and needs a key binding'])
    const presented = await presentBound(bound, agent, 'https://bank.example', 'nonce-1', NOW)
    const result = await check(presented)
    expect(result.problems).toEqual([])
    expect(result.claims?.holderKey?.keyId).toBe(agent.publicKey.keyId)
    expect((await check(presented, { now: NOW + 3600 })).problems[0]).toMatch(/outside the accepted window/)
  })

  it('cross-checks a matching index proof', async () => {
    const result = await withProof(record.token)
    expect(result.index?.valid).toBe(true)
    expect(result.mismatches).toEqual([])
    expect(result.valid).toBe(true)
  })

  it('reports an index proof of another agent or count', async () => {
    const proof = vector('index-proof.json').proofs[2]
    const result = await withProof(record.token, proof)
    expect(result.valid).toBe(false)
    expect(result.mismatches).toContain('The index proof is not about the agent invoice-agent')
    expect(result.mismatches).toContain('The track record claims 2 entries but the index proves 0')
    const changed = await withProof(await issue({}, {}, { total: 3 }))
    expect(changed.mismatches).toEqual(['The track record claims 3 entries but the index proves 2'])
  })
})
