export default function ForecastChart({ values, labels, compact = false }) {
  const width = 680
  const height = compact ? 180 : 250
  const padding = compact ? 18 : 28
  const max = Math.max(...values) * 1.08
  const min = Math.min(...values) * 0.78
  const points = values.map((value, index) => {
    const x = padding + (index * (width - padding * 2)) / (values.length - 1)
    const y = padding + ((max - value) * (height - padding * 2)) / (max - min)
    return { x, y, value, label: labels[index] }
  })
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ')
  const area = `${path} L ${points.at(-1).x} ${height - padding} L ${points[0].x} ${height - padding} Z`

  return (
    <div className="forecast-chart">
      <svg
        role="img"
        aria-labelledby="forecast-title forecast-description"
        viewBox={`0 0 ${width} ${height}`}
      >
        <title id="forecast-title">Évolution prévisionnelle du solde</title>
        <desc id="forecast-description">
          Le solde estimé diminue progressivement pour atteindre {values.at(-1)} dinars en fin de mois.
        </desc>
        <defs>
          <linearGradient id="forecastArea" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--color-proxym-blue)" stopOpacity="0.28" />
            <stop offset="100%" stopColor="var(--color-proxym-blue)" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        {[0.25, 0.5, 0.75].map((ratio) => (
          <line
            key={ratio}
            x1={padding}
            x2={width - padding}
            y1={height * ratio}
            y2={height * ratio}
            stroke="var(--color-border)"
            strokeDasharray="5 7"
          />
        ))}
        <path d={area} fill="url(#forecastArea)" />
        <path d={path} fill="none" stroke="var(--color-primary)" strokeWidth="5" strokeLinecap="round" />
        {points.map((point) => (
          <g key={point.label}>
            <circle cx={point.x} cy={point.y} r="5" fill="var(--color-surface)" stroke="var(--color-primary)" strokeWidth="3" />
            {!compact && (
              <text x={point.x} y={height - 4} textAnchor="middle" className="chart-label">
                {point.label}
              </text>
            )}
          </g>
        ))}
      </svg>
    </div>
  )
}
