// Flat-config ESLint 9 for the TS surface (renderer + electron main + build scripts).
// The core/ JVM build is Gradle/Java and out of scope; it is ignored below.
//
// Deliberately NOT recommendedTypeChecked: the three tsconfigs here diverge (renderer
// DOM lib, electron node16 module, test) and type-aware rules would flag every narrowing
// `as` cast in src/api/client.ts (hand-kept wire types). We run the syntactic recommended
// set only; the full typecheck lives in `npm --prefix app run typecheck`.
import js from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import eslintConfigPrettier from 'eslint-config-prettier';
import globals from 'globals';

export default js.config(
  {
    // Generated output, vendored binaries, the Java core, and declaration files never lint.
    ignores: [
      '**/dist/**',
      '**/electron-dist/**',
      'app/dist/**',
      'core/**',
      'build-resources/**',
      'node_modules/**',
      '**/*.d.ts',
      'release/**',
      'logs/**',
    ],
  },
  // Base recommended set for every TS/TSX file. no-explicit-any stays a warning (the wire
  // types in client.ts narrow JSON with a few casts); unused args prefixed `_` are allowed.
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended],
    rules: {
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  // Renderer: browser globals, React hooks + Fast Refresh rules. exhaustive-deps stays a
  // warning by design — three mount-only useEffect(..., []) in RecordingSettings.tsx are
  // intentional one-shot loads, not stale-closure bugs.
  {
    files: ['app/src/**/*.{ts,tsx}'],
    languageOptions: {
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs['recommended-latest'].rules,
      ...reactRefresh.configs.vite.rules,
    },
  },
  // Node context: electron main process, build scripts, and the vite/vitest configs.
  {
    files: ['app/electron/**/*.ts', 'scripts/**/*.mjs', 'app/*.config.ts'],
    languageOptions: {
      globals: globals.node,
    },
  },
  // Test files reach into internals: non-null assertions and `any` are fine in a test.
  {
    files: ['**/*.test.{ts,tsx}'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  // Must be LAST: turns off every rule that would collide with Prettier's formatting.
  eslintConfigPrettier,
);
