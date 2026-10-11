import {
  ED25519,
  EvidenceStatement,
  GENESIS,
  LogReceipt,
  Note,
  NoteKey,
  commitments as computeCommitments,
  contentHash,
  equal,
  fromBase64,
  fromHex,
  linkHash,
  merkle,
  packageTrustedKeys,
  toHex,
} from '@nexusphere/ledger'
import type { Json, PublicKey } from '@nexusphere/ledger'

export type CheckStatus = 'passed' | 'failed' | 'not present'

export interface Check {
  name: string
  status: CheckStatus
  detail: string
}

export interface AuditEntry {
  sequence: number
  occurredAt: string
  agentId: string
  principalId: string | null
  action: string
  target: string | null
  decision: string | null
  outcome: string
  model: string | null
  erased: boolean
  attributes: Record<string, string>
  entry: Json
}

export interface ChainLink {
  sequence: number
  previousHash: string
  hash: string
  disclosed: boolean
}

export interface Audit {
  links: Json[]
  positions: Map<number, number>
  entries: AuditEntry[]
  firstSequence: number
  startHash: string
  anchorSequence: number | null
  checkpointSequence: number | null
  checkpointHash: string | null
  note: Note | null
  noteProblem: string | null
  proofs: Map<number, Json>
  keys: PublicKey[]
  keyProblems: string[]
}

export interface EntryResult {
  sequence: number
  checks: Check[]
  commitments: Record<string, string> | null
  valid: boolean
}

export interface AuditOptions {
  publicKey?: string | null
  keyList?: Json[]
}

export const CHECK_NAMES = [
  'Content hash',
  'Entry hash',
  'Hash link',
  'Inclusion proof',
  'SCITT statement',
  'SCITT receipt',
] as const

export async function prepareAudit(pkg: Json, options: AuditOptions = {}): Promise<Audit> {
  const links = (Array.isArray(pkg.links) ? pkg.links : []).filter(isObject) as Json[]
  const positions = new Map<number, number>()
  links.forEach((link, index) => positions.set(Number(link.sequence), index))
  const anchor = isObject(pkg.anchor) ? pkg.anchor : null
  const checkpoint = isObject(pkg.checkpoint) ? pkg.checkpoint : null
  const anchorSequence = anchor && typeof anchor.sequence === 'number' ? anchor.sequence : null
  let note: Note | null = null
  let noteProblem: string | null = null
  const proofs = new Map<number, Json>()
  if (isObject(pkg.log)) {
    try {
      note = Note.parse(String(pkg.log.checkpoint ?? ''))
    } catch (e) {
      noteProblem = (e as Error).message
    }
    for (const proof of Array.isArray(pkg.log.proofs) ? pkg.log.proofs : []) {
      if (isObject(proof) && typeof proof.sequence === 'number') {
        proofs.set(proof.sequence, proof)
      }
    }
  }
  const keyProblems: string[] = []
  const keys = await packageTrustedKeys(pkg, options.keyList ?? [], options.publicKey ?? null, keyProblems)
  return {
    links,
    positions,
    entries: links.filter((link) => isObject(link.entry)).map((link) => auditEntry(link.entry as Json)),
    firstSequence: anchorSequence === null ? 1 : anchorSequence + 1,
    startHash: anchor && typeof anchor.headHash === 'string' ? anchor.headHash : GENESIS,
    anchorSequence,
    checkpointSequence: checkpoint && typeof checkpoint.sequence === 'number' ? checkpoint.sequence : null,
    checkpointHash: checkpoint && typeof checkpoint.headHash === 'string' ? checkpoint.headHash : null,
    note,
    noteProblem,
    proofs,
    keys: [...keys.values()],
    keyProblems,
  }
}

export function chainLink(audit: Audit, index: number): ChainLink {
  const link = audit.links[index]
  return {
    sequence: Number(link.sequence),
    previousHash: String(link.previousHash ?? ''),
    hash: String(link.hash ?? ''),
    disclosed: isObject(link.entry),
  }
}

