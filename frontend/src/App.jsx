import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import ProtectedRoute from './auth/ProtectedRoute'
import AppShell from './components/AppShell'
import { FinancialDataProvider } from './financial/FinancialDataContext'
import BankPage from './pages/BankPage'
import CategoriesPage from './pages/CategoriesPage'
import DashboardPage from './pages/DashboardPage'
import ForecastPage from './pages/ForecastPage'
import GoalsPage from './pages/GoalsPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import SalaryPlanningPage from './pages/SalaryPlanningPage'
import SettingsPage from './pages/SettingsPage'
import TransactionsPage from './pages/TransactionsPage'
import VerificationPendingPage from './pages/VerificationPendingPage'
import VerifyEmailPage from './pages/VerifyEmailPage'

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/" element={<Navigate replace to="/connexion" />} />
        <Route path="/connexion" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email-sent" element={<VerificationPendingPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<FinancialDataProvider><AppShell /></FinancialDataProvider>}>
            <Route path="/tableau-de-bord" element={<DashboardPage />} />
            <Route path="/transactions" element={<TransactionsPage />} />
            <Route path="/categories" element={<CategoriesPage />} />
            <Route path="/connexion-bancaire" element={<BankPage />} />
            <Route path="/planification-salaire" element={<SalaryPlanningPage />} />
            <Route path="/objectifs" element={<GoalsPage />} />
            <Route path="/previsions" element={<ForecastPage />} />
            <Route path="/profil" element={<SettingsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate replace to="/connexion" />} />
      </Routes>
    </AuthProvider>
  )
}
