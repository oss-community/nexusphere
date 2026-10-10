import { fromBase64, toBase64, toBase64Url, utf8 } from "./bytes.js";
import { sha256 } from "./canonical.js";
import { PrivateKey, PublicKey } from "./keys.js";

export const HTTP_SIGNATURE_LABEL = "nexusphere";
export const HTTP_SIGNATURE_TAG = "nexusphere-ledger";
export const HTTP_SIGNATURE_ALGORITHM = "ed25519";
export const HTTP_SIGNATURE_VALIDITY = 300;

export class InvalidHttpSignature extends Error {}

export interface VerifiedRequest {
  keyId: string;
  created: number;
  nonce: string | null;
}

type Header = (name: string) => string | null | undefined;

export async function contentDigest(body: Uint8Array): Promise<string> {
  return "sha-256=:" + toBase64(await sha256(body)) + ":";
}

export function authority(url: string): string {
  const parsed = new URL(url);
  return parsed.host.toLowerCase();
}

function components(body: Uint8Array | null | undefined): string[] {
  return !body || body.length === 0
    ? ["@method", "@authority", "@path"]
    : ["@method", "@authority", "@path", "content-digest"];
}

function value(component: string, method: string, url: string, header: Header): string {
  switch (component) {
    case "@method":
      return method.toUpperCase();
    case "@authority":
      return authority(url);
    case "@path":
      return new URL(url).pathname || "/";
    case "@query":
      return "?" + new URL(url).search.replace(/^\?/, "");
  }
  if (component.startsWith("@")) {
    throw new InvalidHttpSignature(`The component ${component} is not supported`);
  }
  const found = header(component);
  if (found === null || found === undefined) {
    throw new InvalidHttpSignature(`The request has no ${component} header`);
  }
  return found.trim();
}

export function signatureBase(method: string, url: string, covered: string[], header: Header, params: string): string {
  const lines = covered.map((c) => `"${c}": ${value(c, method, url, header)}`);
  return [...lines, `"@signature-params": ${params}`].join("\n");
}

export async function signRequest(
  method: string,
  url: string,
  body: Uint8Array | null,
  keyId: string,
  key: PrivateKey,
  created: number = Math.floor(Date.now() / 1000),
  nonce: string = toBase64Url(crypto.getRandomValues(new Uint8Array(16))),
): Promise<Record<string, string>> {
  const covered = components(body);
  const headers: Record<string, string> = {};
  if (covered.includes("content-digest")) {
    headers["Content-Digest"] = await contentDigest(body as Uint8Array);
  }
  const params = `(${covered.map((c) => `"${c}"`).join(" ")});created=${created};expires=${
    created + HTTP_SIGNATURE_VALIDITY};nonce="${nonce}";keyid="${keyId}";alg="${HTTP_SIGNATURE_ALGORITHM}";tag="${
    HTTP_SIGNATURE_TAG}"`;
  const digest = headers["Content-Digest"];
  const base = signatureBase(method, url, covered, (n) => (n.toLowerCase() === "content-digest" ? digest : null), params);
  headers["Signature-Input"] = `${HTTP_SIGNATURE_LABEL}=${params}`;
  headers["Signature"] = `${HTTP_SIGNATURE_LABEL}=:${toBase64(await key.sign(utf8(base)))}:`;
  return headers;
}

