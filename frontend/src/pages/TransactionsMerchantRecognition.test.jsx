import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useFinancialData } from '../financial/FinancialDataContext'
import {
  acceptMerchantSuggestion,
  assignTransactionCategory,
  forgetMerchantMemory,
  listCategorizationReviews,
  listCategories,
  listMerchantMemories,
  listMerchantSuggestions,
  rejectMerchantSuggestion,
  runMerchantRecognition,
} from '../services/categoryApi'
import { listLocalTransactions } from '../services/financialApi'
import TransactionsPage from './TransactionsPage'

vi.mock('../financial/FinancialDataContext', () => ({ useFinancialData: vi.fn() }))
vi.mock('../services/financialApi', () => ({ listLocalTransactions: vi.fn() }))
vi.mock('../services/categoryApi', () => ({
  acceptMerchantSuggestion: vi.fn(),
  assignTransactionCategory: vi.fn(),
  forgetMerchantMemory: vi.fn(),
  listCategorizationReviews: vi.fn(),
  listCategories: vi.fn(),
  listMerchantMemories: vi.fn(),
  listMerchantSuggestions: vi.fn(),
  rejectMerchantSuggestion: vi.fn(),
  runMerchantRecognition: vi.fn(),
}))

const transactionPage = {
  items: [{
    id: 'TX-DEBIT', accountId: 'ACCOUNT-1', bookingDate: '2026-08-19',
    description: 'Paiement marchand', direction: 'DEBIT', amount: 25, currency: 'TND',
    categoryId: null, categoryName: null, categoryAssignmentSource: 'UNCATEGORIZED',
  }],
  page: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
}
const suggestion = {
  predictionId: 'PREDICTION-1', transactionId: 'TX-DEBIT', categoryId: 'CAT-FOOD',
  categoryCode: 'FOOD', categoryName: 'Alimentation et courses', canonicalMerchant: 'Carrefour',
  matchMethod: 'ML',
}
const emptyReviewPage = {
  items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true,
}

function pendingReview(item) {
  return {
    ...emptyReviewPage,
    totalElements: 1, totalPages: 1,
    items: [{ ...transactionPage.items[0], transactionId: 'TX-DEBIT',
      reviewStatus: 'SUGGESTION_PENDING', reasonCode: 'RELIABLE_SUGGESTION',
      pendingSuggestion: item, candidateCategories: [], manualCategorizationAllowed: true }],
  }
}

function openReview() {
  fireEvent.click(screen.getByRole('button', { name: /^À catégoriser/ }))
}

