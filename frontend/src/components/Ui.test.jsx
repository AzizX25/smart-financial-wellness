import { describe, expect, it } from 'vitest'
import { formatCurrency, formatScale4Display, formatTnd } from './Ui'

describe('one-decimal monetary display', () => {
  it('formats every currency with one decimal without converting currencies', () => {
    expect(formatCurrency('1234.5678', 'TND')).toBe('1\u202f234,6 TND')
    expect(formatCurrency('12.5000', 'EUR')).toBe('12,5 EUR')
    expect(formatTnd(12)).toBe('12,0 TND')
  })

  it('rounds display ties half-even without altering scale-4 values', () => {
    const units = 122500n
    expect(formatScale4Display(units, 'TND')).toBe('12,2 TND')
    expect(formatScale4Display(123500n, 'TND')).toBe('12,4 TND')
    expect(formatScale4Display(-123500n, 'TND')).toBe('−12,4 TND')
    expect(units).toBe(122500n)
    expect(formatCurrency('999999999999999.9500', 'TND')).toBe('1\u202f000\u202f000\u202f000\u202f000\u202f000,0 TND')
  })

  it('does not display invalid money as an authoritative zero', () => {
    for (const value of [null, undefined, 'NaN', 'invalid', Infinity]) {
      expect(formatCurrency(value)).toBe('—')
    }
  })
})
