import { concat, equal, fromBase64, toBase64, toHex, utf8 } from "./bytes.js";
import { sha256 } from "./canonical.js";
import { PrivateKey, PublicKey } from "./keys.js";

export const ED25519 = 0x01;
export const COSIGNATURE = 0x04;
export const SIGNATURE_PREFIX = "— ";
const COSIGNATURE_HEADER = "cosignature/v1\ntime ";

export class NoteKey {
  private constructor(
    readonly name: string,
    readonly type: number,
    readonly publicKey: PublicKey,
    readonly hash: Uint8Array,
  ) {}

  static async of(name: string, type: number, publicKey: PublicKey): Promise<NoteKey> {
    if (!name || name.includes("+") || /\s/.test(name)) {
      throw new Error("A note key name must be non-empty without spaces or '+'");
    }
    if (type !== ED25519 && type !== COSIGNATURE) {
      throw new Error("Unsupported note key type " + type);
    }
    const hash = (await sha256(concat(utf8(name + "\n"), Uint8Array.of(type), publicKey.raw))).slice(0, 4);
    return new NoteKey(name, type, publicKey, hash);
  }

  static async parse(vkey: string): Promise<NoteKey> {
    const text = vkey.trim();
    const first = text.indexOf("+");
    const second = first < 0 ? -1 : text.indexOf("+", first + 1);
    if (second < 0) {
      throw new Error("A verifier key has the form name+hash+key");
    }
    const key = fromBase64(text.substring(second + 1));
    if (key.length !== 33) {
      throw new Error("A verifier key holds a type byte and a 32-byte Ed25519 key");
    }
    const parsed = await NoteKey.of(text.substring(0, first), key[0], await PublicKey.fromRaw(key.slice(1)));
    if (parsed.hashHex !== text.substring(first + 1, second)) {
      throw new Error("The verifier key hash does not match its key");
    }
    return parsed;
  }

  get hashHex(): string {
    return toHex(this.hash);
  }

  get vkey(): string {
    return this.name + "+" + this.hashHex + "+" + toBase64(concat(Uint8Array.of(this.type), this.publicKey.raw));
  }
}

export class NoteSignature {
  constructor(
    readonly name: string,
    readonly keyHash: Uint8Array,
    readonly signature: Uint8Array,
  ) {}

  line(): string {
    return SIGNATURE_PREFIX + this.name + " " + toBase64(concat(this.keyHash, this.signature)) + "\n";
  }

  static parse(line: string): NoteSignature {
    const body = line.endsWith("\n") ? line.substring(0, line.length - 1) : line;
    if (!body.startsWith(SIGNATURE_PREFIX)) {
      throw new Error("A signature line starts with an em dash and a space");
    }
    const parts = body.substring(SIGNATURE_PREFIX.length).split(" ");
    if (parts.length !== 2) {
      throw new Error("A signature line has a name and a signature");
    }
    const value = fromBase64(parts[1]);
    if (value.length < 5) {
      throw new Error("A signature is too short");
    }
    return new NoteSignature(parts[0], value.slice(0, 4), value.slice(4));
  }
}

export class LogCheckpoint {
  constructor(
    readonly origin: string,
    readonly size: number,
    readonly root: Uint8Array,
  ) {
    if (!origin || !origin.trim() || origin.includes("\n")) {
      throw new Error("A log origin is one non-empty line");
    }
    if (!Number.isSafeInteger(size) || size < 0) {
      throw new Error("A tree size is not negative");
    }
    if (!root || root.length !== 32) {
      throw new Error("A root hash has 32 bytes");
    }
  }

  body(): string {
    return `${this.origin}\n${this.size}\n${toBase64(this.root)}\n`;
  }

  async sign(key: NoteKey, privateKey: PrivateKey): Promise<Note> {
    if (key.type !== ED25519) {
      throw new Error("A checkpoint is signed with an Ed25519 note key");
    }
    const body = this.body();
    return new Note(this, body, [new NoteSignature(key.name, key.hash, await privateKey.sign(utf8(body)))]);
  }
}

export class Note {
  constructor(
    readonly checkpoint: LogCheckpoint,
    readonly body: string,
    readonly signatures: NoteSignature[],
  ) {}

  static parse(text: string): Note {
    const split = text.indexOf("\n\n");
    if (split < 0) {
      throw new Error("A note has a body, a blank line and signatures");
    }
    const body = text.substring(0, split + 1);
    const lines = body.split("\n");
    if (lines.length < 4) {
      throw new Error("A checkpoint has an origin, a size and a root hash");
    }
    if (!/^\d+$/.test(lines[1])) {
      throw new Error("A tree size is a number");
    }
    const checkpoint = new LogCheckpoint(lines[0], Number(lines[1]), fromBase64(lines[2]));
    if (checkpoint.body() !== body) {
      throw new Error("Checkpoint extension lines are not supported");
    }
    const signatures = text
      .substring(split + 2)
      .split("\n")
      .filter((line) => line.length > 0)
      .map((line) => NoteSignature.parse(line));
    if (signatures.length === 0) {
      throw new Error("A note has at least one signature");
    }
    return new Note(checkpoint, body, signatures);
  }

  text(): string {
    return this.body + "\n" + this.signatures.map((signature) => signature.line()).join("");
  }

  withSignature(signature: NoteSignature): Note {
    return new Note(this.checkpoint, this.body, [...this.signatures, signature]);
  }

  async signedBy(key: NoteKey): Promise<boolean> {
    for (const signature of this.signatures) {
      if (
        key.type === ED25519 &&
        key.name === signature.name &&
        equal(key.hash, signature.keyHash) &&
        (await key.publicKey.verify(utf8(this.body), signature.signature))
      ) {
        return true;
      }
    }
    return false;
  }

  async cosignedBy(key: NoteKey): Promise<number | null> {
    for (const signature of this.signatures) {
      if (
        key.type !== COSIGNATURE ||
        key.name !== signature.name ||
        !equal(key.hash, signature.keyHash) ||
        signature.signature.length !== 72
      ) {
        continue;
      }
      const time = new DataView(signature.signature.buffer, signature.signature.byteOffset, 8).getBigUint64(0);
      if (await key.publicKey.verify(cosignedBytes(time, this.body), signature.signature.slice(8))) {
        return Number(time);
      }
    }
    return null;
  }
}

export async function cosign(body: string, key: NoteKey, privateKey: PrivateKey, time: number): Promise<NoteSignature> {
  if (key.type !== COSIGNATURE) {
    throw new Error("A cosignature uses a cosignature key");
  }
  const prefix = new Uint8Array(8);
  new DataView(prefix.buffer).setBigUint64(0, BigInt(time));
  return new NoteSignature(key.name, key.hash, concat(prefix, await privateKey.sign(cosignedBytes(BigInt(time), body))));
}

function cosignedBytes(time: bigint, body: string): Uint8Array {
  return utf8(COSIGNATURE_HEADER + time.toString() + "\n" + body);
}