export async function verifyRequest(
  method: string,
  url: string,
  headers: Record<string, string> | Headers,
  body: Uint8Array | null,
  keys: (keyId: string) => Promise<PublicKey | null> | PublicKey | null,
  now: number = Date.now() / 1000,
  clockSkew = 60,
): Promise<VerifiedRequest> {
  const header: Header = headers instanceof Headers
    ? (name) => headers.get(name)
    : (name) => {
        const key = Object.keys(headers).find((k) => k.toLowerCase() === name.toLowerCase());
        return key === undefined ? null : headers[key];
      };
  const input = member(header("signature-input"), `The request has no Signature-Input ${HTTP_SIGNATURE_LABEL}`);
  const signature = member(header("signature"), `The request has no Signature ${HTTP_SIGNATURE_LABEL}`);
  const { covered, params } = parse(input);
  const required = components(body);
  if (!required.every((c) => covered.includes(c))) {
    throw new InvalidHttpSignature("The signature must cover " + required.join(", "));
  }
  if (params.alg !== undefined && text(params.alg) !== HTTP_SIGNATURE_ALGORITHM) {
    throw new InvalidHttpSignature("The signature algorithm must be " + HTTP_SIGNATURE_ALGORITHM);
  }
  if (text(params.tag) !== HTTP_SIGNATURE_TAG) {
    throw new InvalidHttpSignature("The signature tag must be " + HTTP_SIGNATURE_TAG);
  }
  const created = number(params.created);
  const expires = number(params.expires);
  if (created === null || expires === null) {
    throw new InvalidHttpSignature("The signature needs created and expires");
  }
  if (created > now + clockSkew || expires < now - clockSkew || expires - created > HTTP_SIGNATURE_VALIDITY) {
    throw new InvalidHttpSignature(`The signature was made at ${created} and is not valid now`);
  }
  const keyId = text(params.keyid);
  if (keyId === null) {
    throw new InvalidHttpSignature("The signature has no keyid");
  }
  if (covered.includes("content-digest")) {
    const digest = header("content-digest");
    if (!digest || digest.trim() !== (await contentDigest(body ?? new Uint8Array()))) {
      throw new InvalidHttpSignature("The Content-Digest does not match the body");
    }
  }
  const key = await keys(keyId);
  if (!key) {
    throw new InvalidHttpSignature(`The key ${keyId} is unknown`);
  }
  if (signature.length < 2 || !signature.startsWith(":") || !signature.endsWith(":")) {
    throw new InvalidHttpSignature("The signature is not a byte sequence");
  }
  let raw: Uint8Array;
  try {
    raw = fromBase64(signature.substring(1, signature.length - 1));
  } catch {
    throw new InvalidHttpSignature("The signature is not base64");
  }
  if (!(await key.verify(utf8(signatureBase(method, url, covered, header, input)), raw))) {
    throw new InvalidHttpSignature("The signature does not match the request");
  }
  return { keyId, created, nonce: text(params.nonce) };
}

function member(dictionary: string | null | undefined, missing: string): string {
  if (dictionary === null || dictionary === undefined) {
    throw new InvalidHttpSignature(missing);
  }
  for (const part of split(dictionary)) {
    const equals = part.indexOf("=");
    if (equals > 0 && part.substring(0, equals).trim() === HTTP_SIGNATURE_LABEL) {
      return part.substring(equals + 1).trim();
    }
  }
  throw new InvalidHttpSignature(missing);
}

function split(dictionary: string): string[] {
  const members: string[] = [];
  let depth = 0;
  let quoted = false;
  let start = 0;
  for (let i = 0; i < dictionary.length; i++) {
    const c = dictionary[i];
    if (c === '"') {
      quoted = !quoted;
    } else if (!quoted && c === "(") {
      depth++;
    } else if (!quoted && c === ")") {
      depth--;
    } else if (!quoted && depth === 0 && c === ",") {
      members.push(dictionary.substring(start, i));
      start = i + 1;
    }
  }
  members.push(dictionary.substring(start));
  return members;
}

function parse(input: string): { covered: string[]; params: Record<string, string> } {
  const close = input.indexOf(")");
  if (!input.startsWith("(") || close < 0) {
    throw new InvalidHttpSignature("The signature input is not an inner list");
  }
  const covered = input.substring(1, close).trim().split(/ +/).filter((item) => item !== "").map((item) => {
    if (item.length < 2 || !item.startsWith('"') || !item.endsWith('"')) {
      throw new InvalidHttpSignature("A covered component is not a string");
    }
    return item.substring(1, item.length - 1);
  });
  const params: Record<string, string> = {};
  for (const param of input.substring(close + 1).split(";")) {
    if (!param.trim()) {
      continue;
    }
    const equals = param.indexOf("=");
    if (equals < 0) {
      throw new InvalidHttpSignature("A signature parameter has no value");
    }
    params[param.substring(0, equals).trim()] = param.substring(equals + 1).trim();
  }
  return { covered, params };
}

function text(value: string | undefined): string | null {
  return value === undefined || value.length < 2 || !value.startsWith('"') || !value.endsWith('"')
    ? null
    : value.substring(1, value.length - 1);
}

function number(value: string | undefined): number | null {
  return value !== undefined && /^-?\d+$/.test(value) ? Number(value) : null;
}
