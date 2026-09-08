import axios from 'axios';

const API = axios.create({
  baseURL: 'http://localhost:8000/api/',
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
