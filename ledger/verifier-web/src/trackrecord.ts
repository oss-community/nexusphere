import { SdJwt, isSha256, jwkPublicKey, keyBindingProblem, packageTrustedKeys } from '@nexusphere/ledger'
import type { IndexCheck, PublicKey } from '@nexusphere/ledger'

export const TRACK_RECORD_TYPE = 'dc+sd-jwt'
export const TRACK_RECORD_VCT = 'urn:nexusphere:vct:agent-track-record:1'

const ALGORITHM = 'EdDSA'
const CLOCK_SKEW = 60
const KEY_BINDING_MAX_AGE = 300
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

type Json = Record<string, unknown>

export interface TrackRecord {
  id: string
  issuer: string
  agentId: string
  audience: string | null
  issuedAt: number
  expiresAt: number
  checkpointSequence: number
  headHash: string
  indexRoot: string
  total: number
  firstSequence: number | null
  lastSequence: number | null
  firstAt: string | null
  lastAt: string | null
  outcomes: Record<string, number> | null
  principals: number | null
  actions: Record<string, number> | null
  holderKey: PublicKey | null
}

export interface TrackRecordCheck {
  claims: TrackRecord | null
  keyId: string | null
  problems: string[]
}

export interface TrackRecordOptions {
  pinnedPublicKey: string | null
  keyList: Json[]
  now: number
}

export function isTrackRecord(text: string): boolean {
  try {
    return SdJwt.parse(text).jwt.payload.vct === TRACK_RECORD_VCT
  } catch {
    return false
  }
}

export async function trackRecordClaims(p: Json): Promise<TrackRecord> {
  if (p.vct !== TRACK_RECORD_VCT) {
    throw new Error(`The token is not a ${TRACK_RECORD_VCT} credential`)
  }
  const r = isObject(p.record) ? p.record : {}
  const c = isObject(r.checkpoint) ? r.checkpoint : {}
  const active = typeof r.firstSequence === 'number'
  const id = text(p, 'jti')
  if (!UUID.test(id)) {
    throw new Error('The track record has a jti that is not a UUID')
  }
  const headHash = text(c, 'headHash')
  const indexRoot = text(c, 'indexRoot')
  if (!isSha256(headHash) || !isSha256(indexRoot)) {
    throw new Error('The track record names no valid head hash or index root')
  }
  return {
    id,
    issuer: text(p, 'iss'),
    agentId: text(p, 'sub'),
    audience: typeof p.aud === 'string' ? p.aud : null,
    issuedAt: integer(p, 'iat'),
    expiresAt: integer(p, 'exp'),
    checkpointSequence: integer(c, 'sequence'),
    headHash,
    indexRoot,
    total: integer(r, 'total'),
    firstSequence: active ? integer(r, 'firstSequence') : null,
    lastSequence: active ? integer(r, 'lastSequence') : null,
    firstAt: active ? instant(r, 'firstAt') : null,
    lastAt: active ? instant(r, 'lastAt') : null,
    outcomes: counts(r.outcomes),
    principals: typeof r.principals === 'number' ? integer(r, 'principals') : null,
    actions: counts(r.actions),
    holderKey: isObject(p.cnf) ? await holderKey(p.cnf) : null,
  }
}

