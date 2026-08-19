import BankConnectionSection from '../components/BankConnectionSection'
import { InlineNotice, PageHeader } from '../components/Ui'

export default function BankPage() {
  return (
    <div className="page">
      <PageHeader
        eyebrow="Connexion sécurisée"
        title="Ma banque"
        description="Synchronisez la banque simulée et consultez les données normalisées conservées par l’application."
      />

      <InlineNotice tone="success" icon="shield">
        <strong>Lecture seule.</strong> Mon Équilibre peut consulter vos comptes, soldes et transactions,
        mais ne peut effectuer ni paiement ni virement.
      </InlineNotice>

      <BankConnectionSection />
    </div>
  )
}
