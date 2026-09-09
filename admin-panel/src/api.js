import axios from 'axios';

const API = axios.create({
  baseURL: (import.meta.env.VITE_API_URL || '/api/').replace(/\/?$/, '/'),
  timeout: 30000,
});

export const fetchGlobalSettings = () => API.get('settings/global/');
export const updateGlobalSettings = (data) => API.patch('settings/global/', data);
export const fetchProfiles = () => API.get('profiles/');
export const generateDeviceSpecs = (query, provider = null, model = null) => 
  API.post('devices/generate/', { query, provider, model });
export const fetchAvailableModels = (provider) => 
  API.get(`devices/models/?provider=${encodeURIComponent(provider)}`);
export const importProfileCookies = (profileId, cookies) => 
  API.post(`profiles/${profileId}/cookies/import/`, { cookies });

export default API;
export const getToken = () => sessionStorage.getItem('terso_token');
export function clearSession() {
  sessionStorage.removeItem('terso_token');
  window.dispatchEvent(new Event('auth:expired'));
}
API.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Token ${token}`;
  return config;
});
API.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) clearSession();
  return Promise.reject(error);
});
export async function downloadCookies(profileId) {
  const response = await API.get(`profiles/${profileId}/cookies/export/?download=true`, { responseType: 'blob' });
  const url = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = `cookies_${profileId}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
