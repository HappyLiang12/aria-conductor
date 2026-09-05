import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import {
  clearApiToken,
  getApiToken,
  requestApiTokenFromOperator,
  setApiToken,
} from './auth';

const client = axios.create({
  baseURL: '',
  headers: { 'Content-Type': 'application/json' },
});

interface AuthRetriableConfig extends InternalAxiosRequestConfig {
  _authRetried?: boolean;
}

// Request interceptor — attach correlation ID and the operator API token (if set) to every request
client.interceptors.request.use(
  (config) => {
    config.headers['X-Correlation-ID'] = crypto.randomUUID();
    const token = getApiToken();
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor for error handling
client.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response) {
      const status = error.response.status;
      if (status === 401) {
        const config = error.config as AuthRetriableConfig | undefined;
        if (config && !config._authRetried) {
          // Surface a "set API token" prompt; retry transparently once the operator enters one.
          const token = await requestApiTokenFromOperator();
          if (token) {
            setApiToken(token);
            config.headers['Authorization'] = `Bearer ${token}`;
            config._authRetried = true;
            return client.request(config);
          }
        } else if (config?._authRetried) {
          // The retried request still failed — the supplied token is wrong; drop it so the
          // next request re-prompts instead of silently retrying a bad credential.
          clearApiToken();
        }
        console.warn('[API] Unauthorized');
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
