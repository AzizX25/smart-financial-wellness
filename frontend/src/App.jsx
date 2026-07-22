import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell from './components/AppShell'
import BankPage from './pages/BankPage'
import DashboardPage from './pages/DashboardPage'
import ForecastPage from './pages/ForecastPage'
import GoalsPage from './pages/GoalsPage'
import LoginPage from './pages/LoginPage'
import MockBankPage from './pages/MockBankPage'
import SalaryPlanningPage from './pages/SalaryPlanningPage'
import SettingsPage from './pages/SettingsPage'
import TransactionsPage from './pages/TransactionsPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate replace to="/connexion" />} />
      <Route path="/connexion" element={<LoginPage />} />
      <Route path="/banque-demo" element={<MockBankPage />} />
      <Route element={<AppShell />}>
        <Route path="/tableau-de-bord" element={<DashboardPage />} />
        <Route path="/transactions" element={<TransactionsPage />} />
        <Route path="/connexion-bancaire" element={<BankPage />} />
        <Route path="/planification-salaire" element={<SalaryPlanningPage />} />
        <Route path="/objectifs" element={<GoalsPage />} />
        <Route path="/previsions" element={<ForecastPage />} />
        <Route path="/profil" element={<SettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate replace to="/connexion" />} />
    </Routes>
  )
}
