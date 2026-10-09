import { compare, fromUtf8, utf8 } from "./bytes.js";

export class Tagged {
  constructor(
    readonly tag: number,
    readonly value: unknown,
  ) {}
}

export type CborMap = Map<number | string, unknown>;

export function encode(value: unknown): Uint8Array {
  const out: number[] = [];
  write(out, value);
  return Uint8Array.from(out);
}

export function decode(data: Uint8Array): unknown {
  const reader = new Reader(data);
  const value = reader.read();
  if (reader.position !== data.length) {
    throw new Error("Trailing bytes after CBOR item");
  }
  return value;
}

function write(out: number[], value: unknown): void {
  if (value === null || value === undefined) {
    out.push(0xf6);
  } else if (typeof value === "boolean") {
    out.push(value ? 0xf5 : 0xf4);
  } else if (typeof value === "number" || typeof value === "bigint") {
    const n = BigInt(value);
    if (n >= 0n) {
      head(out, 0, n);
    } else {
      head(out, 1, -1n - n);
    }
  } else if (value instanceof Uint8Array) {
    head(out, 2, BigInt(value.length));
    out.push(...value);
  } else if (typeof value === "string") {
    const bytes = utf8(value);
    head(out, 3, BigInt(bytes.length));
    out.push(...bytes);
  } else if (Array.isArray(value)) {
    head(out, 4, BigInt(value.length));
    value.forEach((item) => write(out, item));
  } else if (value instanceof Tagged) {
    head(out, 6, BigInt(value.tag));
    write(out, value.value);
  } else if (value instanceof Map || typeof value === "object") {
    const entries = value instanceof Map ? [...value.entries()] : Object.entries(value as object);
    const pairs = entries.map(([key, item]) => [encode(key), encode(item)] as const);
    pairs.sort((a, b) => compare(a[0], b[0]));
    head(out, 5, BigInt(pairs.length));
    for (const [key, item] of pairs) {
      out.push(...key, ...item);
    }
  } else {
    throw new Error("Cannot encode " + typeof value);
  }
}

function head(out: number[], major: number, argument: bigint): void {
  const kind = major << 5;
  if (argument < 24n) {
    out.push(kind | Number(argument));
    return;
  }
  const length = argument < 0x100n ? 1 : argument < 0x10000n ? 2 : argument < 0x100000000n ? 4 : 8;
  out.push(kind | { 1: 24, 2: 25, 4: 26, 8: 27 }[length]!);
  for (let i = length - 1; i >= 0; i--) {
    out.push(Number((argument >> BigInt(8 * i)) & 0xffn));
  }
}

class Reader {
  position = 0;
  private depth = 0;

  constructor(private readonly data: Uint8Array) {}

  read(): unknown {
    if (++this.depth > 32) {
      throw new Error("CBOR nested too deeply");
    }
    const initial = this.next();
    const major = initial >> 5;
    const info = initial & 0x1f;
    let value: unknown;
    switch (major) {
      case 0:
        value = this.number(this.argument(info));
        break;
      case 1:
        value = this.number(-1n - this.argument(info));
        break;
      case 2:
        value = this.bytes(this.length(info));
        break;
      case 3:
        value = fromUtf8(this.bytes(this.length(info)));
        break;
      case 4: {
        const size = this.length(info);
        const list: unknown[] = [];
        for (let i = 0; i < size; i++) {
          list.push(this.read());
        }
        value = list;
        break;
      }
      case 5: {
        const size = this.length(info);
        const map: CborMap = new Map();
        for (let i = 0; i < size; i++) {
          const key = this.read();
          if ((typeof key !== "number" && typeof key !== "string") || map.has(key)) {
            throw new Error("Duplicate or unsupported CBOR map key");
          }
          map.set(key, this.read());
        }
        value = map;
        break;
      }
      case 6:
        value = new Tagged(this.number(this.argument(info)), this.read());
        break;
      default:
        if (info === 20) {
          value = false;
        } else if (info === 21) {
          value = true;
        } else if (info === 22) {
          value = null;
        } else {
          throw new Error("Unsupported CBOR simple value " + info);
        }
    }
    this.depth--;
    return value;
  }

  private next(): number {
    if (this.position >= this.data.length) {
      throw new Error("Truncated CBOR");
    }
    return this.data[this.position++];
  }

  private argument(info: number): bigint {
    if (info < 24) {
      return BigInt(info);
    }
    const length = ({ 24: 1, 25: 2, 26: 4, 27: 8 } as Record<number, number>)[info];
    if (length === undefined) {
      throw new Error("Indefinite or reserved CBOR length");
    }
    let value = 0n;
    for (let i = 0; i < length; i++) {
      value = (value << 8n) | BigInt(this.next());
    }
    if (value >= 1n << 63n) {
      throw new Error("CBOR integer out of range");
    }
    return value;
  }

  private number(value: bigint): number {
    if (value > BigInt(Number.MAX_SAFE_INTEGER) || value < BigInt(Number.MIN_SAFE_INTEGER)) {
      throw new Error("CBOR integer out of range");
    }
    return Number(value);
  }

  private length(info: number): number {
    const length = this.argument(info);
    if (length > BigInt(this.data.length - this.position)) {
      throw new Error("Truncated CBOR");
    }
    return Number(length);
  }

  private bytes(length: number): Uint8Array {
    const value = this.data.slice(this.position, this.position + length);
    this.position += length;
    return value;
  }
}