export async function verifyTrackRecord(token: string, options: TrackRecordOptions): Promise<TrackRecordCheck> {
  let sdJwt: SdJwt
  let claims: TrackRecord
  try {
    sdJwt = SdJwt.parse(token)
    claims = await trackRecordClaims(await sdJwt.claims())
  } catch (e) {
    return { claims: null, keyId: null, problems: [(e as Error).message] }
  }
  const header = sdJwt.jwt.header
  const keyId = typeof header.kid === 'string' ? header.kid : ''
  const failed = (problem: string): TrackRecordCheck => ({ claims, keyId: keyId || null, problems: [problem] })
  if (header.typ !== TRACK_RECORD_TYPE || header.alg !== ALGORITHM) {
    return failed(`The token is not an EdDSA ${TRACK_RECORD_TYPE}`)
  }
  if (options.pinnedPublicKey === null && options.keyList.length === 0) {
    return failed("No ledger key is given: paste the ledger's public key or choose the key list")
  }
  const problems: string[] = []
  const trusted = await packageTrustedKeys({}, options.keyList, options.pinnedPublicKey, problems)
  if (problems.length > 0) {
    return { claims, keyId: keyId || null, problems }
  }
  const key = trusted.get(keyId)
  if (!key) {
    return failed(`The track record is signed with key ${keyId || 'none'}, which the ledger keys do not reach`)
  }
  if (options.keyList.some((node) => node.keyId === keyId && isObject(node.revocation))) {
    return failed(`The track record is signed with revoked key ${keyId}`)
  }
  if (!(await sdJwt.jwt.verify(key))) {
    return failed("The signature does not match the issuer's key")
  }
  const now = options.now
  if (now + CLOCK_SKEW < claims.issuedAt) {
    problems.push(`The track record is valid from ${instantText(claims.issuedAt)}`)
  }
  if (now - CLOCK_SKEW >= claims.expiresAt) {
    problems.push(`The track record expired at ${instantText(claims.expiresAt)}`)
  }
  if (claims.holderKey === null) {
    if (sdJwt.keyBinding !== null) {
      problems.push('The track record names no agent key, so it cannot carry a key binding')
    }
  } else if (sdJwt.keyBinding === null) {
    problems.push('The track record is bound to a key of the agent and needs a key binding')
  } else {
    const binding = await keyBindingProblem(
      sdJwt,
      claims.holderKey,
      claims.audience,
      null,
      now,
      CLOCK_SKEW,
      KEY_BINDING_MAX_AGE,
    )
    if (binding !== null) {
      problems.push(binding)
    }
  }
  return { claims, keyId, problems }
}

export function matchTrackRecord(claims: TrackRecord, proof: unknown, index: IndexCheck): string[] {
  const problems: string[] = []
  if (index.term.type !== 'agent' || index.term.agentId !== claims.agentId) {
    problems.push(`The index proof is not about the agent ${claims.agentId}`)
  }
  const root = isObject(proof) && isObject(proof.index) ? proof.index.root : null
  if (
    index.sequence !== claims.checkpointSequence ||
    index.headHash !== claims.headHash ||
    root !== claims.indexRoot
  ) {
    problems.push('The index proof is for another checkpoint or index root than the track record')
  }
  const count = index.present ? index.count : 0
  if (
    count !== claims.total ||
    (index.present ? index.firstSequence : null) !== claims.firstSequence ||
    (index.present ? index.lastSequence : null) !== claims.lastSequence
  ) {
    problems.push(`The track record claims ${claims.total} entries but the index proves ${count}`)
  }
  return problems
}

export function instantText(seconds: number): string {
  return new Date(seconds * 1000).toISOString().replace('.000Z', 'Z')
}

async function holderKey(cnf: Json): Promise<PublicKey> {
  if (!isObject(cnf.jwk)) {
    throw new Error('The track record has a cnf claim without an Ed25519 jwk')
  }
  return jwkPublicKey(cnf.jwk)
}

function counts(node: unknown): Record<string, number> | null {
  if (node === undefined || node === null) {
    return null
  }
  if (!isObject(node)) {
    throw new Error('The track record has malformed counts')
  }
  const result: Record<string, number> = {}
  for (const name of Object.keys(node).sort()) {
    const value = node[name]
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
      throw new Error(`The track record has a malformed count for ${name}`)
    }
    result[name] = value
  }
  return result
}

function text(node: Json, name: string): string {
  if (typeof node[name] !== 'string') {
    throw new Error(`The track record has no ${name}`)
  }
  return node[name] as string
}

function integer(node: Json, name: string): number {
  const value = node[name]
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error(`The track record has no ${name}`)
  }
  return value
}

function instant(node: Json, name: string): string {
  const value = text(node, name)
  if (Number.isNaN(Date.parse(value))) {
    throw new Error(`The track record has no ${name}`)
  }
  return value
}

function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