function auditEntry(entry: Json): AuditEntry {
  const attributes: Record<string, string> = {}
  if (isObject(entry.attributes)) {
    for (const [name, value] of Object.entries(entry.attributes)) {
      attributes[name] = value === null || value === undefined ? '' : String(value)
    }
  }
  return {
    sequence: Number(entry.sequence),
    occurredAt: String(entry.occurredAt ?? ''),
    agentId: String(entry.agentId ?? ''),
    principalId: typeof entry.principalId === 'string' ? entry.principalId : null,
    action: String(entry.action ?? ''),
    target: typeof entry.target === 'string' ? entry.target : null,
    decision: typeof entry.decision === 'string' ? entry.decision : null,
    outcome: String(entry.outcome ?? ''),
    model: modelOf(entry.model),
    erased: !isObject(entry.salts),
    attributes,
    entry,
  }
}

function modelOf(model: unknown): string | null {
  if (!isObject(model)) {
    return null
  }
  const parts = [model.name, model.version].filter((part): part is string => typeof part === 'string' && part !== '')
  return parts.length ? parts.join(' ') : null
}

export async function checkEntry(audit: Audit, sequence: number): Promise<EntryResult> {
  const position = audit.positions.get(sequence)
  const link = position === undefined ? undefined : audit.links[position]
  if (!link || !isObject(link.entry)) {
    throw new Error(`sequence ${sequence} is not disclosed in the package`)
  }
  const entry = link.entry
  const checks: Check[] = []
  let computedContent: string | null = null
  let computedHash: string | null = null
  try {
    computedContent = await contentHash(entry)
    computedHash = await linkHash(Number(entry.sequence), String(entry.previousHash), computedContent)
  } catch (e) {
    checks.push(failed('Content hash', `the entry cannot be read: ${(e as Error).message}`))
    checks.push(failed('Entry hash', 'the content hash cannot be computed'))
  }
  if (computedContent !== null) {
    checks.push(computedContent === link.contentHash
      ? passed('Content hash', `${computedContent} matches the link`)
      : failed('Content hash', `recomputed ${computedContent}, the link has ${String(link.contentHash)}`))
  }
  if (computedHash !== null) {
    const belongs = entry.sequence === link.sequence && entry.previousHash === link.previousHash
    if (!belongs) {
      checks.push(failed('Entry hash', 'the entry names another sequence or previous hash than its link'))
    } else if (computedHash !== link.hash || entry.hash !== link.hash) {
      checks.push(failed('Entry hash', `recomputed ${computedHash}, the link has ${String(link.hash)}`))
    } else {
      checks.push(passed('Entry hash', `${computedHash} matches the link`))
    }
  }
  checks.push(hashLink(audit, position!, link))
  const proof = audit.proofs.get(sequence)
  const leafSource = computedHash ?? String(link.hash ?? '')
  checks.push(await inclusion(audit, sequence, proof, leafSource))
  const statement = await statementCheck(audit, proof, link, computedContent)
  checks.push(statement)
  checks.push(await receiptCheck(audit, proof, leafSource))
  let commitments: Record<string, string> | null = null
  try {
    commitments = await computeCommitments(entry)
  } catch {
    commitments = isObject(entry.commitments) ? (entry.commitments as Record<string, string>) : null
  }
  return {
    sequence,
    checks,
    commitments,
    valid: checks.every((check) => check.status !== 'failed'),
  }
}

function hashLink(audit: Audit, position: number, link: Json): Check {
  const sequence = Number(link.sequence)
  let expected: string
  let source: string
  if (position === 0) {
    if (sequence !== audit.firstSequence) {
      return failed('Hash link', `the package starts at sequence ${sequence}, not at ${audit.firstSequence}`)
    }
    expected = audit.startHash
    source = audit.anchorSequence === null ? 'the genesis hash' : `the head of anchor checkpoint ${audit.anchorSequence}`
  } else {
    const previous = audit.links[position - 1]
    if (Number(previous.sequence) !== sequence - 1) {
      return failed('Hash link', `the link before has sequence ${String(previous.sequence)}, not ${sequence - 1}`)
    }
    expected = String(previous.hash)
    source = `the hash of sequence ${sequence - 1}`
  }
  return link.previousHash === expected
    ? passed('Hash link', `the previous hash is ${source}`)
    : failed('Hash link', `the previous hash ${String(link.previousHash)} is not ${source}`)
}

