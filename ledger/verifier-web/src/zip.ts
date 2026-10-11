const END_OF_DIRECTORY = 0x06054b50
const DIRECTORY_ENTRY = 0x02014b50
const LOCAL_HEADER = 0x04034b50
const MAX_FILE = 256 * 1024 * 1024

export const DISPUTE_FILES = [
  'package.json',
  'report.json',
  'keys.json',
  'timestamp-authority.pem',
  'rekor.json',
  'witnesses.txt',
] as const

export interface DisputeArchive {
  packageText: string
  reportText: string | null
  keyListText: string | null
  timestampChainText: string | null
  rekorKey: string | null
  witnesses: string[]
  used: string[]
  unused: string[]
}

export function isZip(bytes: Uint8Array): boolean {
  return bytes.length >= 4 && new DataView(bytes.buffer, bytes.byteOffset, 4).getUint32(0, true) === LOCAL_HEADER
}

export async function readZip(data: Uint8Array): Promise<Map<string, Uint8Array>> {
  const files = new Map<string, Uint8Array>()
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  let end = data.length - 22
  const lowest = Math.max(0, data.length - 22 - 0xffff)
  while (end >= lowest && view.getUint32(end, true) !== END_OF_DIRECTORY) {
    end--
  }
  if (end < lowest) {
    throw new Error('the file is not a ZIP archive')
  }
  let offset = view.getUint32(end + 16, true)
  const names = new TextDecoder('utf-8', { fatal: false })
  for (let count = view.getUint16(end + 10, true); count > 0; count--) {
    if (offset + 46 > data.length || view.getUint32(offset, true) !== DIRECTORY_ENTRY) {
      throw new Error('the central directory is damaged')
    }
    const method = view.getUint16(offset + 10, true)
    const crc = view.getUint32(offset + 16, true)
    const compressed = view.getUint32(offset + 20, true)
    const size = view.getUint32(offset + 24, true)
    const nameLength = view.getUint16(offset + 28, true)
    const local = view.getUint32(offset + 42, true)
    const name = names.decode(data.subarray(offset + 46, offset + 46 + nameLength))
    offset += 46 + nameLength + view.getUint16(offset + 30, true) + view.getUint16(offset + 32, true)
    if (local + 30 > data.length || view.getUint32(local, true) !== LOCAL_HEADER) {
      throw new Error(`the local header of ${name} is damaged`)
    }
    if (size > MAX_FILE) {
      throw new Error(`${name} is larger than ${MAX_FILE} bytes`)
    }
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true)
    if (start + compressed > data.length) {
      throw new Error(`${name} is cut short`)
    }
    const raw = data.subarray(start, start + compressed)
    let content: Uint8Array
    if (method === 0) {
      content = raw.slice()
    } else if (method === 8) {
      content = await inflate(raw)
    } else {
      throw new Error(`${name} uses compression method ${method}, which is not supported`)
    }
    if (content.length !== size) {
      throw new Error(`${name} has ${content.length} bytes, not ${size}`)
    }
    if (crc32(content) !== crc) {
      throw new Error(`${name} does not match its CRC`)
    }
    if (!name.endsWith('/')) {
      files.set(name, content)
    }
  }
  return files
}

async function inflate(raw: Uint8Array): Promise<Uint8Array> {
  const input = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(raw.slice())
      controller.close()
    },
  })
  const reader = input.pipeThrough(new DecompressionStream('deflate-raw') as unknown as TransformStream<Uint8Array, Uint8Array>)
    .getReader()
  const parts: Uint8Array[] = []
  let length = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    parts.push(value)
    length += value.length
    if (length > MAX_FILE) {
      throw new Error(`a file is larger than ${MAX_FILE} bytes`)
    }
  }
  const out = new Uint8Array(length)
  let at = 0
  for (const part of parts) {
    out.set(part, at)
    at += part.length
  }
  return out
}

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  }
  return c >>> 0
})

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff
  for (const byte of data) {
    c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8)
  }
  return (c ^ 0xffffffff) >>> 0
}

export function readDispute(files: Map<string, Uint8Array>): DisputeArchive {
  const decoder = new TextDecoder()
  const text = (name: string) => {
    const content = files.get(name)
    return content === undefined ? null : decoder.decode(content)
  }
  const packageText = text('package.json')
  if (packageText === null) {
    throw new Error('the archive has no package.json')
  }
  let rekorKey: string | null = null
  const rekorText = text('rekor.json')
  if (rekorText !== null) {
    try {
      const rekor = JSON.parse(rekorText)
      if (rekor && typeof rekor === 'object' && typeof rekor.key === 'string') {
        rekorKey = rekor.key
      }
    } catch {
      rekorKey = null
    }
  }
  const witnesses = (text('witnesses.txt') ?? '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  const used = DISPUTE_FILES.filter((name) => files.has(name) && (name !== 'rekor.json' || rekorKey !== null))
  return {
    packageText,
    reportText: text('report.json'),
    keyListText: text('keys.json'),
    timestampChainText: text('timestamp-authority.pem'),
    rekorKey,
    witnesses,
    used,
    unused: [...files.keys()].filter((name) => !(used as string[]).includes(name)),
  }
}
