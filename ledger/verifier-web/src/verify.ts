import { verifyPackage } from '@nexusphere/ledger'
import type { PackageReport } from '@nexusphere/ledger'

export interface Entry {
  sequence: number
  occurredAt: string
  agentId: string
  principalId: string
  action: string
  target: string | null
  decision: string | null
  outcome: string
}

export interface Verification {
  report: PackageReport
  pinned: boolean
  entries: Entry[]
}

export interface Input {
  packageText: string
  publicKey: string
  witnesses: string
  requiredWitnesses: string
}

export async function verify(input: Input): Promise<Verification> {
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
  let report: PackageReport
  try {
    report = await verifyPackage(pkg as Record<string, unknown>, publicKey, { witnesses, requiredWitnesses })
  } catch (e) {
    throw new Error(`The keys cannot be read: ${(e as Error).message}`)
  }
  return { report, pinned: publicKey !== null, entries: entriesOf(pkg as Record<string, unknown>) }
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
    }))
}
