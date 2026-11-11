import { defineConfig } from 'vite';

export default defineConfig({
  build: { outDir: 'dist', sourcemap: false },
  test: { environment: 'node' },
});
