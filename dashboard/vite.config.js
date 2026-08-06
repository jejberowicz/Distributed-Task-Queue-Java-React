import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    // En dev el dashboard habla con el gateway a través del proxy, así que el
    // browser ve un solo origen y no hay que lidiar con CORS ni con el WS cruzado.
    proxy: {
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
      '/admin': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  }
})
