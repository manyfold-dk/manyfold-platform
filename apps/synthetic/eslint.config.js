import js from '@eslint/js'
import prettierConfig from 'eslint-config-prettier/flat'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['node_modules/', '.wrangler/', 'test-results/'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  prettierConfig,
)
