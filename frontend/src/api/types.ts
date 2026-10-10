export interface Page<T> {
  items: T[]
  nextAfter: number | string | null
}

export interface Evidence {
  format: string
  id: string
  sequence: number
  occurredAt: string
  recordedAt: string
  agentId: string
  principalId: string | null
  action: string
  target: string | null
  decision: string | null
  reason: string | null
  delegationId: string | null
  inputHash: string | null
  outputHash: string | null
  outcome: string
  correlationId: string | null
  attributes: Record<string, string>
  previousHash: string
  hash: string
}

export interface Head {
  sequence: number
  hash: string
}

export interface Checkpoint {
  format: string
  sequence: number
  headHash: string
  createdAt: string
  keyId: string
  signature: string
}

export interface SigningKey {
  keyId: string
  algorithm: string
  publicKey: string
  status: 'ACTIVE' | 'RETIRED' | 'REVOKED'
  activatedAt: string
  retiredAt: string | null
  rotation: { previousKeyId: string } | null
  revocation: { compromisedAt: string; revokedAt: string; reason: string; revokerKeyId: string } | null
}

export interface VerificationReport {
  valid: boolean
  checkedEntries: number
  checkedCheckpoints: number
  headSequence: number
  headHash: string
  latestCheckpointSequence: number | null
  failedSequence: number | null
  failure: string | null
}

export interface Agent {
  agentId: string
  name: string
  ownerId: string
  status: string
  keyPrefix: string
  createdAt: string
  signingKey: string | null
  signingKeyId: string | null
  signingKeySetAt: string | null
}

export interface IssuedAgent extends Agent {
  apiKey: string
}

export interface Grant {
  id: string
  principalId: string
  agentId: string
  actions: string[]
  targets: string[]
  notBefore: string | null
  expiresAt: string
  maxUses: number | null
  createdAt: string
  termsHash: string
  uses: number
  status: string
  reason: string | null
  revokedAt: string | null
  revokeReason: string | null
  consent: 'OPERATOR' | 'PRINCIPAL' | null
  consentedAt: string | null
}

export interface GrantDraft {
  principalId?: string
  agentId: string
  actions: string[]
  targets: string[]
  expiresAt: string
  maxUses?: number
  reason?: string
}

export interface Mandate {
  id: string
  grantId: string
  agentId: string
  principalId: string
  audience: string
  statusIndex: number
  issuedAt: string
  expiresAt: string
  status: string
  revokedAt: string | null
  revokeReason: string | null
  token: string
}

export interface Exchange {
  id: string
  direction: string
  peer: string
  requestId: string
  agentId: string
  principalId: string
  method: string
  mandateId: string
  mandate: string
  request: string
  requestHash: string
  responseHash: string | null
  status: number | null
  outcome: string | null
  evidenceSequence: number | null
  receipt: string | null
  receiptStatus: string | null
  createdAt: string
}

export interface PackageReport {
  valid: boolean
  keyId: string | null
  pinnedKeyId: string | null
  anchorSequence: number | null
  checkpointSequence: number
  checkpointCreatedAt: string | null
  firstSequence: number
  checkedLinks: number
  disclosedEntries: number
  agentId: string | null
  principalId: string | null
  problems: string[]
}

export interface Principal {
  principalId: string
  name: string
  issuer: string
  subject: string
  authTime: string | null
  expiresAt: string | null
}

export interface OidcInfo {
  issuer: string
  clientId: string
}
