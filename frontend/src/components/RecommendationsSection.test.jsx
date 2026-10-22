import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { explainRecommendation, generateRecommendations, setRecommendationStatus } from '../services/recommendationApi'
import RecommendationsSection from './RecommendationsSection'

vi.mock('../services/recommendationApi', () => ({ explainRecommendation: vi.fn(), generateRecommendations: vi.fn(), setRecommendationStatus: vi.fn() }))

const item = {
  id: 'one', status: 'ACTIVE', priority: 'MEDIUM', title: 'Revoir ma marge', message: 'Une piste vérifiée.',
  fact: { code: 'SAVINGS_RATE', value: '0.0500', unit: 'RATIO', explanation: 'Taux observé.' },
  periodStart: '2026-04-01', periodEnd: '2026-09-30', currency: 'TND', rulesVersion: 'financial-recommendations-v1',
  actionPath: '/planification-salaire', actionLabel: 'Voir mon budget', disclaimer: 'Information sans garantie.',
}
const response = { currency: 'TND', status: 'AVAILABLE', items: [item] }
const wrapper = ({ children }) => <MemoryRouter>{children}</MemoryRouter>

describe('pistes financières autoritaires', () => {
  beforeEach(() => { vi.clearAllMocks(); generateRecommendations.mockResolvedValue(response) })

  it('affiche au plus trois priorités, les faits et le retour sauvegardé sans mutation financière', async () => {
    generateRecommendations.mockResolvedValue({ ...response, items: Array.from({ length: 4 }, (_, i) => ({ ...item, id: `${i}`, title: `Piste ${i}` })) })
    setRecommendationStatus.mockResolvedValue({ ...item, id: '0', title: 'Piste 0', status: 'ACCEPTED' })
    const { container } = render(<RecommendationsSection currency="TND" dataVersion={1} />, { wrapper })
    expect(await screen.findAllByRole('link', { name: 'Voir mon budget' })).toHaveLength(3)
    await waitFor(() => expect(container.querySelectorAll('.recommendation-card')).toHaveLength(3))
    expect(generateRecommendations).toHaveBeenCalledWith('TND')
    fireEvent.click(screen.getByText('Comprendre les pistes et donner mon avis'))
    const detail = container.querySelector('.recommendation-detail')
    expect(within(detail).getByText(/5/)).toBeInTheDocument()
    fireEvent.click(within(detail).getByRole('button', { name: 'Prise en compte' }))
    await waitFor(() => expect(setRecommendationStatus).toHaveBeenCalledWith('0', 'ACCEPTED'))
    await waitFor(() => expect(within(detail).queryByRole('button', { name: 'Prise en compte' })).not.toBeInTheDocument())
  })

  it('sépare les données insuffisantes, l’absence de piste et les erreurs avec nouvelle tentative', async () => {
    generateRecommendations.mockResolvedValueOnce({ currency: 'TND', status: 'INSUFFICIENT_DATA', items: [] })
    const view = render(<RecommendationsSection currency="TND" dataVersion={1} />, { wrapper })
    expect(await screen.findByText('Un peu plus d’historique est nécessaire')).toBeInTheDocument()
    generateRecommendations.mockRejectedValueOnce(new Error('offline'))
    view.rerender(<RecommendationsSection currency="TND" dataVersion={2} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('ne peuvent pas être chargées')
    expect(screen.queryByText('Aucune piste prioritaire détectée')).not.toBeInTheDocument()
    generateRecommendations.mockResolvedValueOnce({ currency: 'TND', status: 'NO_ACTION_NEEDED', items: [] })
    fireEvent.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByText('Aucune piste prioritaire détectée')).toBeInTheDocument()
  })

  it('ignore les réponses obsolètes lors du changement de devise et après invalidation', async () => {
    let resolveOld
    generateRecommendations.mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
    const view = render(<RecommendationsSection currency="EUR" dataVersion={1} />, { wrapper })
    view.rerender(<RecommendationsSection currency="TND" dataVersion={1} />)
    expect(await screen.findByRole('link', { name: 'Voir mon budget' })).toBeInTheDocument()
    resolveOld({ ...response, currency: 'EUR', items: [{ ...item, title: 'Obsolète' }] })
    await Promise.resolve()
    expect(screen.queryByText('Obsolète')).not.toBeInTheDocument()
    view.rerender(<RecommendationsSection currency="TND" dataVersion={2} />)
    await waitFor(() => expect(generateRecommendations).toHaveBeenCalledTimes(3))
  })

  it('conserve la piste si le retour échoue et ne rend pas de lien arbitraire', async () => {
    generateRecommendations.mockResolvedValue({ ...response, items: [{ ...item, actionPath: 'https://foreign.invalid' }] })
    setRecommendationStatus.mockRejectedValue(new Error('offline'))
    render(<RecommendationsSection currency="TND" dataVersion={1} />, { wrapper })
    await screen.findAllByText('Revoir ma marge')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Comprendre les pistes et donner mon avis'))
    fireEvent.click(screen.getByRole('button', { name: 'Masquer' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('n’a pas été enregistré')
    expect(screen.getByRole('button', { name: 'Masquer' })).not.toBeDisabled()
  })

  it('présente une explication locale sans remplacer les faits et conserve le texte déterministe en cas d’échec', async () => {
    explainRecommendation.mockResolvedValueOnce({ source: 'LOCAL_LLM', text: 'Comparez votre budget et vos dépenses.', factCode: 'SAVINGS_RATE' })
    const view = render(<RecommendationsSection currency="TND" dataVersion={1} />, { wrapper })
    fireEvent.click(await screen.findByRole('button', { name: 'Expliquer simplement' }))
    expect(await screen.findByText('Explication IA locale — expérimentale')).toBeInTheDocument()
    expect(screen.getByText('Comparez votre budget et vos dépenses.')).toBeInTheDocument()
    expect(screen.getByText('Comparez votre budget et vos dépenses.').closest('.recommendation-inline-panel').previousElementSibling).toHaveClass('recommendation-card')
    view.rerender(<RecommendationsSection currency="TND" dataVersion={2} />)
    explainRecommendation.mockRejectedValueOnce(new Error('offline'))
    fireEvent.click(await screen.findByRole('button', { name: 'Expliquer simplement' }))
    expect(await screen.findByText('Explication déterministe')).toBeInTheDocument()
    expect(generateRecommendations).toHaveBeenCalledTimes(2)
  })
})
