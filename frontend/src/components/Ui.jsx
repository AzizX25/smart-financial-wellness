import Icon from './Icon'

export function formatTnd(value) {
  return new Intl.NumberFormat('fr-TN', {
    style: 'currency',
    currency: 'TND',
    minimumFractionDigits: 3,
  }).format(value)
}

export function formatCurrency(value, currency = 'TND') {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '-'
  }
  try {
    return new Intl.NumberFormat('fr-TN', {
      style: 'currency',
      currency,
      minimumFractionDigits: currency === 'TND' ? 3 : 2,
    }).format(Number(value))
  } catch {
    return `${value} ${currency || ''}`.trim()
  }
}

export function DemoBanner({ compact = false }) {
  return (
    <div className={`demo-banner${compact ? ' demo-banner--compact' : ''}`} role="status">
      <span className="demo-banner__dot" aria-hidden="true" />
      Prototype de démonstration — données simulées
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
