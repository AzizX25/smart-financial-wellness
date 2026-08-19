export const summary = {
  globalBalance: 6840.25,
  monthlyIncome: 3250,
  monthlyExpenses: 2115.45,
  savingsCapacity: 790,
  wellnessScore: 74,
  forecastEndBalance: 1680,
}

export const salaryPlan = {
  month: 'Juillet 2026',
  salary: 3250,
  fixedExpenses: 1540,
  variableExpenses: 920,
  savingsCapacity: 790,
  allocations: [
    { label: 'Dépenses essentielles', amount: 1625, percentage: 50, tone: 'teal' },
    { label: 'Dépenses flexibles', amount: 975, percentage: 30, tone: 'gold' },
    { label: 'Épargne et objectifs', amount: 650, percentage: 20, tone: 'coral' },
  ],
}

export const goals = [
  {
    id: 'emergency',
    name: 'Fonds de sécurité',
    icon: 'umbrella',
    current: 2700,
    target: 6000,
    monthlyAllocation: 300,
    targetDate: 'Juin 2027',
    tone: 'teal',
  },
  {
    id: 'tabarka',
    name: 'Vacances à Tabarka',
    icon: 'sun',
    current: 1840,
    target: 3200,
    monthlyAllocation: 250,
    targetDate: 'Janvier 2027',
    tone: 'coral',
  },
  {
    id: 'laptop',
    name: 'Nouvel ordinateur',
    icon: 'laptop',
    current: 1120,
    target: 2800,
    monthlyAllocation: 180,
    targetDate: 'Mai 2027',
    tone: 'gold',
  },
]

export const goalScenarios = [
  { name: 'Tranquille', monthly: 150, targetDate: 'Avril 2027', effort: 'Léger' },
  { name: 'Équilibré', monthly: 250, targetDate: 'Janvier 2027', effort: 'Adapté' },
  { name: 'Accéléré', monthly: 350, targetDate: 'Novembre 2026', effort: 'Soutenu' },
]

export const forecast = {
  confidence: 82,
  savedEndBalance: 1680,
  referenceDate: '22 juillet 2026',
  values: [4890, 4620, 4310, 3920, 3340, 2510, 1680],
  labels: ['1 juil.', '6 juil.', '11 juil.', '16 juil.', '21 juil.', '26 juil.', '31 juil.'],
}

export const recommendations = [
  {
    id: 'rec-1',
    title: 'Gardez un coussin pour la fin du mois',
    message: 'Réserver 300 TND maintenant vous aiderait à absorber les dépenses imprévues.',
    action: 'Voir ma prévision',
    tone: 'teal',
  },
  {
    id: 'rec-2',
    title: 'Votre objectif vacances avance bien',
    message: 'Avec 250 TND par mois, il pourrait être atteint en janvier 2027.',
    action: 'Comparer les scénarios',
    tone: 'coral',
  },
  {
    id: 'rec-3',
    title: 'Les dépenses alimentaires restent stables',
    message: 'Elles représentent 12 % de vos dépenses ce mois-ci, un niveau proche de votre plan.',
    action: 'Voir les transactions',
    tone: 'gold',
  },
]

export const wellnessDetails = [
  { label: 'Équilibre du budget', score: 82, note: 'Vos dépenses restent sous vos revenus.' },
  { label: 'Épargne de précaution', score: 61, note: 'Votre réserve couvre environ 1,7 mois.' },
  { label: 'Régularité', score: 77, note: 'Vos dépenses sont plutôt prévisibles.' },
]

export const consentSettings = [
  { label: 'Analyse de mes transactions', status: 'Accordé', detail: 'Version du 12 juin 2026' },
  { label: 'Recommandations personnalisées', status: 'Accordé', detail: 'Version du 12 juin 2026' },
  { label: 'Notifications de vigilance', status: 'Accordé', detail: 'Modifiable à tout moment' },
]
