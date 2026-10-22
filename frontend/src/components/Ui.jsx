import Icon from './Icon'

export function formatTnd(value) {
  return formatCurrency(value, 'TND')
}

// Display rounding only: inputs, API payloads and scale-4 calculations stay exact.
export function formatScale4Display(units, currency = 'TND') {
  if (typeof units !== 'bigint') return '—'
  const absolute = units < 0n ? -units : units
  let tenths = absolute / 1000n
  const remainder = absolute % 1000n
  if (remainder > 500n || (remainder === 500n && tenths % 2n !== 0n)) tenths += 1n
  const integer = (tenths / 10n).toLocaleString('fr-FR')
  return `${units < 0n && tenths > 0n ? '−' : ''}${integer},${tenths % 10n} ${currency}`
}

export function formatCurrency(value, currency = 'TND') {
  if (value === null || value === undefined) return '—'
  const match = String(value).match(/^(-?)(\d+)(?:\.(\d{1,4}))?$/)
  if (!match) return '—'
  const units = BigInt(match[2]) * 10000n + BigInt((match[3] || '').padEnd(4, '0'))
  return formatScale4Display(match[1] ? -units : units, currency)
}

export function DemoBanner({ compact = false }) {
  return (
    <div className={`demo-banner${compact ? ' demo-banner--compact' : ''}`} role="status">
      <span className="demo-banner__dot" aria-hidden="true" />
      Démonstration — banque simulée, imports utilisateur
    </div>
  )
}

export function PageHeader({ eyebrow, title, description, action }) {
  return (
    <header className="page-header">
      <div className="page-header__copy">
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="page-header__description">{description}</p>}
      </div>
      {action && <div className="page-header__action">{action}</div>}
    </header>
  )
}

export function Card({ children, className = '', as: Component = 'section', ...props }) {
  return <Component className={`card ${className}`} {...props}>{children}</Component>
}

export function ProgressBar({ value, tone = 'teal', label }) {
  const safeValue = Math.min(100, Math.max(0, value))

  return (
    <div
      className="progress"
      role="progressbar"
      aria-label={label}
      aria-valuemin="0"
      aria-valuemax="100"
      aria-valuenow={Math.round(safeValue)}
    >
      <span className={`progress__fill progress__fill--${tone}`} style={{ width: `${safeValue}%` }} />
    </div>
  )
}

export function Badge({ children, tone = 'neutral' }) {
  return <span className={`badge badge--${tone}`}>{children}</span>
}

export function MetricCard({ icon, label, value, note, tone = 'teal' }) {
  return (
    <Card className={`metric-card metric-card--${tone}`}>
      <span className="metric-card__icon"><Icon name={icon} /></span>
      <div>
        <p className="metric-card__label">{label}</p>
        <p className="metric-card__value">{value}</p>
        <p className="metric-card__note">{note}</p>
      </div>
    </Card>
  )
}

export function InlineNotice({ children, tone = 'info', icon = 'info', live = false }) {
  const role = live ? (tone === 'warning' || tone === 'error' ? 'alert' : 'status') : undefined

  return (
    <div className={`inline-notice inline-notice--${tone}`} role={role}>
      <Icon name={icon} size={20} />
      <div>{children}</div>
    </div>
  )
}
