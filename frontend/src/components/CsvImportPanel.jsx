import { useEffect, useRef, useState } from 'react'
import { useFinancialData } from '../financial/FinancialDataContext'
import { ApiError } from '../services/apiClient'
import { confirmCsvImport, createImportAccount, previewCsvImport } from '../services/csvImportApi'
import { formatCurrency, InlineNotice } from './Ui'

const initialSettings = { delimiter: 'AUTO', dateFormat: 'ISO', decimalSeparator: 'DOT', mapping: null }
const mappingFields = [
  ['dateColumn', 'Date de comptabilisation', true],
  ['descriptionColumn', 'Libellé', true],
  ['amountColumn', 'Montant', true],
  ['directionColumn', 'Sens DEBIT / CREDIT', false],
  ['currencyColumn', 'Devise', false],
]

function errorMessage(error) {
  if (!(error instanceof ApiError) || error.status === 0 || error.status >= 500) {
    return "Le service d'import est momentanément indisponible. Réessayez avec le même fichier et le même compte : les imports répétés sont protégés."
  }
  if (error.status === 404) return "Ce compte ou cet import n'est plus disponible. Sélectionnez un compte à nouveau."
  return error.message || "Vérifiez le fichier et les colonnes sélectionnées."
}

export default function CsvImportPanel() {
  const { accounts = [], reload } = useFinancialData()
  const [target, setTarget] = useState('')
  const [createdAccount, setCreatedAccount] = useState(null)
  const [name, setName] = useState('Compte de mes relevés')
  const [currency, setCurrency] = useState('TND')
  const [balance, setBalance] = useState('')
  const [file, setFile] = useState(null)
  const [settings, setSettings] = useState(initialSettings)
  const [preview, setPreview] = useState(null)
  const [previewCurrent, setPreviewCurrent] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [fileInputKey, setFileInputKey] = useState(0)
  const sequence = useRef(0)
  const availableAccounts = accounts.filter((account) => !account.localStatus || account.localStatus === 'ACTIVE')
  if (createdAccount && !availableAccounts.some((account) => account.id === createdAccount.id)) {
    availableAccounts.push({ ...createdAccount, displayName: createdAccount.name })
  }

  useEffect(() => () => { sequence.current += 1 }, [])

  function invalidate() {
    sequence.current += 1
    setPreviewCurrent(false)
    setConfirmed(false)
    setResult(null)
    setError('')
  }

  function changeSetting(field, value) {
    invalidate()
    setSettings((current) => ({ ...current, [field]: value }))
  }

  function chooseFile(event) {
    invalidate()
    setPreview(null)
    setSettings((current) => ({ ...current, mapping: null }))
    const selected = event.target.files?.[0] || null
    if (selected && (!/\.csv$/i.test(selected.name) || selected.size > 2 * 1024 * 1024 || selected.size === 0)) {
      setFile(null)
      setError('Sélectionnez un CSV non vide de 2 Mo au maximum.')
      return
    }
    setFile(selected)
  }

  async function createAccount() {
    const amount = balance.trim().replace(',', '.')
    if (!name.trim() || !/^-?\d{1,15}(?:\.\d{1,4})?$/.test(amount)) {
      setError('Saisissez un nom et le solde actuel avec quatre décimales au maximum.')
      return
    }
    const current = ++sequence.current
    setBusy(true)
    setError('')
    try {
      const account = await createImportAccount({ name: name.trim(), currency, currentBalance: amount })
      if (current !== sequence.current) return
      setCreatedAccount(account)
      setTarget(account.id)
      setPreview(null)
      setPreviewCurrent(false)
      setNotice(account.reused
        ? 'Ce compte existe déjà. Son solde a été conservé.'
        : 'Compte créé avec le solde que vous avez déclaré. Les imports ne recalculent pas ce solde.')
      if (reload) await reload({ quiet: true })
    } catch (requestError) {
      if (current === sequence.current) setError(errorMessage(requestError))
    } finally {
      if (current === sequence.current) setBusy(false)
    }
  }

  async function loadPreview(event) {
    event.preventDefault()
    if (!file || !target || target === 'new') {
      setError('Sélectionnez un compte et un fichier CSV.')
      return
    }
    const current = ++sequence.current
    setBusy(true)
    setError('')
    setPreviewCurrent(false)
    setConfirmed(false)
    try {
      const response = await previewCsvImport(file, { accountId: target, ...settings })
      if (current !== sequence.current) return
      setPreview(response)
      setSettings({ mapping: response.mapping, delimiter: response.delimiter,
        dateFormat: response.dateFormat, decimalSeparator: response.decimalSeparator })
      setPreviewCurrent(true)
    } catch (requestError) {
      if (current === sequence.current) setError(errorMessage(requestError))
    } finally {
      if (current === sequence.current) setBusy(false)
    }
  }

  async function importFile() {
    if (!previewCurrent || !preview?.canImport || !confirmed || busy) return
    const current = ++sequence.current
    setBusy(true)
    setError('')
    try {
      const response = await confirmCsvImport(preview.jobId, file, { accountId: target, ...settings })
      if (current !== sequence.current) return
      setResult(response)
      setPreviewCurrent(false)
      if (reload && !await reload({ quiet: true })) {
        setNotice("L'import est confirmé, mais l'affichage financier n'a pas pu être actualisé. Rechargez la page.")
      }
    } catch (requestError) {
      if (current === sequence.current) setError(errorMessage(requestError))
    } finally {
      if (current === sequence.current) setBusy(false)
    }
  }

  function anotherFile() {
    invalidate()
    setFile(null)
    setPreview(null)
    setNotice('')
    setSettings(initialSettings)
    setFileInputKey((value) => value + 1)
  }

  return (
    <details className="csv-import-panel card">
      <summary>Importer un relevé CSV</summary>
      <p>Ajoutez des opérations sans connexion bancaire. Le fichier est envoyé au serveur pour validation, sans conserver le fichier original. Seuls son empreinte, son mapping et les opérations confirmées sont enregistrés.</p>
      {error && <InlineNotice tone="error" live>{error}</InlineNotice>}
      {notice && <InlineNotice live>{notice}</InlineNotice>}
      {result ? (
        <div className="csv-import-result" role="status">
          <h3>Import terminé</h3>
          <p>{result.importedCount} opération(s) ajoutée(s), {result.duplicateCount} ligne(s) déjà importée(s).</p>
          <button className="button button--secondary" type="button" onClick={anotherFile}>Importer un autre fichier</button>
        </div>
      ) : (
        <>
          <label className="field"><span>Compte destinataire</span>
            <select value={target} disabled={busy} onChange={(event) => { invalidate(); setTarget(event.target.value); setPreview(null) }}>
              <option value="">Choisir un compte</option>
              {availableAccounts.map((account) => <option key={account.id} value={account.id}>{account.displayName} · {account.currency}</option>)}
              <option value="new">Créer un compte pour mes relevés</option>
            </select>
          </label>
          {target === 'new' && (
            <fieldset className="csv-import-account" disabled={busy}>
              <legend>Nouveau compte indépendant de la banque</legend>
              <label className="field"><span>Nom du compte</span><input maxLength={160} value={name} onChange={(event) => setName(event.target.value)} /></label>
              <label className="field"><span>Devise du compte</span><select value={currency} onChange={(event) => setCurrency(event.target.value)}>{['TND', 'EUR', 'USD'].map((item) => <option key={item}>{item}</option>)}</select></label>
              <label className="field"><span>Solde actuel déclaré</span><input value={balance} inputMode="decimal" onChange={(event) => setBalance(event.target.value)} aria-describedby="csv-balance-help" /></label>
              <p id="csv-balance-help">Saisissez votre solde d'aujourd'hui. Il ne sera pas déduit d'un historique potentiellement incomplet.</p>
              <button className="button button--secondary" type="button" onClick={createAccount}>Créer le compte</button>
            </fieldset>
          )}
          <form onSubmit={loadPreview} className="csv-import-form">
            <label className="field"><span>Fichier CSV</span><input key={fileInputKey} type="file" accept=".csv,text/csv" disabled={busy} onChange={chooseFile} aria-describedby="csv-format-help" /></label>
            <p id="csv-format-help">UTF-8, en-têtes obligatoires, 2 Mo et 2 000 opérations maximum. Les dates futures sont refusées.</p>
            <div className="csv-import-options">
              <label className="field"><span>Séparateur de colonnes</span><select value={settings.delimiter} disabled={busy} onChange={(event) => changeSetting('delimiter', event.target.value)}><option value="AUTO">Détection automatique</option><option value="SEMICOLON">Point-virgule</option><option value="COMMA">Virgule</option><option value="TAB">Tabulation</option></select></label>
              <label className="field"><span>Format des dates</span><select value={settings.dateFormat} disabled={busy} onChange={(event) => changeSetting('dateFormat', event.target.value)}><option value="ISO">AAAA-MM-JJ</option><option value="DAY_MONTH_YEAR">JJ/MM/AAAA</option></select></label>
              <label className="field"><span>Séparateur décimal</span><select value={settings.decimalSeparator} disabled={busy} onChange={(event) => changeSetting('decimalSeparator', event.target.value)}><option value="DOT">Point (25.5000)</option><option value="COMMA">Virgule (25,5000)</option></select></label>
            </div>
            {preview && (
              <fieldset className="csv-import-mapping" disabled={busy}>
                <legend>Vérifier les colonnes</legend>
                {mappingFields.map(([field, label, required]) => (
                  <label className="field" key={field}><span>{label}{required ? ' *' : ' (facultatif)'}</span>
                    <select value={settings.mapping?.[field] || ''} onChange={(event) => changeSetting('mapping', { ...settings.mapping, [field]: event.target.value || null })}>
                      <option value="">{required ? 'Choisir une colonne' : field === 'directionColumn' ? 'Signe du montant' : 'Devise du compte'}</option>
                      {preview.columns.map((column) => <option key={column}>{column}</option>)}
                    </select>
                  </label>
                ))}
                <p>Sans colonne de sens : montant négatif = débit, positif = crédit. Avec une colonne de sens : DEBIT ou CREDIT. Aucun arrondi ni conversion de devise.</p>
              </fieldset>
            )}
            <button className="button button--secondary" type="submit" disabled={busy || !file || !target || target === 'new'}>{busy ? 'Traitement en cours…' : 'Prévisualiser le fichier'}</button>
          </form>
          {preview && (
            <section className="csv-import-preview" aria-label="Prévisualisation CSV" aria-live="polite">
              <h3>{preview.rowCount} opération(s) dans le fichier</h3>
              <p>{preview.validCount} ligne(s) valide(s), {preview.invalidCount} à corriger, {preview.duplicateCount} déjà importée(s).</p>
              {!previewCurrent && <p>Les paramètres ont changé. Prévisualisez à nouveau avant d'importer.</p>}
              {preview.errors.length > 0 && <ul>{preview.errors.map((item) => <li key={item.rowNumber}>Ligne {item.rowNumber} : {item.message}</li>)}</ul>}
              <ol className="csv-preview-rows">{preview.rows.map((row) => <li key={row.rowNumber}><span><strong>{row.description}</strong><small>{row.bookingDate} · {row.direction === 'DEBIT' ? 'Débit' : 'Crédit'}</small></span><strong>{formatCurrency(row.amount, row.currency)}</strong></li>)}</ol>
              {preview.alreadyImported ? <InlineNotice live tone="success">Ce fichier a déjà été importé dans ce compte. Aucune opération supplémentaire n'a été créée.</InlineNotice> : (
                <>
                  <p>Un même fichier est protégé contre les imports répétés. Des fichiers différents qui se chevauchent, ou un relevé déjà synchronisé depuis la banque, peuvent contenir des doublons.</p>
                  <label className="checkbox-label"><input type="checkbox" checked={confirmed} disabled={busy} onChange={(event) => setConfirmed(event.target.checked)} />J'ai vérifié les lignes, le sens et l'absence de chevauchement.</label>
                  <button className="button button--primary" type="button" disabled={busy || !previewCurrent || !preview.canImport || !confirmed} onClick={importFile}>Confirmer l'import</button>
                </>
              )}
            </section>
          )}
        </>
      )}
    </details>
  )
}
