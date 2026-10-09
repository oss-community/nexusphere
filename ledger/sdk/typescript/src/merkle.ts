import { concat, equal } from "./bytes.js";
import { sha256 } from "./canonical.js";

export function leafHash(data: Uint8Array): Promise<Uint8Array> {
  return sha256(concat(Uint8Array.of(0), data));
}

export function nodeHash(left: Uint8Array, right: Uint8Array): Promise<Uint8Array> {
  return sha256(concat(Uint8Array.of(1), left, right));
}

export function emptyRoot(): Promise<Uint8Array> {
  return sha256(new Uint8Array(0));
}

export function root(leaves: Uint8Array[]): Promise<Uint8Array> {
  return leaves.length === 0 ? emptyRoot() : hash(leaves, 0, leaves.length);
}

export async function inclusionProof(leaves: Uint8Array[], index: number, size = leaves.length): Promise<Uint8Array[]> {
  if (index < 0 || index >= size || size > leaves.length) {
    throw new Error("index must be below the tree size");
  }
  const proof: Uint8Array[] = [];
  await path(leaves, index, 0, size, proof);
  return proof;
}

export async function consistencyProof(leaves: Uint8Array[], first: number, second = leaves.length): Promise<Uint8Array[]> {
  if (first < 0 || first > second || second > leaves.length) {
    throw new Error("the first size must be between 0 and the second size");
  }
  const proof: Uint8Array[] = [];
  if (first > 0 && first < second) {
    await subproof(leaves, first, 0, second, true, proof);
  }
  return proof;
}

export async function rootFromInclusion(
  leaf: Uint8Array,
  index: number,
  size: number,
  proof: Uint8Array[],
): Promise<Uint8Array | null> {
  if (index < 0 || index >= size) {
    return null;
  }
  let fn = BigInt(index);
  let sn = BigInt(size - 1);
  let r = leaf;
  for (const p of proof) {
    if (sn === 0n) {
      return null;
    }
    if ((fn & 1n) === 1n || fn === sn) {
      r = await nodeHash(p, r);
      if ((fn & 1n) === 0n) {
        while ((fn & 1n) === 0n && fn !== 0n) {
          fn >>= 1n;
          sn >>= 1n;
        }
      }
    } else {
      r = await nodeHash(r, p);
    }
    fn >>= 1n;
    sn >>= 1n;
  }
  return sn === 0n ? r : null;
}

export async function verifyInclusion(
  leaf: Uint8Array,
  index: number,
  size: number,
  proof: Uint8Array[],
  expected: Uint8Array,
): Promise<boolean> {
  return equal(await rootFromInclusion(leaf, index, size, proof), expected);
}

export async function verifyConsistency(
  first: number,
  second: number,
  proof: Uint8Array[],
  firstRoot: Uint8Array,
  secondRoot: Uint8Array,
): Promise<boolean> {
  if (first < 0 || first > second) {
    return false;
  }
  if (first === second) {
    return proof.length === 0 && equal(firstRoot, secondRoot);
  }
  if (first === 0) {
    return proof.length === 0;
  }
  const nodes = [...proof];
  if ((BigInt(first) & BigInt(first - 1)) === 0n) {
    nodes.unshift(firstRoot);
  }
  if (nodes.length === 0) {
    return false;
  }
  let fn = BigInt(first - 1);
  let sn = BigInt(second - 1);
  while ((fn & 1n) === 1n) {
    fn >>= 1n;
    sn >>= 1n;
  }
  let fr = nodes[0];
  let sr = nodes[0];
  for (const c of nodes.slice(1)) {
    if (sn === 0n) {
      return false;
    }
    if ((fn & 1n) === 1n || fn === sn) {
      fr = await nodeHash(c, fr);
      sr = await nodeHash(c, sr);
      while ((fn & 1n) === 0n && fn !== 0n) {
        fn >>= 1n;
        sn >>= 1n;
      }
    } else {
      sr = await nodeHash(sr, c);
    }
    fn >>= 1n;
    sn >>= 1n;
  }
  return sn === 0n && equal(fr, firstRoot) && equal(sr, secondRoot);
}

function split(size: number): number {
  let k = 1;
  while (k * 2 < size) {
    k *= 2;
  }
  return k;
}

async function hash(leaves: Uint8Array[], start: number, size: number): Promise<Uint8Array> {
  if (size === 1) {
    return leaves[start];
  }
  const k = split(size);
  return nodeHash(await hash(leaves, start, k), await hash(leaves, start + k, size - k));
}

async function path(leaves: Uint8Array[], index: number, start: number, size: number, proof: Uint8Array[]) {
  if (size === 1) {
    return;
  }
  const k = split(size);
  if (index < k) {
    await path(leaves, index, start, k, proof);
    proof.push(await hash(leaves, start + k, size - k));
  } else {
    await path(leaves, index - k, start + k, size - k, proof);
    proof.push(await hash(leaves, start, k));
  }
}

async function subproof(
  leaves: Uint8Array[],
  first: number,
  start: number,
  size: number,
  complete: boolean,
  proof: Uint8Array[],
) {
  if (first === size) {
    if (!complete) {
      proof.push(await hash(leaves, start, size));
    }
    return;
  }
  const k = split(size);
  if (first <= k) {
    await subproof(leaves, first, start, k, complete, proof);
    proof.push(await hash(leaves, start + k, size - k));
  } else {
    await subproof(leaves, first - k, start + k, size - k, false, proof);
    proof.push(await hash(leaves, start, k));
  }
}
