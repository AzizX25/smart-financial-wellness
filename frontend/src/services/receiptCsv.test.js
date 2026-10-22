import { describe, expect, it } from 'vitest'
import { createReceiptCsv, receiptValidation } from './receiptCsv'

const fields = { merchant: 'Café "Demo", Tunis', date: '2026-10-01', amount: '12,3456', currency: 'TND' }

describe('CSV de ticket vérifié', () => {
  it('exporte les colonnes exactes et un débit scale-4 sans arrondi ni conversion', () => {
    expect(createReceiptCsv(fields, '2026-10-11')).toBe('date,description,amount,direction,currency\n2026-10-01,"Café ""Demo"", Tunis",12.3456,DEBIT,TND\n')
    expect(createReceiptCsv({ ...fields, amount: '12', currency: 'EUR' }, '2026-10-11')).toContain('12.0000,DEBIT,EUR')
  })

  it('refuse la précision excessive, une devise absente, une date invalide ou future et les formules', () => {
    for (const patch of [{ amount: '1.12345' }, { amount: '-1' }, { amount: '0' }, { currency: '' },
      { date: '2026-02-30' }, { date: '2026-10-12' }, { merchant: '=HYPERLINK("unsafe")' }, { merchant: 'Demo\nmerchant' }]) {
      expect(receiptValidation({ ...fields, ...patch }, '2026-10-11')).not.toBe('')
      expect(() => createReceiptCsv({ ...fields, ...patch }, '2026-10-11')).toThrow()
    }
  })

  it('exports the human-corrected French receipt date and preserves three decimal TND precision at scale four', () => {
    const receipt = { merchant: 'Lefties', date: '2026-09-11', amount: '309.700', currency: 'TND' }
    expect(createReceiptCsv(receipt, '2026-10-14')).toBe('date,description,amount,direction,currency\n2026-09-11,"Lefties",309.7000,DEBIT,TND\n')
    for (const date of ['', '2026-11-09']) expect(() => createReceiptCsv({ ...receipt, date }, '2026-10-14')).toThrow()
  })
})
