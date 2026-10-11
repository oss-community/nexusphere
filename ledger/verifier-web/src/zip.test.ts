import { utf8 } from '@nexusphere/ledger'
import { zip } from '../../sdk/typescript/test/support'
import { crc32, isZip, readDispute, readZip } from './zip'

const FILES = {
  'README.txt': 'Nexusphere Ledger dispute package\n',
  'package.json': '{"format":"nexusphere-ledger/package/v1"}',
  'report.json': '{"format":"nexusphere-ledger/report/v1"}',
  'keys.json': '[]',
  'timestamp-authority.pem': '-----BEGIN CERTIFICATE-----\n',
  'rekor.json': '{"origin":"rekor.local","key":"rekor.local+1a2b3c4d+AQ=="}',
  'witnesses.txt': 'witness.example+00000000+AQ==\n\nwitness.other+00000000+AQ==\n',
  'compliance.json': '{}',
}

function texts(files: Map<string, Uint8Array>): Record<string, string> {
  return Object.fromEntries([...files].map(([name, content]) => [name, new TextDecoder().decode(content)]))
}

describe('zip', () => {
  it('has the global DecompressionStream of Node', () => {
    expect(typeof DecompressionStream).toBe('function')
  })

  it('reads stored entries', async () => {
    const archive = zip(FILES)
    expect(isZip(archive)).toBe(true)
    expect(texts(await readZip(archive))).toEqual(FILES)
  })

  it('reads deflated entries with data descriptors, as ZipOutputStream writes them', async () => {
    const big = 'evidence '.repeat(5000)
    const archive = zip({ ...FILES, 'big.txt': big }, { deflate: true, descriptor: true })
    expect(archive.length).toBeLessThan(big.length)
    expect(texts(await readZip(archive))).toEqual({ ...FILES, 'big.txt': big })
  })

  it('reads a fixed deflated archive made by another writer', async () => {
    const bytes = new Uint8Array([
      0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x4b, 0x5d, 0xd7, 0xe5, 0xc9, 0x5b,
      0x0d, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x61, 0x2e, 0x74, 0x78, 0x74, 0x4b,
      0x2c, 0x4d, 0xc9, 0x2c, 0xc9, 0x2f, 0x52, 0x48, 0xc4, 0x41, 0x03, 0x00, 0x50, 0x4b, 0x01, 0x02, 0x14, 0x03,
      0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x4b, 0x5d, 0xd7, 0xe5, 0xc9, 0x5b, 0x0d, 0x00, 0x00, 0x00,
      0x20, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x01,
      0x00, 0x00, 0x00, 0x00, 0x61, 0x2e, 0x74, 0x78, 0x74, 0x50, 0x4b, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x01,
      0x00, 0x01, 0x00, 0x33, 0x00, 0x00, 0x00, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00,
    ])
    expect(crc32(utf8('auditor '.repeat(4)))).toBe(0x5bc9e5d7)
    expect(texts(await readZip(bytes))).toEqual({ 'a.txt': 'auditor '.repeat(4) })
  })

  it('refuses damaged archives', async () => {
    await expect(readZip(utf8('not a zip at all, just text'))).rejects.toThrow('the file is not a ZIP archive')
    const archive = zip({ 'a.txt': 'hello' })
    const changed = archive.slice()
    changed[30 + 5] ^= 1
    await expect(readZip(changed)).rejects.toThrow('a.txt does not match its CRC')
    const moved = archive.slice()
    moved[moved.length - 6] = 1
    await expect(readZip(moved)).rejects.toThrow('the central directory is damaged')
    expect(isZip(utf8('{}'))).toBe(false)
  })

  it('takes the files of a dispute package by their names', async () => {
    const dispute = readDispute(await readZip(zip(FILES, { deflate: true, descriptor: true })))
    expect(dispute).toEqual({
      packageText: FILES['package.json'],
      reportText: FILES['report.json'],
      keyListText: '[]',
      timestampChainText: FILES['timestamp-authority.pem'],
      rekorKey: 'rekor.local+1a2b3c4d+AQ==',
      witnesses: ['witness.example+00000000+AQ==', 'witness.other+00000000+AQ=='],
      used: ['package.json', 'report.json', 'keys.json', 'timestamp-authority.pem', 'rekor.json', 'witnesses.txt'],
      unused: ['README.txt', 'compliance.json'],
    })
  })

  it('takes only what a smaller dispute package has', async () => {
    const dispute = readDispute(await readZip(zip({ 'package.json': '{}', 'rekor.json': '{"origin":"x"}' })))
    expect(dispute).toMatchObject({
      reportText: null,
      keyListText: null,
      timestampChainText: null,
      rekorKey: null,
      witnesses: [],
      used: ['package.json'],
      unused: ['rekor.json'],
    })
    expect(() => readDispute(new Map())).toThrow('the archive has no package.json')
  })
})
