import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import Icon from '../components/Icon'
import { Badge, Card, formatCurrency, InlineNotice, PageHeader } from '../components/Ui'
import { useFinancialData } from '../financial/FinancialDataContext'
import { createCategory, deleteCategory, getCategorySummary, listCategories, runCategorization, updateCategory } from '../services/categoryApi'

const defaultCategoryColor = '#22396f'
const emptyForm = { name: '', nature: 'EXPENSE', iconKey: '', color: defaultCategoryColor }
const natureLabels = { EXPENSE: 'Dépense', INCOME: 'Revenu', TRANSFER: 'Transfert', NEUTRAL: 'Neutre' }

function CategoryColor({ color, name, compact = false }) {
  const resolved = /^#[\da-f]{6}$/i.test(color || '') ? color : defaultCategoryColor
  const rgb = resolved.slice(1).match(/.{2}/g).map((part) => parseInt(part, 16) / 255)
  const channels = rgb.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  const luminance = channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
  return (
    <span
      className={compact ? 'category-color-dot' : 'category-definition__icon'}
      role="img"
      aria-label={`Couleur de ${name} : ${resolved.toUpperCase()}`}
      style={{ backgroundColor: resolved, color: luminance > 0.18 ? '#030303' : '#ffffff' }}
    >
      {!compact && <Icon name="tag" size={20} />}
    </span>
  )
}

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
  const [editorOpen, setEditorOpen] = useState(false)
  const [categoryView, setCategoryView] = useState('all')
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
  const categoryColors = useMemo(() => new Map(categories.map((category) => [category.id, category.color])), [categories])

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
      setEditorOpen(false)
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
    setEditorOpen(true)
    setEditingId(category.id)
    setForm({ name: category.name, nature: category.nature, iconKey: category.iconKey || '', color: category.color || defaultCategoryColor })
  }

  const categoryEditor = (
    <Card className="category-form-card inline-panel" hidden={!editorOpen} id="category-editor">
      <h2>{editingId ? 'Modifier la catégorie' : 'Nouvelle catégorie personnelle'}</h2>
      <form className="category-form" onSubmit={submit}>
        <label>Nom<input maxLength={160} onChange={(event) => setForm({ ...form, name: event.target.value })} required value={form.name} /></label>
        <label>Nature<select onChange={(event) => setForm({ ...form, nature: event.target.value })} value={form.nature}>{Object.entries(natureLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label>Couleur<input aria-label="Couleur" onChange={(event) => setForm({ ...form, color: event.target.value })} type="color" value={form.color} /></label>
        <div className="category-form__actions"><button className="button button--primary" disabled={saving} type="submit">{editingId ? 'Enregistrer' : 'Créer'}</button><button className="button button--secondary" disabled={saving} onClick={() => { setEditingId(''); setForm(emptyForm); setEditorOpen(false) }} type="button">Annuler</button></div>
      </form>
    </Card>
  )

  return (
    <div className="page categories-page">
      <PageHeader
        eyebrow="Une place pour chaque dépense"
        title="Mes catégories"
        description="Des catégories claires, et vos propres repères pour mieux vous organiser."
        action={<div className="button-row"><button className="button button--secondary" disabled={saving} onClick={categorize} type="button"><Icon name="transactions" size={18} /> Appliquer les règles</button><button className="button button--primary" aria-expanded={editorOpen} aria-controls="category-editor" onClick={() => { setEditingId(''); setForm(emptyForm); setEditorOpen(true) }} type="button">Nouvelle catégorie</button></div>}
      />
      {error && <InlineNotice live tone="warning" icon="alert">{error}</InlineNotice>}
      {message && <InlineNotice live tone="success" icon="check">{message}</InlineNotice>}

      <div className="category-layout">
        {!editingId && categoryEditor}

        <section className="category-groups" aria-busy={loading}>
          <nav className="workspace-switcher" aria-label="Afficher les catégories">
            {[['all', 'Toutes'], ['system', 'Système'], ['personal', 'Personnelles']].map(([view, label]) => <button className="button" aria-pressed={categoryView === view} key={view} onClick={() => setCategoryView(view)} type="button">{label}</button>)}
          </nav>
          {loading && categories.length === 0 ? <Card className="bank-state" aria-live="polite"><span className="auth-spinner" /> Chargement des catégories…</Card> : <>
            <Card hidden={categoryView === 'personal'}><div className="section-heading"><h2>Catégories système</h2><Badge>{systemCategories.length}</Badge></div><div className="category-definition-grid">{systemCategories.map((category) => <article className="category-definition" key={category.id}><CategoryColor color={category.color} name={category.name} /><div><h3>{category.name}</h3><span className="muted">{natureLabels[category.nature]}</span></div><Badge>Standard</Badge></article>)}</div></Card>
            <Card hidden={categoryView === 'system'}><div className="section-heading"><h2>Catégories personnelles</h2><Badge>{personalCategories.length}</Badge></div>{personalCategories.length === 0 ? <div className="category-empty"><Icon name="tag" /><p>Créez une catégorie pour les dépenses qui vous ressemblent.</p></div> : <div className="category-definition-grid">{personalCategories.map((category) => <Fragment key={category.id}><article className={`category-definition category-definition--personal${editingId === category.id ? ' category-definition--expanded' : ''}`}><CategoryColor color={category.color} name={category.name} /><div><h3>{category.name}</h3><span className="muted">{natureLabels[category.nature]}</span></div><div className="category-definition__actions"><button className="button button--ghost" aria-expanded={editorOpen && editingId === category.id} aria-controls={editingId === category.id ? 'category-editor' : undefined} onClick={() => edit(category)} type="button">Modifier</button><button className="button button--danger-quiet" onClick={() => remove(category)} type="button">Supprimer</button></div></article>{editingId === category.id && categoryEditor}</Fragment>)}</div>}</Card>
          </>}
        </section>
      </div>

      <section className="category-summary"><h2>Résumé du mois</h2>{summary.currencies.length === 0 ? <Card><p>Aucune transaction pour ce mois.</p></Card> : summary.currencies.map((currency) => <Card key={currency.currency}><div className="category-summary__header"><h3>{currency.currency}</h3><span>{currency.uncategorizedCount} non catégorisée{currency.uncategorizedCount === 1 ? '' : 's'} · {formatCurrency(currency.uncategorizedAmount, currency.currency)}</span></div><div className="category-total-list">{currency.categories.map((item) => <div key={item.categoryId}><span><strong className="category-total-list__name"><CategoryColor compact color={categoryColors.get(item.categoryId)} name={item.categoryName} />{item.categoryName}</strong><small>{natureLabels[item.nature]} · {item.transactionCount} transaction{item.transactionCount === 1 ? '' : 's'}</small></span><strong>{formatCurrency(item.totalAmount, currency.currency)}</strong></div>)}</div></Card>)}</section>
    </div>
  )
}
