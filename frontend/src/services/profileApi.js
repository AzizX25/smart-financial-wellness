import { apiRequest } from './apiClient'

export function getProfile() {
  return apiRequest('/api/profile')
}

export function updateProfile(profile) {
  const { interfacePreferences, financialPreferences } = profile
  return apiRequest('/api/profile', {
    method: 'PUT',
    body: {
      firstName: profile.firstName,
      lastName: profile.lastName,
      displayName: profile.displayName,
      interfacePreferences: {
        languageTag: interfacePreferences.languageTag,
        timeZone: interfacePreferences.timeZone,
        theme: interfacePreferences.theme,
        textSize: interfacePreferences.textSize,
        highContrast: interfacePreferences.highContrast,
        reducedMotion: interfacePreferences.reducedMotion,
      },
      financialPreferences: {
        primaryCurrency: financialPreferences.primaryCurrency,
        monthStartDay: financialPreferences.monthStartDay,
        lowBalanceThreshold: financialPreferences.lowBalanceThreshold,
        hideHomeAmounts: financialPreferences.hideHomeAmounts,
      },
    },
  })
}
