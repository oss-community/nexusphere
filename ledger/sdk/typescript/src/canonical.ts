import { toHex, utf8 } from "./bytes.js";

const ESCAPES: Record<string, string> = {
  '"': '\\"',
  "\\": "\\\\",
  "\b": "\\b",
  "\f": "\\f",
  "\n": "\\n",
  "\r": "\\r",
  "\t": "\\t",
};

export function canonicalJson(value: unknown): string {
  const out: string[] = [];
  write(out, value);
  return out.join("");
}

export function canonicalBytes(value: unknown): Uint8Array {
  return utf8(canonicalJson(value));
}

export async function sha256(data: Uint8Array | string): Promise<Uint8Array> {
  const bytes = typeof data === "string" ? utf8(data) : data;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes as BufferSource));
}

export async function sha256Hex(data: Uint8Array | string): Promise<string> {
  return toHex(await sha256(data));
}

function write(out: string[], value: unknown): void {
  if (value === null || value === undefined) {
    out.push("null");
  } else if (typeof value === "boolean") {
    out.push(value ? "true" : "false");
  } else if (typeof value === "number" || typeof value === "bigint") {
    if (typeof value === "number" && !Number.isSafeInteger(value)) {
      throw new Error("Canonical JSON numbers are integers");
    }
    out.push(String(value));
  } else if (typeof value === "string") {
    writeString(out, value);
  } else if (Array.isArray(value)) {
    out.push("[");
    value.forEach((item, index) => {
      if (index) {
        out.push(",");
      }
      write(out, item);
    });
    out.push("]");
  } else if (typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    out.push("{");
    entries.forEach(([key, item], index) => {
      if (index) {
        out.push(",");
      }
      writeString(out, key);
      out.push(":");
      write(out, item);
    });
    out.push("}");
  } else {
    throw new Error("Unsupported canonical JSON value: " + typeof value);
  }
}

function writeString(out: string[], text: string): void {
  out.push('"');
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    const escaped = ESCAPES[c];
    if (escaped !== undefined) {
      out.push(escaped);
    } else if (c.charCodeAt(0) < 0x20) {
      out.push("\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
    } else {
      out.push(c);
    }
  }
  out.push('"');
}
