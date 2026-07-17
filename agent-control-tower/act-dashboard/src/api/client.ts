import axios from 'axios';

const client = axios.create({
  baseURL: '',
  headers: { 'Content-Type': 'application/json' },
});

// Request interceptor — attach correlation ID to every request
client.interceptors.request.use(
  (config) => {
    config.headers['X-Correlation-ID'] = crypto.randomUUID();
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor for error handling
client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response) {
      const status = error.response.status;
      if (status === 401) {
        console.warn('[API] Unauthorized — redirect to login');
      } else if (status === 403) {
        console.warn('[API] Forbidden');
      } else if (status >= 500) {
        console.error('[API] Server error:', error.response.data);
      }
    } else if (error.request) {
      console.error('[API] No response received:', error.message);
    }
    return Promise.reject(error);
  }
);

export default client;
