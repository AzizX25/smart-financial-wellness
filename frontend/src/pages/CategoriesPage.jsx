import { useCallback, useEffect, useMemo, useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, formatCurrency, InlineNotice, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'
import { createCategory, deleteCategory, getCategorySummary, listCategories, runCategorization, updateCategory } from '../services/categoryApi'

const defaultCategoryColor = '#7667e8'
const emptyForm = { name: '', nature: 'EXPENSE', iconKey: '', color: defaultCategoryColor }
const natureLabels = { EXPENSE: 'Dépense', INCOME: 'Revenu', TRANSFER: 'Transfert', NEUTRAL: 'Neutre' }

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

export default function CategoriesPage() {
  const { dataVersion, reload: reloadFinancialData } = useFinancialData()
  const [categories, setCategories] = useState([])
  const [summary, setSummary] = useState({ month: currentMonth(), currencies: [] })
  const [form, setForm] = useState(emptyForm)
  const [editingId, setEditingId] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [visibleCategories, categorySummary] = await Promise.all([
        listCategories(), getCategorySummary(currentMonth()),
      ])
      setCategories(visibleCategories)
      setSummary(categorySummary)
    } catch {
      setError('Les catégories n’ont pas pu être chargées.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [dataVersion, load])

  const systemCategories = useMemo(() => categories.filter((category) => category.system), [categories])
  const personalCategories = useMemo(() => categories.filter((category) => !category.system), [categories])

  async function submit(event) {
    event.preventDefault()
    setSaving(true)
    setError('')
    setMessage('')
    try {
      const payload = { ...form, iconKey: form.iconKey || null }
      if (editingId) await updateCategory(editingId, payload)
      else await createCategory(payload)
      setMessage(editingId ? 'Catégorie modifiée.' : 'Catégorie personnelle créée.')
      setEditingId('')
      setForm(emptyForm)
      await load()
    } catch (requestError) {
      setError(requestError.code === 'CATEGORY_NAME_ALREADY_EXISTS'
        ? 'Une catégorie personnelle porte déjà ce nom.'
        : 'La catégorie n’a pas pu être enregistrée.')
    } finally {
      setSaving(false)
    }
  }

  async function remove(category) {
    setError('')
    setMessage('')
    try {
      await deleteCategory(category.id)
      setMessage('Catégorie supprimée.')
      await load()
    } catch (requestError) {
      setError(requestError.code === 'CATEGORY_IN_USE'
        ? 'Cette catégorie est utilisée par une transaction et ne peut pas être supprimée.'
        : 'La catégorie n’a pas pu être supprimée.')
    }
  }

  async function categorize() {
    setSaving(true)
    setError('')
    setMessage('')
    try {
      const result = await runCategorization()
      setMessage(`${result.categorizedCount} transaction${result.categorizedCount === 1 ? '' : 's'} catégorisée${result.categorizedCount === 1 ? '' : 's'}.`)
      await Promise.all([load(), reloadFinancialData({ quiet: true })])
    } catch {
      setError('La catégorisation déterministe n’a pas pu être exécutée.')
    } finally {
      setSaving(false)
    }
  }

  function edit(category) {
    setEditingId(category.id)
    setForm({ name: category.name, nature: category.nature, iconKey: category.iconKey || '', color: category.color || defaultCategoryColor })
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Organisation réelle"
        title="Mes catégories"
        description="Classez vos transactions avec des règles explicites ou vos propres catégories."
        action={<button className="button button--primary" disabled={saving} onClick={categorize} type="button"><Icon name="transactions" size={18} /> Appliquer les règles</button>}
      />
      {error && <InlineNotice live tone="warning" icon="alert">{error}</InlineNotice>}
      {message && <InlineNotice live tone="success" icon="check">{message}</InlineNotice>}

      <div className="category-layout">
        <Card className="category-form-card">
          <h2>{editingId ? 'Modifier la catégorie' : 'Nouvelle catégorie personnelle'}</h2>
          <form className="category-form" onSubmit={submit}>
            <label>Nom<input maxLength={160} onChange={(event) => setForm({ ...form, name: event.target.value })} required value={form.name} /></label>
            <label>Nature<select onChange={(event) => setForm({ ...form, nature: event.target.value })} value={form.nature}>{Object.entries(natureLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
            <label>Couleur<input aria-label="Couleur" onChange={(event) => setForm({ ...form, color: event.target.value })} type="color" value={form.color} /></label>
            <div className="category-form__actions"><button className="button button--primary" disabled={saving} type="submit">{editingId ? 'Enregistrer' : 'Créer'}</button>{editingId && <button className="button button--secondary" onClick={() => { setEditingId(''); setForm(emptyForm) }} type="button">Annuler</button>}</div>
          </form>
        </Card>

        <section className="category-groups" aria-busy={loading}>
          {loading && categories.length === 0 ? <Card className="bank-state" aria-live="polite"><span className="auth-spinner" /> Chargement des catégories…</Card> : <>
            <Card><h2>Catégories système</h2><div className="category-chip-list">{systemCategories.map((category) => <span className="category-chip" key={category.id}><i style={{ background: category.color || 'var(--color-text-muted)' }} />{category.name}<Badge>{natureLabels[category.nature]}</Badge></span>)}</div></Card>
            <Card><h2>Catégories personnelles</h2>{personalCategories.length === 0 ? <p className="muted">Aucune catégorie personnelle pour le moment.</p> : <div className="personal-category-list">{personalCategories.map((category) => <div key={category.id}><span><i style={{ background: category.color }} />{category.name}</span><div><button className="button button--ghost" onClick={() => edit(category)} type="button">Modifier</button><button className="button button--ghost" onClick={() => remove(category)} type="button">Supprimer</button></div></div>)}</div>}</Card>
          </>}
        </section>
      </div>

      <section className="category-summary"><h2>Résumé du mois</h2>{summary.currencies.length === 0 ? <Card><p>Aucune transaction pour ce mois.</p></Card> : summary.currencies.map((currency) => <Card key={currency.currency}><div className="category-summary__header"><h3>{currency.currency}</h3><span>{currency.uncategorizedCount} non catégorisée{currency.uncategorizedCount === 1 ? '' : 's'} · {formatCurrency(currency.uncategorizedAmount, currency.currency)}</span></div><div className="category-total-list">{currency.categories.map((item) => <div key={item.categoryId}><span><strong>{item.categoryName}</strong><small>{natureLabels[item.nature]} · {item.transactionCount} transaction{item.transactionCount === 1 ? '' : 's'}</small></span><strong>{formatCurrency(item.totalAmount, currency.currency)}</strong></div>)}</div></Card>)}</section>
    </div>
  )
}