async function inclusion(audit: Audit, sequence: number, proof: Json | undefined, hash: string): Promise<Check> {
  if (!audit.note) {
    return audit.noteProblem
      ? failed('Inclusion proof', `the log checkpoint cannot be read: ${audit.noteProblem}`)
      : absent('Inclusion proof', 'the package has no log')
  }
  if (!proof) {
    return failed('Inclusion proof', 'the package has a log but no proof for this entry')
  }
  const checkpoint = audit.note.checkpoint
  try {
    const hashes = (Array.isArray(proof.hashes) ? proof.hashes : []).map((item) => fromBase64(String(item)))
    const leaf = await merkle.leafHash(fromHex(hash))
    if (await merkle.verifyInclusion(leaf, sequence - 1, checkpoint.size, hashes, checkpoint.root)) {
      return passed('Inclusion proof', `leaf ${sequence - 1} leads to the root of tree size ${checkpoint.size}`)
    }
  } catch (e) {
    return failed('Inclusion proof', `the proof cannot be read: ${(e as Error).message}`)
  }
  return failed('Inclusion proof', `the proof does not lead to the root of tree size ${checkpoint.size}`)
}

async function statementCheck(
  audit: Audit,
  proof: Json | undefined,
  link: Json,
  computedContent: string | null,
): Promise<Check> {
  if (!proof || typeof proof.statement !== 'string') {
    return absent('SCITT statement', 'the package has no statement for this entry')
  }
  let statement: EvidenceStatement
  try {
    statement = EvidenceStatement.parse(fromBase64(proof.statement))
  } catch (e) {
    return failed('SCITT statement', `the statement cannot be read: ${(e as Error).message}`)
  }
  const key = audit.keys.find((item) => item.keyId === statement.keyId)
  if (!key || !(await statement.verify(key))) {
    return failed('SCITT statement', `the statement is not signed by a trusted ledger key (${statement.keyId ?? 'no key ID'})`)
  }
  const described = {
    sequence: Number(link.sequence),
    previousHash: String(link.previousHash),
    contentHash: computedContent ?? '',
  }
  if (!statement.describes(described)) {
    return failed('SCITT statement', `the statement by ${key.keyId} is for different evidence`)
  }
  return passed('SCITT statement', `signed by ${key.keyId} for ${statement.subject}`)
}

async function receiptCheck(audit: Audit, proof: Json | undefined, hash: string): Promise<Check> {
  if (!proof || typeof proof.receipt !== 'string') {
    return absent('SCITT receipt', 'the package has no receipt for this entry')
  }
  let receipt: LogReceipt
  try {
    receipt = LogReceipt.parse(fromBase64(proof.receipt))
  } catch (e) {
    return failed('SCITT receipt', `the receipt cannot be read: ${(e as Error).message}`)
  }
  let leaf: Uint8Array
  try {
    leaf = await merkle.leafHash(fromHex(hash))
  } catch {
    return failed('SCITT receipt', 'the entry hash cannot be read')
  }
  const key = audit.keys.find((item) => item.keyId === receipt.keyId)
  if (!key || !(await receipt.verify(leaf, key))) {
    return failed('SCITT receipt', 'the receipt does not prove this entry with a trusted ledger key')
  }
  const checkpoint = audit.note?.checkpoint
  if (checkpoint && (receipt.treeSize !== checkpoint.size || !equal(await receipt.root(leaf), checkpoint.root))) {
    return failed('SCITT receipt', 'the receipt is for a different log checkpoint')
  }
  return passed('SCITT receipt', `signed by ${key.keyId}, leaf ${receipt.leafIndex} of tree size ${receipt.treeSize}`)
}

export async function checkEntries(audit: Audit, sequences: number[]): Promise<Map<number, EntryResult>> {
  const results = new Map<number, EntryResult>()
  for (const sequence of sequences) {
    results.set(sequence, await checkEntry(audit, sequence))
  }
  return results
}

function passed(name: string, detail: string): Check {
  return { name, status: 'passed', detail }
}

function failed(name: string, detail: string): Check {
  return { name, status: 'failed', detail }
}

function absent(name: string, detail: string): Check {
  return { name, status: 'not present', detail }
}

export interface Filter {
  agent: string
  principal: string
  action: string
  decision: string
  outcome: string
  from: string
  to: string
  text: string
}

export const NO_FILTER: Filter = {
  agent: '',
  principal: '',
  action: '',
  decision: '',
  outcome: '',
  from: '',
  to: '',
  text: '',
}

export const FILTER_FIELDS = ['agent', 'principal', 'action', 'decision', 'outcome'] as const

export type FilterField = (typeof FILTER_FIELDS)[number]

function fieldOf(entry: AuditEntry, field: FilterField): string | null {
  switch (field) {
    case 'agent':
      return entry.agentId
    case 'principal':
      return entry.principalId
    case 'action':
      return entry.action
    case 'decision':
      return entry.decision
    case 'outcome':
      return entry.outcome
  }
}

