export function Loading() {
  return <p className="notice" role="status">Chargement…</p>
}

export function ErrorMessage({ message }) {
  return message ? <p className="notice error" role="alert">{message}</p> : null
}

export function SuccessMessage({ message }) {
  return message ? <p className="notice success" role="status">{message}</p> : null
}

export function Empty({ children = 'Aucune donnée disponible.' }) {
  return <p className="empty">{children}</p>
}

export function Pagination({ page, totalPages, onPage }) {
  if (totalPages <= 1) return null
  return (
    <div className="pagination">
      <button className="button secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Précédent
      </button>
      <span>Page {page + 1} sur {totalPages}</span>
      <button className="button secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
        Suivant
      </button>
    </div>
  )
}

export function StatusBadge({ status }) {
  return <span className={`status status-${status?.toLowerCase()}`}>{status}</span>
}
