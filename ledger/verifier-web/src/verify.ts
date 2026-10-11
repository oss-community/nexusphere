import { INDEX_PROOF_FORMAT, readCertificates, verifyIndexProof, verifyPackage } from '@nexusphere/ledger'
import type { IndexCheck, PackageReport } from '@nexusphere/ledger'
import { isTrackRecord, matchTrackRecord, verifyTrackRecord } from './trackrecord'
import type { TrackRecordCheck } from './trackrecord'

export interface Entry {
  sequence: number
  occurredAt: string
  agentId: string
  principalId: string
  action: string
  target: string | null
  decision: string | null
  outcome: string
  model: string | null
  erased: boolean
}

export interface Verification {
  report: PackageReport
  pinned: boolean
  timestampPinned: boolean
  rekorPinned: boolean
  entries: Entry[]
  pkg: Record<string, unknown>
  publicKey: string | null
  keyList: Record<string, unknown>[] | undefined
  witnessKeys: string[]
}

export interface IndexVerification {
  index: IndexCheck
  pinned: boolean
}

export interface TrackRecordVerification {
  trackRecord: TrackRecordCheck
  pinned: boolean
  index: IndexCheck | null
  mismatches: string[]
  valid: boolean
}

export interface Input {
  packageText: string
  publicKey: string
  keyListText?: string
  witnesses: string
  requiredWitnesses: string
  timestampCertificatesText?: string
  requireTimestamp?: boolean
  rekorKeys?: string
  requireRekor?: boolean
  indexProofText?: string
  now?: number
}

export async function verify(input: Input): Promise<Verification | IndexVerification | TrackRecordVerification> {
  if (isTrackRecord(input.packageText.trim())) {
    return verifyTrackRecordInput(input)
  }
  let pkg: unknown
  try {
    pkg = JSON.parse(input.packageText)
  } catch {
    throw new Error('The file is not JSON.')
  }
  if (!pkg || typeof pkg !== 'object' || Array.isArray(pkg)) {
    throw new Error('The file is not an evidence package.')
  }
  const publicKey = input.publicKey.trim() || null
  const witnesses = input.witnesses
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  const required = input.requiredWitnesses.trim()
  const requiredWitnesses = required === '' ? witnesses.length : Number(required)
  if (!Number.isInteger(requiredWitnesses) || requiredWitnesses < 0) {
    throw new Error('Required witnesses must be a whole number.')
  }
  const keys = keyListOf(input)
  if ((pkg as Record<string, unknown>).format === INDEX_PROOF_FORMAT) {
    return {
      index: await verifyIndexProof(pkg, { pinnedPublicKey: publicKey, keyList: keys }),
      pinned: publicKey !== null,
    }
  }
  let timestampCertificates: ReturnType<typeof readCertificates> = []
  if (input.timestampCertificatesText && input.timestampCertificatesText.trim() !== '') {
    try {
      timestampCertificates = readCertificates(input.timestampCertificatesText)
    } catch {
      throw new Error('The timestamp authority chain is not a PEM file of certificates.')
    }
  }
  const rekorKeys = (input.rekorKeys ?? '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  let report: PackageReport
  try {
    report = await verifyPackage(pkg as Record<string, unknown>, publicKey, {
      witnesses,
      requiredWitnesses,
      keys,
      timestampCertificates,
      requireTimestamp: input.requireTimestamp ?? false,
      rekorKeys,
      requireRekor: input.requireRekor ?? false,
    })
  } catch (e) {
    throw new Error(`The keys cannot be read: ${(e as Error).message}`)
  }
  return {
    report,
    pinned: publicKey !== null,
    timestampPinned: timestampCertificates.length > 0,
    rekorPinned: rekorKeys.length > 0,
    entries: entriesOf(pkg as Record<string, unknown>),
    pkg: pkg as Record<string, unknown>,
    publicKey,
    keyList: keys,
    witnessKeys: witnesses,
  }
}

async function verifyTrackRecordInput(input: Input): Promise<TrackRecordVerification> {
  const publicKey = input.publicKey.trim() || null
  const keyList = keyListOf(input) ?? []
  let proof: unknown = null
  if (input.indexProofText && input.indexProofText.trim() !== '') {
    try {
      proof = JSON.parse(input.indexProofText)
    } catch {
      throw new Error('The index proof is not JSON.')
    }
  }
  const trackRecord = await verifyTrackRecord(input.packageText.trim(), {
    pinnedPublicKey: publicKey,
    keyList,
    now: input.now ?? Math.floor(Date.now() / 1000),
  })
  const index = proof === null ? null : await verifyIndexProof(proof, { pinnedPublicKey: publicKey, keyList })
  const mismatches = index && trackRecord.claims ? matchTrackRecord(trackRecord.claims, proof, index) : []
  return {
    trackRecord,
    pinned: publicKey !== null,
    index,
    mismatches,
    valid: trackRecord.claims !== null && trackRecord.problems.length === 0 &&
      (index === null || (index.valid && mismatches.length === 0)),
  }
}

function keyListOf(input: Input): Record<string, unknown>[] | undefined {
  if (!input.keyListText || input.keyListText.trim() === '') {
    return undefined
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(input.keyListText)
  } catch {
    throw new Error('The key list is not JSON.')
  }
  if (!Array.isArray(parsed)) {
    throw new Error('The key list must be the array that GET /api/v1/keys returns.')
  }
  return parsed as Record<string, unknown>[]
}

function entriesOf(pkg: Record<string, unknown>): Entry[] {
  const links = Array.isArray(pkg.links) ? pkg.links : []
  return links
    .map((link) => (link && typeof link === 'object' ? (link as Record<string, unknown>).entry : null))
    .filter((entry): entry is Record<string, unknown> => !!entry && typeof entry === 'object')
    .map((entry) => ({
      sequence: Number(entry.sequence),
      occurredAt: String(entry.occurredAt ?? ''),
      agentId: String(entry.agentId ?? ''),
      principalId: String(entry.principalId ?? ''),
      action: String(entry.action ?? ''),
      target: entry.target == null ? null : String(entry.target),
      decision: entry.decision == null ? null : String(entry.decision),
      outcome: String(entry.outcome ?? ''),
      model: modelOf(entry.model),
      erased: !entry.salts || typeof entry.salts !== 'object',
    }))
}

function modelOf(model: unknown): string | null {
  if (!model || typeof model !== 'object') {
    return null
  }
  const { name, version } = model as Record<string, unknown>
  const parts = [name, version].filter((part): part is string => typeof part === 'string' && part !== '')
  return parts.length ? parts.join(' ') : null
}
