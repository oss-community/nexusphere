const INSTANT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

export function parseMicros(text: string): bigint {
  const match = typeof text === "string" ? INSTANT.exec(text.trim()) : null;
  if (!match) {
    throw new Error("Not an ISO-8601 instant: " + JSON.stringify(text));
  }
  const [, year, month, day, hour, minute, second, fraction, zone] = match;
  let millis = Date.UTC(+year, +month - 1, +day, +hour, +minute, +second);
  if (zone !== "Z") {
    const sign = zone[0] === "+" ? 1 : -1;
    millis -= sign * (+zone.substring(1, 3) * 60 + +zone.substring(4, 6)) * 60_000;
  }
  const micros = BigInt((fraction ?? "0").substring(0, 6).padEnd(6, "0"));
  return BigInt(millis) * 1000n + micros;
}

export function formatMicros(micros: bigint): string {
  let seconds = micros / 1_000_000n;
  let rest = micros % 1_000_000n;
  if (rest < 0n) {
    rest += 1_000_000n;
    seconds -= 1n;
  }
  const iso = new Date(Number(seconds) * 1000).toISOString();
  return iso.substring(0, 19) + "." + rest.toString().padStart(6, "0") + "Z";
}

export function formatInstant(text: string | Date): string {
  return formatMicros(text instanceof Date ? BigInt(text.getTime()) * 1000n : parseMicros(text));
}

export function epochText(seconds: number): string {
  return new Date(seconds * 1000).toISOString().substring(0, 19) + "Z";
}