export function filterOptions(entries: AuditEntry[]): Record<FilterField, string[]> {
  const options = {} as Record<FilterField, string[]>
  for (const field of FILTER_FIELDS) {
    const values = new Set<string>()
    for (const entry of entries) {
      const value = fieldOf(entry, field)
      if (value !== null && value !== '') {
        values.add(value)
      }
    }
    options[field] = [...values].sort()
  }
  return options
}

export function filterEntries(entries: AuditEntry[], filter: Filter): AuditEntry[] {
  const text = filter.text.trim().toLowerCase()
  return entries.filter((entry) => {
    for (const field of FILTER_FIELDS) {
      if (filter[field] !== '' && fieldOf(entry, field) !== filter[field]) {
        return false
      }
    }
    const day = entry.occurredAt.slice(0, 10)
    if ((filter.from !== '' && day < filter.from) || (filter.to !== '' && day > filter.to)) {
      return false
    }
    if (text !== '') {
      const haystack = [
        entry.target ?? '',
        ...Object.entries(entry.attributes).map(([name, value]) => `${name} ${value}`),
      ].join('\n').toLowerCase()
      if (!haystack.includes(text)) {
        return false
      }
    }
    return true
  })
}

export const WORKSHEET_COLUMNS = [
  'sequence',
  'occurredAt',
  'agentId',
  'principalId',
  'action',
  'target',
  'decision',
  'outcome',
  'model',
  'hash',
  'contentHashCheck',
  'entryHashCheck',
  'hashLinkCheck',
  'inclusionProofCheck',
  'statementCheck',
  'receiptCheck',
]

export function worksheet(entries: AuditEntry[], results: Map<number, EntryResult>): string {
  const lines = [WORKSHEET_COLUMNS.join(',')]
  for (const entry of entries) {
    const result = results.get(entry.sequence)
    const statuses = CHECK_NAMES.map((name) => result?.checks.find((check) => check.name === name)?.status ?? '')
    lines.push([
      String(entry.sequence),
      entry.occurredAt,
      entry.agentId,
      entry.erased ? 'erased' : (entry.principalId ?? ''),
      entry.action,
      entry.erased ? 'erased' : (entry.target ?? ''),
      entry.decision ?? '',
      entry.outcome,
      entry.model ?? '',
      String(entry.entry.hash ?? ''),
      ...statuses,
    ].map(cell).join(','))
  }
  return lines.join('\r\n') + '\r\n'
}

function cell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

export interface SignatureLine {
  name: string
  keyHash: string
  kind: 'ledger signature' | 'cosignature' | 'signature'
  verified: boolean
  detail: string
}

export async function logSignatures(audit: Audit, witnessKeys: string[]): Promise<SignatureLine[]> {
  const note = audit.note
  if (!note) {
    return []
  }
  const witnesses: NoteKey[] = []
  for (const text of witnessKeys) {
    try {
      witnesses.push(await NoteKey.parse(text))
    } catch {
      continue
    }
  }
  const lines: SignatureLine[] = []
  for (const signature of note.signatures) {
    const single = new Note(note.checkpoint, note.body, [signature])
    const keyHash = toHex(signature.keyHash)
    let line: SignatureLine | null = null
    for (const key of audit.keys) {
      if (await single.signedBy(await NoteKey.of(note.checkpoint.origin, ED25519, key))) {
        line = { name: signature.name, keyHash, kind: 'ledger signature', verified: true, detail: `verified with ledger key ${key.keyId}` }
        break
      }
    }
    if (!line && signature.signature.length === 72) {
      const named = witnesses.filter((key) => key.name === signature.name)
      for (const key of named) {
        const time = await single.cosignedBy(key)
        if (time !== null) {
          line = {
            name: signature.name,
            keyHash,
            kind: 'cosignature',
            verified: true,
            detail: `verified with the given witness key, cosigned at ${new Date(time * 1000).toISOString()}`,
          }
          break
        }
      }
      line ??= {
        name: signature.name,
        keyHash,
        kind: 'cosignature',
        verified: false,
        detail: named.length ? 'does not verify with the given witness key' : 'no witness key given for this name',
      }
    }
    lines.push(line ?? {
      name: signature.name,
      keyHash,
      kind: 'signature',
      verified: false,
      detail: 'not verified with a trusted ledger key',
    })
  }
  return lines
}

function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
