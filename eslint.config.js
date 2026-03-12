import antfu from '@antfu/eslint-config'

ignores: [
  'node_modules',
  'dist',
  'android',
  'ios',
  'coverage',
  '*.min.js',
]

export default antfu()
