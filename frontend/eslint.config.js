import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

export default [
  { ignores: ['dist'] },
  js.configs.recommended,
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { ecmaVersion: 'latest', ecmaFeatures: { jsx: true }, sourceType: 'module' },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs['recommended-latest'].rules,
      ...reactRefresh.configs.vite.rules,
      'no-unused-vars': ['error', {
        argsIgnorePattern: '^[A-Z_]',
        varsIgnorePattern: '^[A-Z_]',
      }],
    },
  },
  {
    files: ['src/auth/AuthContext.jsx', 'src/components/Ui.jsx', 'src/financial/FinancialDataContext.jsx'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
]
