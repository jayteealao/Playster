export function parseClockDuration(value: string): number | null {
  const rawParts = value
    .trim()
    .split(":")
    .map((part) => part.trim());
  if (rawParts.length !== 2 && rawParts.length !== 3) return null;
  if (rawParts.some((part) => !/^\d+$/.test(part))) return null;
  const parts = rawParts.map((part) => Number.parseInt(part, 10));
  if (parts.some((part) => !Number.isFinite(part))) return null;
  if (parts.length === 2) {
    const [minutes, seconds] = parts;
    if (seconds >= 60) return null;
    return minutes * 60 + seconds;
  }
  const [hours, minutes, seconds] = parts;
  if (minutes >= 60 || seconds >= 60) return null;
  return hours * 3600 + minutes * 60 + seconds;
}

export function parseIsoDuration(value: string): number | null {
  const match = value
    .trim()
    .match(/^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i);
  if (!match) return null;
  const days = Number.parseInt(match[1] ?? "0", 10);
  const hours = Number.parseInt(match[2] ?? "0", 10);
  const minutes = Number.parseInt(match[3] ?? "0", 10);
  const seconds = Number.parseFloat(match[4] ?? "0");
  if (![days, hours, minutes, seconds].every((part) => Number.isFinite(part))) return null;
  const hasAnyComponent = Boolean(match[1] || match[2] || match[3] || match[4]);
  if (!hasAnyComponent) return null;
  const total = days * 86_400 + hours * 3600 + minutes * 60 + seconds;
  return total > 0 ? Math.max(1, Math.round(total)) : null;
}

export function resolveMediaDurationSecondsFromData({
  metaDuration,
  uiDuration,
  videoDuration,
}: {
  metaDuration?: string | null;
  uiDuration?: string | null;
  videoDuration?: number | null;
}): number | null {
  if (metaDuration) {
    const parsed = parseIsoDuration(metaDuration);
    if (parsed) return parsed;
  }

  if (uiDuration) {
    const parsed = parseClockDuration(uiDuration);
    if (parsed) return parsed;
  }

  if (typeof videoDuration === "number" && Number.isFinite(videoDuration) && videoDuration > 0) {
    return Math.round(videoDuration);
  }

  return null;
}
