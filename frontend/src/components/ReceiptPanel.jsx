import { useEffect, useId, useRef, useState } from 'react'
import { extractReceipt } from '../services/receiptApi'
import { createReceiptCsv, receiptFieldWarnings, receiptValidation } from '../services/receiptCsv'
import { InlineNotice } from './Ui'

const empty = { merchant: '', date: '', amount: '', currency: '' }

export default function ReceiptPanel({ expanded = false }) {
  const panel = useRef(null)
  useEffect(() => { if (expanded && panel.current) panel.current.open = true }, [expanded])
  const [file, setFile] = useState(null)
  const [imageUrl, setImageUrl] = useState('')
  const [preview, setPreview] = useState(null)
  const [fields, setFields] = useState(empty)
  const [checked, setChecked] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const sequence = useRef(0)
  const fieldId = useId()
  const warnings = preview ? receiptFieldWarnings(fields, preview.asOfDate) : {}
  useEffect(() => () => { sequence.current += 1 }, [])
  useEffect(() => {
    if (!file || !['image/png', 'image/jpeg'].includes(file.type) || !file.size || file.size > 2 * 1024 * 1024) {
      setImageUrl('')
      return
    }
    const url = URL.createObjectURL(file)
    setImageUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [file])

  function changeFile(event) {
    sequence.current += 1
    setFile(event.target.files?.[0] || null)
    setPreview(null)
    setFields(empty)
    setChecked(false)
    setBusy(false)
    setError('')
    setNotice('')
  }

  async function read(event) {
    event.preventDefault()
    if (busy) return
    if (!file || !['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 2 * 1024 * 1024) {
      setError('Sélectionnez une image JPEG ou PNG de 2 Mo au maximum.')
      return
    }
    const request = ++sequence.current
    setBusy(true)
    setError('')
    setNotice('')
    setPreview(null)
    setChecked(false)
    try {
      const result = await extractReceipt(file)
      if (sequence.current !== request) return
      if (!['EXTRACTED', 'PARTIAL'].includes(result?.status)) {
        setError('La lecture locale du ticket est indisponible ou n’a pas donné de résultat fiable. Vous pouvez toujours importer un relevé CSV.')
        return
      }
      setPreview(result)
      setFields({ merchant: result.merchant || '', date: result.date || '', amount: result.amount || '', currency: result.currency || '' })
    } catch {
      if (sequence.current === request) setError('Le ticket n’a pas pu être lu. Aucun fichier ni aucune opération n’a été enregistré.')
    } finally {
      if (sequence.current === request) setBusy(false)
    }
  }

  function update(key, value) {
    setFields((current) => ({ ...current, [key]: value }))
    setChecked(false)
    setNotice('')
    setError('')
  }

  function download() {
    if (!checked || busy) return
    const validation = receiptValidation(fields, preview?.asOfDate)
    if (validation) { setError(validation); return }
    const csv = createReceiptCsv(fields, preview.asOfDate)
    const url = URL.createObjectURL(new Blob(['\ufeff', csv], { type: 'text/csv;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `ticket-${fields.date}-${fields.currency}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
    setChecked(false)
    setNotice('CSV préparé. Importez-le ci-dessous après avoir vérifié qu’il ne fait pas doublon avec vos opérations bancaires.')
  }

  return (
    <details ref={panel} className="csv-import-panel receipt-panel card">
      <summary>Lire un ticket — IA locale expérimentale</summary>
      <p>La lecture propose des champs à vérifier. Elle ne crée aucune transaction et n’applique aucune catégorie. L’image n’est pas conservée ni envoyée à un fournisseur externe.</p>
      <p className="muted">Photographiez le ticket entier, bien à plat et sans reflet. Lecture française/tunisienne : dates jour/mois/année, montants avec virgule ou point.</p>
      <form className="csv-import-form" onSubmit={read}>
        <label className="field"><span>Photo du ticket (JPEG ou PNG, 2 Mo maximum)</span><input accept="image/jpeg,image/png" type="file" disabled={busy} onChange={changeFile} /></label>
        <button className="button button--secondary" type="submit" disabled={busy || !file}>{busy ? 'Lecture locale en cours…' : 'Lire ce ticket'}</button>
      </form>
      {busy && <p role="status">La lecture peut prendre quelques instants. Aucun import n’est effectué.</p>}
      {error && <InlineNotice live tone="warning">{error}</InlineNotice>}
      {notice && <InlineNotice live tone="success">{notice}</InlineNotice>}
      <div className="receipt-review-layout">
      {imageUrl && <figure className="receipt-image-preview">
        <img src={imageUrl} alt="Aperçu local du ticket à vérifier" />
        <figcaption>Photo locale — comparez la date et le total payé avec les champs proposés.</figcaption>
      </figure>}
      {preview && (
        <div className="csv-import-preview">
          <h3>Vérifier avant d’exporter</h3>
          {preview.status === 'PARTIAL' && <InlineNotice live tone="warning">{!preview.date
            ? 'La date n’a pas pu être validée. Recopiez la date d’achat imprimée sur le ticket (jour/mois/année). Vérifiez aussi les autres champs, sans supposer une devise absente.'
            : 'Certains champs n’ont pas été reconnus. Complétez-les sans supposer une devise absente du ticket.'}</InlineNotice>}
          <div className="csv-import-options">
            <div className="receipt-review-field">
              <label className="field"><span>Commerçant</span><input value={fields.merchant} maxLength={160} aria-invalid={Boolean(warnings.merchant)} aria-describedby={warnings.merchant ? `${fieldId}-merchant` : undefined} onChange={(e) => update('merchant', e.target.value)} /></label>
              {warnings.merchant && <p className="receipt-field-warning" id={`${fieldId}-merchant`}>{warnings.merchant}</p>}
            </div>
            <div className="receipt-review-field">
              <label className="field"><span>Date de l’achat</span><input type="date" value={fields.date} min="1900-01-01" max={preview.asOfDate} aria-invalid={Boolean(warnings.date)} aria-describedby={warnings.date ? `${fieldId}-date` : undefined} onChange={(e) => update('date', e.target.value)} /></label>
              {warnings.date && <p className="receipt-field-warning" id={`${fieldId}-date`}>{warnings.date}</p>}
            </div>
            <div className="receipt-review-field">
              <label className="field"><span>Montant total</span><input inputMode="decimal" value={fields.amount} aria-invalid={Boolean(warnings.amount)} aria-describedby={`${fieldId}-amount`} onChange={(e) => update('amount', e.target.value)} /></label>
              <p className={warnings.amount ? 'receipt-field-warning' : 'muted'} id={`${fieldId}-amount`}>{warnings.amount || 'Total payé, taxes et timbre compris — pas le sous-total ni la TVA seule.'}</p>
            </div>
            <div className="receipt-review-field">
              <label className="field"><span>Devise imprimée</span><input value={fields.currency} maxLength={3} aria-invalid={Boolean(warnings.currency)} aria-describedby={warnings.currency ? `${fieldId}-currency` : undefined} onChange={(e) => update('currency', e.target.value.toUpperCase())} /></label>
              {warnings.currency && <p className="receipt-field-warning" id={`${fieldId}-currency`}>{warnings.currency}</p>}
            </div>
          </div>
          <p className="muted">Attention : un ticket peut déjà correspondre à une transaction bancaire. Les doublons entre sources différentes ne sont pas détectés automatiquement.</p>
          <label className="checkbox-label"><input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />J’ai vérifié les champs et l’absence de doublon bancaire.</label>
          <button className="button button--primary" type="button" disabled={!checked || busy} onClick={download}>Télécharger le CSV vérifié</button>
        </div>
      )}
      </div>
    </details>
  )
}
