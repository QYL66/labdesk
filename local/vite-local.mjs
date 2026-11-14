import upstream from '../frontend/vite.config.ts';

export default {
  ...upstream,
  server: {
    ...upstream.server,
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    proxy: {
      '/api': { ...upstream.server.proxy['/api'], target: 'http://127.0.0.1:9090' },
    },
  },
};
