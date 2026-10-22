export function formatMoney(value, currency = 'TND') {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency }).format(Number(value || 0))
}

export function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-FR').format(new Date(value))
}
