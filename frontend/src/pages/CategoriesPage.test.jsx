import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import { createCategory, deleteCategory, getCategorySummary, listCategories, runCategorization, updateCategory } from '../services/categoryApi'
import CategoriesPage from './CategoriesPage'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/categoryApi', () => ({ createCategory: vi.fn(), deleteCategory: vi.fn(), getCategorySummary: vi.fn(), listCategories: vi.fn(), runCategorization: vi.fn(), updateCategory: vi.fn() }))

const system = { id: 'SYS', code: 'FOOD', name: 'Alimentation et courses', nature: 'EXPENSE', color: '#D66A4A', system: true }
const personal = { id: 'OWN', code: 'PERSONAL', name: 'Animaux', nature: 'EXPENSE', color: '#4B8FD8', system: false }
const summary = { month: '2026-08', currencies: [{ currency: 'TND', uncategorizedCount: 2, uncategorizedAmount: 15, categories: [{ categoryId: 'SYS', categoryName: 'Alimentation et courses', nature: 'EXPENSE', transactionCount: 3, totalAmount: 90 }] }, { currency: 'EUR', uncategorizedCount: 0, uncategorizedAmount: 0, categories: [{ categoryId: 'OWN', categoryName: 'Animaux', nature: 'EXPENSE', transactionCount: 1, totalAmount: 10 }] }] }

describe('page Catégories dynamique', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useFinancialData.mockReturnValue({ dataVersion: 1, reload: vi.fn().mockResolvedValue(true) })
    listCategories.mockResolvedValue([system, personal])
    getCategorySummary.mockResolvedValue(summary)
  })

  it('affiche catégories système, personnelles, résumé par devise et non catégorisées', async () => {
    render(<CategoriesPage />)
    expect(await screen.findAllByText('Alimentation et courses')).toHaveLength(2)
    expect(screen.getAllByText('Animaux')).toHaveLength(2)
    expect(screen.getByRole('heading', { name: 'TND' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'EUR' })).toBeInTheDocument()
    expect(screen.getByText(/2 non catégorisées/)).toBeInTheDocument()
  })

  it('crée, modifie et supprime une catégorie personnelle', async () => {
    createCategory.mockResolvedValue(personal)
    updateCategory.mockResolvedValue(personal)
    deleteCategory.mockResolvedValue(null)
    render(<CategoriesPage />)
    await screen.findByRole('button', { name: 'Modifier' })
    fireEvent.click(screen.getByRole('button', { name: 'Nouvelle catégorie' }))
    fireEvent.change(screen.getByLabelText('Nom'), { target: { value: 'Vacances' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer' }))
    await waitFor(() => expect(createCategory).toHaveBeenCalledWith(expect.objectContaining({ name: 'Vacances' })))
    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    fireEvent.change(screen.getByLabelText('Nom'), { target: { value: 'Animaux domestiques' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(updateCategory).toHaveBeenCalledWith('OWN', expect.objectContaining({ name: 'Animaux domestiques' })))
    fireEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
    await waitFor(() => expect(deleteCategory).toHaveBeenCalledWith('OWN'))
  })

  it('affiche les couleurs enregistrées pour les catégories système, personnelles et leur résumé', async () => {
    render(<CategoriesPage />)
    for (const category of [system, personal]) {
      const swatches = await screen.findAllByRole('img', { name: `Couleur de ${category.name} : ${category.color}` })
      expect(swatches).toHaveLength(2)
      for (const swatch of swatches) expect(swatch).toHaveStyle({ backgroundColor: category.color })
      expect(swatches[0]).toHaveClass('category-definition__icon')
      expect(swatches[1]).toHaveClass('category-color-dot')
    }
  })

  it('garde une icône contrastée sur une couleur claire et utilise un repli pour une couleur invalide', async () => {
    listCategories.mockResolvedValue([{ ...system, color: 'invalid-color' }, { ...personal, color: '#FFFFFF' }])
    render(<CategoriesPage />)
    const light = await screen.findAllByRole('img', { name: 'Couleur de Animaux : #FFFFFF' })
    expect(light[0]).toHaveStyle({ backgroundColor: '#FFFFFF', color: '#030303' })
    const fallback = screen.getAllByRole('img', { name: 'Couleur de Alimentation et courses : #22396F' })
    expect(fallback[0]).toHaveStyle({ backgroundColor: '#22396f', color: '#ffffff' })
  })

  it('reflète une couleur modifiée après le rechargement autoritaire', async () => {
    listCategories.mockResolvedValueOnce([system, personal]).mockResolvedValue([system, { ...personal, color: '#FFD400' }])
    updateCategory.mockResolvedValue({ ...personal, color: '#FFD400' })
    render(<CategoriesPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    fireEvent.change(screen.getByLabelText('Couleur'), { target: { value: '#ffd400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findAllByRole('img', { name: 'Couleur de Animaux : #FFD400' })).toHaveLength(2)
    expect(updateCategory).toHaveBeenCalledWith('OWN', expect.objectContaining({ color: '#ffd400' }))
  })

  it('exécute la catégorisation déterministe et recharge les données', async () => {
    const reload = vi.fn().mockResolvedValue(true)
    useFinancialData.mockReturnValue({ dataVersion: 1, reload })
    runCategorization.mockResolvedValue({ categorizedCount: 2, uncategorizedCount: 1 })
    render(<CategoriesPage />)
    await screen.findByRole('button', { name: 'Modifier' })
    fireEvent.click(screen.getByRole('button', { name: /Appliquer les règles/ }))
    expect(await screen.findByText('2 transactions catégorisées.')).toBeInTheDocument()
    expect(reload).toHaveBeenCalled()
  })

  it('reveals the editor on demand and filters category groups without changing data', async () => {
    render(<CategoriesPage />)
    await screen.findByRole('button', { name: 'Modifier' })
    expect(screen.getByLabelText('Nom')).not.toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Personnelles' }))
    expect(screen.queryByRole('heading', { name: 'Catégories système' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Catégories personnelles' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    expect(screen.getByLabelText('Nom')).toBeVisible()
    expect(screen.getByLabelText('Nom')).toHaveValue('Animaux')
    expect(screen.getByLabelText('Nom').closest('.category-form-card').previousElementSibling).toHaveClass('category-definition--personal')
  })

  it('affiche une erreur contrôlée de suppression en cours d’utilisation', async () => {
    deleteCategory.mockRejectedValue({ code: 'CATEGORY_IN_USE' })
    render(<CategoriesPage />)
    await screen.findByRole('button', { name: 'Supprimer' })
    fireEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
    expect(await screen.findByText(/utilisée par une transaction/)).toBeInTheDocument()
  })
})
