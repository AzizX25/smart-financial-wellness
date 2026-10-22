// Formatting and review validation only: no authoritative financial calculations.
export function receiptFieldWarnings(fields, asOfDate) {
  const warnings = {}
  const merchant = fields.merchant.trim()
  if (!merchant || merchant.length > 160 || [...merchant].some((character) => character.charCodeAt(0) < 32)
      || /^[=+@-]/.test(merchant)) {
    warnings.merchant = 'Vérifiez le commerçant : texte simple, sans formule ni caractère de contrôle.'
  }
  const parsedDate = new Date(`${fields.date}T00:00:00Z`)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fields.date) || Number.isNaN(parsedDate.getTime())
      || parsedDate.toISOString().slice(0, 10) !== fields.date || fields.date < '1900-01-01'
      || !asOfDate || fields.date > asOfDate) warnings.date = 'Indiquez une date valide, pas après la date du jour.'
  const amount = fields.amount.trim().replace(',', '.')
  if (!/^\d{1,15}(?:\.\d{1,4})?$/.test(amount) || BigInt(amount.replace('.', '')) <= 0n) {
    warnings.amount = 'Indiquez un montant strictement positif avec au maximum quatre décimales.'
  }
  if (!/^[A-Z]{3}$/.test(fields.currency)) warnings.currency = 'Confirmez la devise : trois lettres, par exemple TND. Aucune conversion n’est effectuée.'
  return warnings
}

export function receiptValidation(fields, asOfDate) {
  return Object.values(receiptFieldWarnings(fields, asOfDate))[0] || ''
}

export function createReceiptCsv(fields, asOfDate) {
  const error = receiptValidation(fields, asOfDate)
  if (error) throw new Error(error)
  const [integer, fraction = ''] = fields.amount.trim().replace(',', '.').split('.')
  const quote = (value) => `"${value.replaceAll('"', '""')}"`
  return `date,description,amount,direction,currency\n${fields.date},${quote(fields.merchant.trim())},${integer}.${fraction.padEnd(4, '0')},DEBIT,${fields.currency}\n`
}
