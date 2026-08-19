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

  it('affiche une erreur contrôlée de suppression en cours d’utilisation', async () => {
    deleteCategory.mockRejectedValue({ code: 'CATEGORY_IN_USE' })
    render(<CategoriesPage />)
    await screen.findByRole('button', { name: 'Supprimer' })
    fireEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
    expect(await screen.findByText(/utilisée par une transaction/)).toBeInTheDocument()
  })
})
