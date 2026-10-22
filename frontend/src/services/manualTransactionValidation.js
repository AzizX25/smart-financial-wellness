export function positiveDecimal(value) {
  const normalized = value.trim().replace(',', '.')
  if (!/^\d{1,15}(?:\.\d{1,4})?$/.test(normalized)) return null
  const [whole, fraction = ''] = normalized.split('.')
  if (BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0')) <= 0n) return null
  return normalized
}