describe('suggestions marchandes des transactions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useFinancialData.mockReturnValue({
      accounts: [{ id: 'ACCOUNT-1', displayName: 'Compte courant' }], connected: true,
      dataVersion: 1, historical: false, reload: vi.fn().mockResolvedValue(true),
      synchronizationError: '', synchronize: vi.fn(), synchronizing: false,
    })
    listCategories.mockResolvedValue([{
      id: 'CAT-FOOD', code: 'FOOD', name: 'Alimentation et courses', nature: 'EXPENSE', system: true,
    }])
    listLocalTransactions.mockResolvedValue(transactionPage)
    listMerchantSuggestions.mockResolvedValue([])
    listCategorizationReviews.mockResolvedValue(emptyReviewPage)
    listMerchantMemories.mockResolvedValue([])
  })

  it('analyse, affiche et accepte une suggestion sans afficher de score', async () => {
    listCategorizationReviews.mockResolvedValueOnce(emptyReviewPage).mockResolvedValueOnce(pendingReview(suggestion)).mockResolvedValue(emptyReviewPage)
    listMerchantSuggestions.mockResolvedValueOnce([]).mockResolvedValueOnce([suggestion]).mockResolvedValue([])
    runMerchantRecognition.mockResolvedValue({ outcome: 'SUGGESTED', suggestedCount: 1 })
    let accepted = false
    listLocalTransactions.mockImplementation(async ({ approved }) => ({ ...transactionPage,
      items: approved === true && accepted ? transactionPage.items.map(item => ({ ...item, categoryId: 'CAT-FOOD', categoryApproved: true }))
        : approved === false && accepted ? [] : transactionPage.items }))
    acceptMerchantSuggestion.mockImplementation(async () => {
      accepted = true
      return { transactionId: 'TX-DEBIT', categoryId: 'CAT-FOOD', categoryName: 'Alimentation et courses',
        categoryNature: 'EXPENSE', assignmentSource: 'ML' }
    })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Paiement marchand' })

    fireEvent.click(screen.getByRole('button', { name: 'Analyser les transactions non catégorisées' }))
    const review = screen.getByRole('region', { name: 'Transactions à catégoriser' })
    expect(await within(review).findByText('Carrefour')).toBeInTheDocument()
    expect(within(review).getByText(/Suggestion intelligente/)).toBeInTheDocument()
    expect(within(review).getByText(/Suggestion expérimentale fondée/)).toBeInTheDocument()
    expect(screen.queryByText(/%|confiance/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Accepter' }))
    await waitFor(() => expect(acceptMerchantSuggestion).toHaveBeenCalledWith('TX-DEBIT', 'PREDICTION-1'))
    fireEvent.click(screen.getByRole('button', { name: 'Historique' }))
    fireEvent.click(screen.getByRole('button', { name: 'Approuvées', exact: true }))
    await waitFor(() => expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('CAT-FOOD'))
  })

  it('refuse une suggestion et conserve la transaction non catégorisée', async () => {
    listMerchantSuggestions.mockResolvedValueOnce([{ ...suggestion, matchMethod: 'EXACT_ALIAS' }]).mockResolvedValue([])
    rejectMerchantSuggestion.mockResolvedValue(null)
    render(<TransactionsPage />)
    fireEvent.click(await screen.findByText('Suggestion à vérifier'))
    await screen.findByText(/Alias connu/)

    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(rejectMerchantSuggestion).toHaveBeenCalledWith('TX-DEBIT', 'PREDICTION-1'))
    expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('')
    expect(await screen.findByText(/reste non catégorisée/)).toBeInTheDocument()
  })

  it('affiche une suggestion web bornée avec des liens sûrs puis permet de la refuser', async () => {
    const webSuggestion = {
      ...suggestion,
      canonicalMerchant: 'Commerce Exemple',
      categoryCode: 'SHOPPING',
      categoryName: 'Achats',
      matchMethod: 'WEB_LLM',
      source: 'WEB_LLM',
      rationale: 'Les sources publiques décrivent un commerce de détail.',
      evidenceUrls: [
        'https://example.test/merchant',
        'http://example.test/insecure',
        'https://user:password@example.test/credentials',
      ],
      experimental: true,
      guaranteed: false,
      providerId: 'internal-provider',
      modelVersion: 'internal-version',
      prompt: 'internal-prompt',
    }
    listMerchantSuggestions.mockResolvedValueOnce([webSuggestion]).mockResolvedValue([])
    rejectMerchantSuggestion.mockResolvedValue(null)

    render(<TransactionsPage />)
    fireEvent.click(await screen.findByText('Suggestion à vérifier'))

    expect(await screen.findByText('Commerce Exemple')).toBeInTheDocument()
    expect(screen.getByText(/Recherche web \+ IA locale/)).toBeInTheDocument()
    expect(screen.getByText(webSuggestion.rationale)).toBeInTheDocument()
    expect(screen.getByText(/expérimentale, informative et non garantie/)).toBeInTheDocument()
    const evidenceLink = screen.getByRole('link', { name: 'Voir la source 1' })
    expect(evidenceLink).toHaveAttribute('href', 'https://example.test/merchant')
    expect(evidenceLink).toHaveAttribute('target', '_blank')
    expect(evidenceLink).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(screen.queryByText(/%|confiance/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/internal-provider|internal-version|internal-prompt/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refuser' }))
    await waitFor(() => expect(rejectMerchantSuggestion).toHaveBeenCalledWith('TX-DEBIT', 'PREDICTION-1'))
  })

  it('affiche une indisponibilité contrôlée sans altérer les transactions', async () => {
    runMerchantRecognition.mockResolvedValue({ outcome: 'SERVICE_UNAVAILABLE', suggestedCount: 0 })
    render(<TransactionsPage />)
    await screen.findByRole('heading', { name: 'Paiement marchand' })

    fireEvent.click(screen.getByRole('button', { name: 'Analyser les transactions non catégorisées' }))
    expect(await screen.findByText(/service de suggestion est momentanément indisponible/)).toBeInTheDocument()
    expect(screen.getByLabelText('Catégorie de Paiement marchand')).toHaveValue('')
  })

  it('affiche les candidats à vérifier sans en appliquer un avant confirmation', async () => {
    listCategorizationReviews.mockResolvedValue({
      ...emptyReviewPage,
      items: [{
        transactionId: 'TX-DEBIT', bookingDate: '2026-08-19', description: 'Paiement marchand',
        direction: 'DEBIT', amount: 25, currency: 'TND', reviewStatus: 'NEEDS_REVIEW',
        reasonCode: 'MODEL_ABSTAINED', pendingSuggestion: null, manualCategorizationAllowed: true,
        candidateCategories: [
          { rank: 1, categoryId: 'CAT-FOOD', categoryCode: 'FOOD', categoryName: 'Alimentation et courses' },
          { rank: 2, categoryId: 'CAT-SHOPPING', categoryCode: 'SHOPPING', categoryName: 'Shopping' },
        ],
      }],
      totalElements: 1, totalPages: 1,
    })
    listCategories.mockResolvedValue([
      { id: 'CAT-FOOD', code: 'FOOD', name: 'Alimentation et courses', nature: 'EXPENSE', system: true },
      { id: 'CAT-CUSTOM', code: 'PERSONAL', name: 'Mes animaux', nature: 'EXPENSE', system: false },
    ])
    assignTransactionCategory.mockResolvedValue({
      transactionId: 'TX-DEBIT', categoryId: 'CAT-FOOD', categoryName: 'Alimentation et courses',
      categoryNature: 'EXPENSE', assignmentSource: 'MANUAL',
    })

    render(<TransactionsPage />)
    openReview()
    expect(await screen.findByText('À vérifier')).toBeInTheDocument()
    expect(screen.getAllByRole('option', { name: 'Mes animaux' }).length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: 'Alimentation et courses' }))
    expect(assignTransactionCategory).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Alimentation et courses' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer ce choix' }))
    await waitFor(() => expect(assignTransactionCategory).toHaveBeenCalledWith('TX-DEBIT', 'CAT-FOOD'))
    expect(screen.queryByText(/%|confiance/i)).not.toBeInTheDocument()
  })

  it('explique une exclusion tout en conservant le choix manuel', async () => {
    listCategorizationReviews.mockResolvedValue({
      ...emptyReviewPage,
      items: [{
        transactionId: 'TX-DEBIT', bookingDate: '2026-08-19', description: 'Paiement en euros',
        direction: 'DEBIT', amount: 25, currency: 'EUR', reviewStatus: 'EXCLUDED',
        reasonCode: 'UNSUPPORTED_CURRENCY', pendingSuggestion: null, candidateCategories: [],
        manualCategorizationAllowed: true,
      }],
      totalElements: 1, totalPages: 1,
    })

    render(<TransactionsPage />)
    openReview()
    expect(await screen.findByText('Analyse non disponible')).toBeInTheDocument()
    expect(screen.getByText(/prend actuellement en charge les débits en TND uniquement/)).toBeInTheDocument()
    expect(screen.getByLabelText('Choisir une catégorie pour Paiement en euros')).toBeInTheDocument()
  })

  it('affiche la mémoire privée, reconnaît sa suggestion et permet de l’oublier avec confirmation', async () => {
    const memorySuggestion = { ...suggestion, matchMethod: 'USER_MEMORY', source: 'USER_MEMORY' }
    listCategorizationReviews.mockResolvedValue({
      ...emptyReviewPage,
      items: [{
        transactionId: 'TX-DEBIT', bookingDate: '2026-08-19', description: 'Paiement marchand',
        direction: 'DEBIT', amount: 25, currency: 'TND', reviewStatus: 'SUGGESTION_PENDING',
        reasonCode: 'RELIABLE_SUGGESTION', pendingSuggestion: memorySuggestion,
        candidateCategories: [], manualCategorizationAllowed: true,
      }],
      totalElements: 1, totalPages: 1,
    })
    listMerchantMemories.mockResolvedValue([{
      id: 'MEMORY-1', normalizedMerchant: 'carrefour', direction: 'DEBIT', currency: 'TND',
      categoryId: 'CAT-FOOD', categoryName: 'Alimentation et courses',
    }])
    forgetMerchantMemory.mockResolvedValue(null)
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    render(<TransactionsPage />)
    openReview()
    fireEvent.click(screen.getByText('Choix marchands appris'))
    expect((await screen.findAllByText(/Appris de vos choix/)).length).toBeGreaterThan(0)
    expect(screen.getByText('carrefour')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Associations marchandes privées' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tableau des choix marchands appris' })).toHaveAttribute('tabindex', '0')
    fireEvent.click(screen.getByRole('button', { name: 'Oublier le choix pour carrefour' }))
    await waitFor(() => expect(forgetMerchantMemory).toHaveBeenCalledWith('MEMORY-1'))
    expect(window.confirm).toHaveBeenCalled()
    expect(screen.queryByText('carrefour')).not.toBeInTheDocument()
    expect(screen.queryByText(/%|confiance/i)).not.toBeInTheDocument()
  })

  it('distingue une erreur de revue d’un véritable état vide', async () => {
    listCategorizationReviews.mockRejectedValue(new Error('review unavailable'))

    render(<TransactionsPage />)

    expect(await screen.findByText(/transactions à vérifier n’ont pas pu être chargées/)).toBeInTheDocument()
    expect(screen.queryByText('Tout est à jour')).not.toBeInTheDocument()
  })

  it('explains abstention and timeout separately without claiming categories were applied', async () => {
    runMerchantRecognition.mockResolvedValue({ outcome: 'UNRECOGNIZED', suggestedCount: 0, reviewRequiredCount: 2 })
    listCategorizationReviews.mockResolvedValue({
      ...emptyReviewPage, totalElements: 2, totalPages: 1,
      items: ['MODEL_ABSTAINED', 'ENRICHMENT_UNAVAILABLE'].map((reasonCode, index) => ({
        ...transactionPage.items[0], transactionId: `TX-${index}`,
        reviewStatus: 'NEEDS_REVIEW', reasonCode, pendingSuggestion: null,
        candidateCategories: [], manualCategorizationAllowed: true,
      })),
    })
    render(<TransactionsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Analyser les transactions non catégorisées' }))
    expect(await screen.findByText(/2 opérations à vérifier/)).toBeInTheDocument()
    expect(document.getElementById('transactions-review')).toBeVisible()
    expect(await screen.findByText(/préfère vous laisser choisir/)).toBeVisible()
    expect(screen.getByText(/n’a pas pu terminer à temps/)).toBeVisible()
    expect(assignTransactionCategory).not.toHaveBeenCalled()
  })
})
