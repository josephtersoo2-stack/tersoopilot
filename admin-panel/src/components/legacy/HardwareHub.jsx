import React, { useState } from 'react';
import { 
  Sliders, 
  VolumeX, 
  Volume2, 
  Tv, 
  Sparkles, 
  Smartphone, 
  RefreshCw, 
  CheckCircle2, 
  Radio, 
  Search, 
  Save, 
  BookmarkCheck, 
  FileText, 
  RotateCcw, 
  Code2, 
  Globe, 
  Plus 
} from 'lucide-react';
import { 
  updateGlobalSettings, 
  generateDeviceSpecs, 
  fetchAvailableModels, 
  fetchProfiles,
  importProfileCookies 
} from '../../api';

export default function HardwareHub({
  settings,
  setSettings,
  profiles,
  setProfiles,
  loadDashboardData,
  showNotification
}) {
  const [aiPrompt, setAiPrompt] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [generatedResult, setGeneratedResult] = useState(null);
  const [provider, setProvider] = useState(settings.selected_ai_provider || 'openrouter');
  const [availableModels, setAvailableModels] = useState([]);
  const [selectedModel, setSelectedModel] = useState(settings.selected_ai_model || '');
  const [providerNotice, setProviderNotice] = useState('');
  const [modelSearch, setModelSearch] = useState('');
  const [isSavingModel, setIsSavingModel] = useState(false);
  const [isLoadingModels, setIsLoadingModels] = useState(false);
  const [customPrompt, setCustomPrompt] = useState(settings.ai_generation_prompt || '');
  const [isSavingPrompt, setIsSavingPrompt] = useState(false);
  const [showPromptEditor, setShowPromptEditor] = useState(false);
  const [targetSearchSites, setTargetSearchSites] = useState(
    settings.target_search_sites || "gsmarena.com\ndevicespecifications.com\nphonearena.com\nkimovil.com\nnanoreview.net"
  );
  const [isSavingSites, setIsSavingSites] = useState(false);
  const [showSitesEditor, setShowSitesEditor] = useState(false);

  // Cookie Import/Export state
  const [importJsonText, setImportJsonText] = useState('');
  const [activeProfileForImport, setActiveProfileForImport] = useState(null);
  const [isSubmittingImport, setIsSubmittingImport] = useState(false);

  const handleDownloadCookies = (profileId) => {
    window.open(`http://localhost:8000/api/profiles/${profileId}/cookies/export/?download=true`, '_blank');
  };

  const handleImportSubmit = async () => {
    if (!activeProfileForImport || !importJsonText.trim()) return;
    setIsSubmittingImport(true);
    try {
      let cookiesPayload = [];
      const trimmed = importJsonText.trim();
      if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        cookiesPayload = JSON.parse(trimmed);
      } else {
        trimmed.split('\n').forEach(line => {
          const l = line.trim();
          if (l && !l.startsWith('#')) {
            const parts = l.split('\t');
            if (parts.length >= 7) {
              cookiesPayload.push({
                domain: parts[0],
                path: parts[2],
                isSecure: parts[3].toUpperCase() === 'TRUE',
                expiry: parseInt(parts[4]) || Math.floor(Date.now() / 1000) + 31536000,
                name: parts[5],
                value: parts[6]
              });
            }
          }
        });
      }

      if (!Array.isArray(cookiesPayload) || cookiesPayload.length === 0) {
        showNotification('No valid cookies detected. Provide a JSON array or Netscape lines.', 'error');
        setIsSubmittingImport(false);
        return;
      }

      await importProfileCookies(activeProfileForImport.id, cookiesPayload);
      showNotification(`Successfully imported ${cookiesPayload.length} cookies into ${activeProfileForImport.name}!`);
      setImportJsonText('');
      setActiveProfileForImport(null);
      const profRes = await fetchProfiles();
      setProfiles(profRes.data);
    } catch (err) {
      console.error(err);
      showNotification(`Import failed: ${err.response?.data ? JSON.stringify(err.response.data) : err.message}`, 'error');
    } finally {
      setIsSubmittingImport(false);
    }
  };

  const handleSwitchProvider = (newProv) => {
    setProvider(newProv);
    setModelSearch('');
    const targetModel = newProv === 'gemini' ? settings.saved_gemini_model : settings.saved_openrouter_model;
    loadModels(newProv, targetModel);
  };

  const loadModels = async (prov, targetModel = null) => {
    setIsLoadingModels(true);
    setProviderNotice('');
    try {
      const res = await fetchAvailableModels(prov);
      const list = res.data.models || [];
      setAvailableModels(list);
      if (res.data.notice) {
        setProviderNotice(res.data.notice);
      }
      if (list.length > 0) {
        const targetSaved = targetModel || (prov === 'gemini' ? settings.saved_gemini_model : settings.saved_openrouter_model);
        const exists = list.some(m => m.id === targetSaved);
        setSelectedModel(exists ? targetSaved : list[0].id);
      } else {
        setSelectedModel('');
      }
    } catch (err) {
      console.error(err);
      showNotification(`Failed to load ${prov} models from backend`, 'error');
    } finally {
      setIsLoadingModels(false);
    }
  };

  const handleSaveAiModel = async () => {
    if (!selectedModel) return;
    setIsSavingModel(true);
    try {
      const payload = {
        selected_ai_provider: provider,
        selected_ai_model: selectedModel,
        ...(provider === 'gemini' ? { saved_gemini_model: selectedModel } : { saved_openrouter_model: selectedModel })
      };
      const res = await updateGlobalSettings(payload);
      setSettings(res.data);
      showNotification(`Saved backend model for ${provider === 'gemini' ? 'Google Gemini' : 'OpenRouter'}: ${selectedModel}`);
    } catch (err) {
      showNotification('Failed to save AI model preference', 'error');
    } finally {
      setIsSavingModel(false);
    }
  };

  const handleSettingChange = async (updates) => {
    try {
      const res = await updateGlobalSettings(updates);
      setSettings(res.data);
      showNotification('Global settings updated and synced to Android fleet.');
    } catch (err) {
      showNotification('Failed to update settings', 'error');
    }
  };

  const handleSaveSearchSites = async () => {
    setIsSavingSites(true);
    try {
      const res = await updateGlobalSettings({ target_search_sites: targetSearchSites });
      setSettings(res.data);
      showNotification('Authoritative Spec Search Sources saved and live-grounding activated.');
    } catch (err) {
      showNotification('Failed to save search sources', 'error');
    } finally {
      setIsSavingSites(false);
    }
  };

  const handleResetSearchSites = () => {
    const defaultSites = "gsmarena.com\ndevicespecifications.com\nphonearena.com\nkimovil.com\nnanoreview.net";
    setTargetSearchSites(defaultSites);
  };

  const handleAddSitePreset = (domain) => {
    const currentList = targetSearchSites.split('\n').map(s => s.trim()).filter(Boolean);
    if (!currentList.includes(domain)) {
      const updated = [...currentList, domain].join('\n');
      setTargetSearchSites(updated);
    }
  };

  const handleSavePrompt = async () => {
    setIsSavingPrompt(true);
    try {
      const res = await updateGlobalSettings({ ai_generation_prompt: customPrompt });
      setSettings(res.data);
      showNotification('AI Generation Prompt saved to Django backend.');
    } catch (err) {
      showNotification('Failed to save AI prompt', 'error');
    } finally {
      setIsSavingPrompt(false);
    }
  };

  const handleResetPrompt = () => {
    const defaultPrompt = `You are an expert mobile hardware and anti-detect browser engineer.
Your task is to generate verified hardware and browser fingerprint specifications for the requested mobile device.
LIVE ONLINE SEARCH PRIORITY: The backend automatically performs a live web search for the device specifications and passes real-time ground truth snippets. You MUST prioritize the live web search findings over your internal model training data cutoff. Use the exact SoC (chipset), GPU renderer, Android version, RAM, and screen specs from the live search results.
CRITICAL RULE FOR MODEL NAME: You must ALWAYS preserve the user's requested model in 'model_name' (e.g. if the user requests 'itel s26 ultra', output 'S26 Ultra', NEVER downgrade or rename it to an older model like S25). If the requested device is unreleased or concept, synthesize plausible, authentic next-generation hardware specifications consistent with the brand's hardware lineage.
You must respond ONLY with a raw JSON object containing these exact fields:
- brand (string, e.g. Samsung, Google, OnePlus, Xiaomi, itel)
- model_name (string, matching the user's requested model without repeating brand)
- model_code (string, authentic manufacturer code e.g. SM-S928B, CPH2581, S696LN)
- android_version (integer, 13, 14 or 15)
- soc (string, e.g. Snapdragon 8 Gen 3, Unisoc T7300, Dimensity 9300)
- webgl_vendor (string: 'Qualcomm' for Adreno, 'ARM' for Mali)
- webgl_renderer (string, e.g. 'Adreno (TM) 750', 'Mali-G57 MP2', 'Mali-G715')
- ram_gb (integer, e.g. 8, 12, 16)
- cpu_cores (integer, typically 8)
- screen_width (integer, CSS viewport width e.g. 360, 384, 412)
- screen_height (integer, CSS viewport height e.g. 800, 854, 915)
- dpr (float, device pixel ratio e.g. 2.625, 2.75, 3.0)
- user_agent (string, authentic Mobile Firefox: 'Mozilla/5.0 (Android {android_version}; Mobile; rv:135.0) Gecko/135.0 Firefox/135.0')
Output valid JSON only. No markdown formatting, no code blocks, no other text.`;
    setCustomPrompt(defaultPrompt);
  };

  const handleGenerate = async () => {
    if (!aiPrompt.trim()) return;
    setIsGenerating(true);
    setGeneratedResult(null);
    try {
      const res = await generateDeviceSpecs(aiPrompt.trim(), provider, selectedModel || null);
      setGeneratedResult(res.data);
      showNotification(`Generated blueprint: ${res.data.brand} ${res.data.model_name}`);
      setAiPrompt('');
      const profRes = await fetchProfiles();
      setProfiles(profRes.data);
    } catch (e) {
      showNotification('Device specification generation failed', 'error');
    } finally {
      setIsGenerating(false);
    }
  };

  return (
    <div className="space-y-8">
      {/* Top Metric Cards Banner */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-[#111520] border border-[#232A3E] rounded-2xl p-5 relative overflow-hidden">
          <div className="flex justify-between items-start">
            <div>
              <span className="text-xs font-medium text-neutral-400">Concurrency Limit</span>
              <h3 className="text-2xl font-bold text-white mt-1">{settings.max_active_profiles} / 10</h3>
            </div>
            <div className="p-2.5 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
              <Sliders className="w-5 h-5" />
            </div>
          </div>
          <p className="text-[11px] text-neutral-400 mt-3">Dynamic GeckoSession pool threshold</p>
        </div>

        <div className="bg-[#111520] border border-[#232A3E] rounded-2xl p-5 relative overflow-hidden">
          <div className="flex justify-between items-start">
            <div>
              <span className="text-xs font-medium text-neutral-400">Hardware Audio Lock</span>
              <h3 className="text-2xl font-bold text-white mt-1">
                {settings.force_global_mute ? 'Mute Active' : 'Unmuted'}
              </h3>
            </div>
            <div className={`p-2.5 rounded-xl border ${
              settings.force_global_mute 
                ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400' 
                : 'bg-amber-500/10 border-amber-500/20 text-amber-400'
            }`}>
              {settings.force_global_mute ? <VolumeX className="w-5 h-5" /> : <Volume2 className="w-5 h-5" />}
            </div>
          </div>
          <p className="text-[11px] text-neutral-400 mt-3">media.volume_scale + Prototype Lock</p>
        </div>

        <div className="bg-[#111520] border border-[#232A3E] rounded-2xl p-5 relative overflow-hidden">
          <div className="flex justify-between items-start">
            <div>
              <span className="text-xs font-medium text-neutral-400">Video Resolution</span>
              <h3 className="text-2xl font-bold text-white mt-1">{settings.default_video_resolution}</h3>
            </div>
            <div className="p-2.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-purple-400">
              <Tv className="w-5 h-5" />
            </div>
          </div>
          <p className="text-[11px] text-neutral-400 mt-3">Clamped to preserve MediaCodec decoders</p>
        </div>

        <div className="bg-[#111520] border border-[#232A3E] rounded-2xl p-5 relative overflow-hidden">
          <div className="flex justify-between items-start">
            <div>
              <span className="text-xs font-medium text-neutral-400">Fleet Profiles</span>
              <h3 className="text-2xl font-bold text-white mt-1">{profiles.length}</h3>
            </div>
            <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
              <Smartphone className="w-5 h-5" />
            </div>
          </div>
          <p className="text-[11px] text-neutral-400 mt-3">Sandboxed hardware identities saved</p>
        </div>
      </div>

      {/* 2-Column Controls & AI Blueprint Section */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 items-start">
        
        {/* Left Column: Runtime Controls & Spec Search Sites */}
        <div className="space-y-8">
          {/* Section 1: Global Runtime Controls */}
          <section className="bg-[#111520] border border-[#232A3E] rounded-2xl p-6 shadow-xl space-y-6">
            <div className="flex items-center gap-3 pb-4 border-b border-[#232A3E]">
              <div className="p-2 bg-blue-500/10 rounded-lg text-blue-400">
                <Sliders className="w-5 h-5" />
              </div>
              <div>
                <h2 className="text-base font-semibold text-white">Runtime Constraints & Concurrency</h2>
                <p className="text-xs text-neutral-400">Orchestrate browser engine parameters across all mobile devices</p>
              </div>
            </div>

            {/* Concurrency Slider */}
            <div className="bg-[#181E2E] border border-[#232A3E] rounded-xl p-4 space-y-3">
              <div className="flex justify-between items-center">
                <label className="text-sm font-medium text-neutral-200">
                  Max Active Profiles Concurrency
                </label>
                <span className="text-sm font-bold text-blue-400 bg-blue-500/10 px-2.5 py-0.5 rounded border border-blue-500/20">
                  {settings.max_active_profiles} / 10 Active
                </span>
              </div>
              <input
                type="range"
                min="1"
                max="10"
                value={settings.max_active_profiles}
                onChange={(e) => handleSettingChange({ max_active_profiles: parseInt(e.target.value) })}
                className="w-full h-2 bg-[#232A3E] rounded-lg appearance-none cursor-pointer accent-blue-500"
              />
              <div className="flex justify-between text-[11px] text-neutral-400">
                <span>1 (Single)</span>
                <span>5 (Standard)</span>
                <span>10 (Max Concurrency)</span>
              </div>
              <p className="text-xs text-neutral-400">
                Prevents Android memory thrashing by throttling concurrent background GeckoSessions.
              </p>
            </div>

            {/* Global Mute Toggle */}
            <div className="bg-[#181E2E] border border-[#232A3E] rounded-xl p-4 flex items-center justify-between">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-white">Force Global Mute</span>
                  <span className="text-[10px] bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-semibold px-2 py-0.2 rounded">
                    Bypass-Proof
                  </span>
                </div>
                <p className="text-xs text-neutral-400">
                  Hardware-level audio sink silence (`media.volume_scale=0.0`) + Prototype getter/setter lock.
                </p>
              </div>
              <label className="relative inline-flex items-center cursor-pointer">
                <input
                  type="checkbox"
                  checked={settings.force_global_mute}
                  onChange={(e) => handleSettingChange({ force_global_mute: e.target.checked })}
                  className="sr-only peer"
                />
                <div className="w-11 h-6 bg-[#232A3E] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-neutral-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-blue-600"></div>
              </label>
            </div>

            {/* Video Resolution Select */}
            <div className="bg-[#181E2E] border border-[#232A3E] rounded-xl p-4 flex items-center justify-between">
              <div className="space-y-1">
                <span className="text-sm font-medium text-white">Enforce Video Stream Resolution</span>
                <p className="text-xs text-neutral-400">
                  Clamps YouTube API quality to tiny (240p/144p) to preserve hardware MediaCodec decoders.
                </p>
              </div>
              <select
                value={settings.default_video_resolution}
                onChange={(e) => handleSettingChange({ default_video_resolution: e.target.value })}
                className="bg-[#111520] border border-[#232A3E] text-xs font-semibold rounded-lg px-3 py-2 text-white focus:outline-none focus:border-blue-500 cursor-pointer"
              >
                <option value="240p">240p (Optimal)</option>
                <option value="144p">144p (Ultra-Light)</option>
                <option value="360p">360p (Higher CPU)</option>
              </select>
            </div>
          </section>

          {/* Section 1.5: Authoritative Hardware Spec Search Sources */}
          <section className="bg-[#111520] border border-[#232A3E] rounded-2xl p-6 shadow-xl space-y-5">
            <div className="flex items-center justify-between pb-4 border-b border-[#232A3E]">
              <div className="flex items-center gap-3">
                <div className="p-2 bg-emerald-500/10 rounded-lg text-emerald-400">
                  <Globe className="w-5 h-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h2 className="text-base font-semibold text-white">Authoritative Spec Search Sources</h2>
                    <span className="text-[10px] bg-emerald-500/10 text-emerald-300 border border-emerald-500/20 px-1.5 py-0.2 rounded font-mono font-semibold">
                      Real-World Grounding
                    </span>
                  </div>
                  <p className="text-xs text-neutral-400">
                    Sites searched live when creating any device profile to eliminate AI hallucinations
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setShowSitesEditor(!showSitesEditor)}
                className="text-xs text-emerald-400 hover:text-emerald-300 font-medium transition cursor-pointer flex items-center gap-1"
              >
                <Code2 className="w-3.5 h-3.5" />
                {showSitesEditor ? 'Collapse' : 'Edit Sources'}
              </button>
            </div>

            {showSitesEditor ? (
              <div className="space-y-3.5">
                <div className="p-3 bg-[#181E2E] border border-[#232A3E] rounded-xl text-xs text-neutral-300 space-y-2">
                  <div className="flex items-center gap-2 text-emerald-400 font-semibold text-xs">
                    <CheckCircle2 className="w-4 h-4" />
                    <span>Live Search Priority Enabled</span>
                  </div>
                  <p className="text-[11px] text-neutral-400 leading-relaxed">
                    Whenever a device profile is requested via this dashboard or the Android app, the backend searches these exact domains for authentic specifications.
                  </p>
                </div>

                <div>
                  <label className="text-xs font-medium text-neutral-300 block mb-1.5">
                    Quick Add Trusted Specification Sources:
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      { label: 'GSMArena', domain: 'gsmarena.com' },
                      { label: 'DeviceSpecifications', domain: 'devicespecifications.com' },
                      { label: 'PhoneArena', domain: 'phonearena.com' },
                      { label: 'Kimovil', domain: 'kimovil.com' },
                      { label: 'NanoReview', domain: 'nanoreview.net' },
                      { label: 'itel Official', domain: 'itel-life.com' },
                      { label: '91mobiles', domain: '91mobiles.com' },
                    ].map((preset) => {
                      const isAdded = targetSearchSites.split('\n').some(l => l.trim().toLowerCase() === preset.domain.toLowerCase());
                      return (
                        <button
                          key={preset.domain}
                          type="button"
                          onClick={() => handleAddSitePreset(preset.domain)}
                          disabled={isAdded}
                          className={`px-2.5 py-1 text-[11px] font-medium rounded-lg border transition cursor-pointer flex items-center gap-1 ${
                            isAdded
                              ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300 cursor-default opacity-80'
                              : 'bg-[#181E2E] hover:bg-[#232A3E] border-[#232A3E] text-neutral-300 hover:text-white'
                          }`}
                        >
                          {isAdded ? <CheckCircle2 className="w-3 h-3" /> : <Plus className="w-3 h-3 text-neutral-400" />}
                          {preset.label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div>
                  <div className="flex justify-between items-center mb-1.5">
                    <label className="text-xs font-medium text-neutral-300">
                      Authoritative Target Domains (One domain or URL per line):
                    </label>
                    <span className="text-[10px] text-neutral-400 font-mono">
                      {targetSearchSites.split('\n').filter(s => s.trim()).length} sources listed
                    </span>
                  </div>
                  <textarea
                    value={targetSearchSites}
                    onChange={(e) => setTargetSearchSites(e.target.value)}
                    rows={5}
                    className="w-full bg-[#181E2E] border border-[#232A3E] rounded-xl p-3 text-xs font-mono text-emerald-300 focus:outline-none focus:border-emerald-500 leading-relaxed resize-y selection:bg-emerald-600"
                    placeholder="gsmarena.com&#10;devicespecifications.com&#10;phonearena.com..."
                  />
                </div>

                <div className="flex flex-col sm:flex-row gap-2 items-center justify-between text-[11px] text-neutral-400 pt-1 border-t border-[#232A3E]/60">
                  <span>Synchronized with Django <code>/api/settings/global/</code> & Android app</span>
                  <div className="flex items-center gap-2 self-end">
                    <button
                      type="button"
                      onClick={handleResetSearchSites}
                      className="px-2.5 py-1.5 rounded-lg bg-[#181E2E] hover:bg-[#232A3E] text-neutral-400 hover:text-white border border-[#232A3E] transition cursor-pointer flex items-center gap-1 text-xs"
                    >
                      <RotateCcw className="w-3 h-3" />
                      Reset Defaults
                    </button>
                    <button
                      type="button"
                      onClick={handleSaveSearchSites}
                      disabled={isSavingSites || !targetSearchSites.trim()}
                      className="px-3.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition cursor-pointer flex items-center gap-1.5 shadow-sm shadow-emerald-600/30 disabled:opacity-50 text-xs"
                    >
                      {isSavingSites ? (
                        <>
                          <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                          Saving...
                        </>
                      ) : (
                        <>
                          <Save className="w-3.5 h-3.5" />
                          Save Search Sources
                        </>
                      )}
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="text-[11px] text-neutral-400 flex items-center justify-between">
                <span className="truncate max-w-[340px] text-emerald-400 font-mono text-[10px]">
                  {targetSearchSites.split('\n').filter(s => s.trim()).join(', ')}
                </span>
                <span className="text-neutral-500 text-[10px]">
                  Click 'Edit Sources' to modify
                </span>
              </div>
            )}
          </section>
        </div>

        {/* Section 2: AI Device Blueprint Generator */}
        <section className="bg-[#111520] border border-[#232A3E] rounded-2xl p-6 shadow-xl space-y-6">
          <div className="flex items-center gap-3 pb-4 border-b border-[#232A3E]">
            <div className="p-2 bg-indigo-500/10 rounded-lg text-indigo-400">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-white">Dynamic AI Hardware Blueprint Generator</h2>
              <p className="text-xs text-neutral-400">Direct provider integration without hardcoded models</p>
            </div>
          </div>

          {/* Provider and Dynamic Model Picker */}
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-medium text-neutral-300 mb-1.5 block">AI Provider</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('openrouter')}
                    className={`flex-1 py-2 px-2 text-xs font-medium rounded-lg border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'openrouter'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm shadow-blue-500/30'
                        : 'bg-[#181E2E] text-neutral-400 border-[#232A3E] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5">
                      <Radio className="w-3.5 h-3.5" />
                      <span>OpenRouter.ai</span>
                    </div>
                    <span className="text-[10px] opacity-75 truncate max-w-[130px]">
                      Saved: {settings.saved_openrouter_model || 'google/gemini-3.8-flash'}
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('gemini')}
                    className={`flex-1 py-2 px-2 text-xs font-medium rounded-lg border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'gemini'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm shadow-blue-500/30'
                        : 'bg-[#181E2E] text-neutral-400 border-[#232A3E] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5">
                      <Sparkles className="w-3.5 h-3.5" />
                      <span>Gemini API</span>
                    </div>
                    <span className="text-[10px] opacity-75 truncate max-w-[130px]">
                      Saved: {settings.saved_gemini_model || 'gemini-flash-latest'}
                    </span>
                  </button>
                </div>
              </div>

              <div>
                <div className="flex justify-between items-center mb-1.5">
                  <label className="text-xs font-medium text-neutral-300">
                    Search Models ({availableModels.length} {provider === 'gemini' ? 'Gemini' : 'OpenRouter'})
                  </label>
                  <div className="flex items-center gap-1.5">
                    {settings.selected_ai_model === selectedModel && settings.selected_ai_provider === provider && (
                      <span className="text-[10px] bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-semibold px-2 py-0.2 rounded flex items-center gap-1">
                        <BookmarkCheck className="w-3 h-3" /> Saved Default
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={() => loadModels(provider)}
                      disabled={isLoadingModels}
                      className="p-1 rounded bg-[#181E2E] hover:bg-[#232A3E] text-neutral-400 hover:text-white border border-[#232A3E] transition cursor-pointer"
                      title="Refresh models from provider"
                    >
                      <RefreshCw className={`w-3 h-3 ${isLoadingModels ? 'animate-spin text-blue-400' : ''}`} />
                    </button>
                  </div>
                </div>
                <div className="relative">
                  <input
                    type="text"
                    placeholder="Filter models (e.g. gemini, claude, llama, gpt)..."
                    value={modelSearch}
                    onChange={(e) => setModelSearch(e.target.value)}
                    className="w-full bg-[#181E2E] border border-[#232A3E] text-xs rounded-lg pl-8 pr-3 py-2 text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500"
                  />
                  <Search className="w-3.5 h-3.5 text-neutral-500 absolute left-2.5 top-2.5" />
                </div>
              </div>
            </div>

            {/* Provider Notice & API Key Status */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-[#181E2E]/60 border border-[#232A3E] rounded-lg text-[11px]">
              <div className="flex items-center gap-1.5 text-neutral-400">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span>
                  API Key: <strong className="text-emerald-400 font-mono">Configured in Django</strong>
                </span>
                <span className="text-neutral-600">•</span>
                <span>{availableModels.length} models ready</span>
              </div>
              {providerNotice && (
                <span className="text-amber-400/90 text-[10px] truncate max-w-[280px]">
                  {providerNotice}
                </span>
              )}
            </div>

            {/* Model Dropdown & Save Preference */}
            <div className="bg-[#181E2E] border border-[#232A3E] rounded-xl p-3.5 space-y-3">
              <div className="flex flex-col sm:flex-row gap-2.5 items-center justify-between">
                <div className="w-full sm:flex-1">
                  <label className="text-[11px] font-medium text-neutral-400 mb-1 block">
                    Choose Active Model for Device Generation ({provider === 'gemini' ? 'Google Gemini' : 'OpenRouter'})
                  </label>
                  <select
                    value={selectedModel}
                    disabled={isLoadingModels || availableModels.length === 0}
                    onChange={(e) => setSelectedModel(e.target.value)}
                    className="w-full bg-[#111520] border border-[#232A3E] text-xs font-medium rounded-lg px-3 py-2 text-white focus:outline-none focus:border-blue-500 disabled:opacity-50 cursor-pointer"
                  >
                    {isLoadingModels ? (
                      <option>Loading models directly from {provider === 'gemini' ? 'Google Gemini' : 'OpenRouter'}...</option>
                    ) : availableModels.length === 0 ? (
                      <option>No models available from provider</option>
                    ) : (
                      availableModels
                        .filter((m) =>
                          !modelSearch ||
                          (m.name && m.name.toLowerCase().includes(modelSearch.toLowerCase())) ||
                          (m.id && m.id.toLowerCase().includes(modelSearch.toLowerCase()))
                        )
                        .map((m) => (
                          <option key={m.id} value={m.id}>
                            {m.name || m.id} {m.context_length ? `(${Math.round(m.context_length / 1000)}k ctx)` : ''}
                          </option>
                        ))
                    )}
                  </select>
                </div>

                <button
                  type="button"
                  onClick={handleSaveAiModel}
                  disabled={isSavingModel || !selectedModel}
                  className="w-full sm:w-auto mt-auto self-end bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold px-4 py-2 rounded-lg transition disabled:opacity-50 flex items-center justify-center gap-1.5 cursor-pointer shadow-sm shadow-emerald-600/20"
                >
                  {isSavingModel ? (
                    <>
                      <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      Saving...
                    </>
                  ) : (
                    <>
                      <Save className="w-3.5 h-3.5" />
                      Save Model
                    </>
                  )}
                </button>
              </div>

              {selectedModel && (
                <div className="text-[11px] text-neutral-400 flex items-center justify-between pt-1 border-t border-[#232A3E]/60">
                  <span className="truncate font-mono text-neutral-300">
                    ID: {selectedModel}
                  </span>
                  <span className="text-[10px] text-neutral-500">
                    Persisted to Django /api/settings/global/
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Section: AI System Prompt Template Editor */}
          <div className="bg-[#181E2E] border border-[#232A3E] rounded-xl p-3.5 space-y-3">
            <div className="flex justify-between items-center">
              <div className="flex items-center gap-2">
                <FileText className="w-4 h-4 text-indigo-400" />
                <div>
                  <span className="text-xs font-semibold text-white">AI Generation Prompt & Schema</span>
                  <span className="ml-2 text-[10px] bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 px-1.5 py-0.2 rounded font-mono">
                    Backend Managed
                  </span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setShowPromptEditor(!showPromptEditor)}
                className="text-xs text-indigo-400 hover:text-indigo-300 font-medium transition cursor-pointer flex items-center gap-1"
              >
                <Code2 className="w-3.5 h-3.5" />
                {showPromptEditor ? 'Collapse Prompt' : 'Edit Prompt'}
              </button>
            </div>

            {showPromptEditor ? (
              <div className="space-y-2.5 pt-1">
                <p className="text-[11px] text-neutral-400 leading-normal">
                  This prompt instructs the backend AI model on required fields (GPU, SoC, DPR, CSS resolution, User-Agent).
                </p>
                <textarea
                  value={customPrompt}
                  onChange={(e) => setCustomPrompt(e.target.value)}
                  rows={9}
                  className="w-full bg-[#111520] border border-[#232A3E] rounded-lg p-2.5 text-[11px] font-mono text-neutral-200 focus:outline-none focus:border-indigo-500 leading-relaxed resize-y selection:bg-indigo-600"
                  placeholder="Enter system prompt for AI model..."
                />
                <div className="flex flex-col sm:flex-row gap-2 items-center justify-between text-[11px] text-neutral-400">
                  <span>{customPrompt.length} characters • Also editable at Django <code>/admin/</code></span>
                  <div className="flex items-center gap-2 self-end">
                    <button
                      type="button"
                      onClick={handleResetPrompt}
                      className="px-2.5 py-1.5 rounded-lg bg-[#111520] hover:bg-[#232A3E] text-neutral-400 hover:text-white border border-[#232A3E] transition cursor-pointer flex items-center gap-1 text-xs"
                    >
                      <RotateCcw className="w-3 h-3" />
                      Reset Default
                    </button>
                    <button
                      type="button"
                      onClick={handleSavePrompt}
                      disabled={isSavingPrompt || !customPrompt.trim()}
                      className="px-3 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-medium transition cursor-pointer flex items-center gap-1.5 shadow-sm shadow-indigo-600/30 disabled:opacity-50 text-xs"
                    >
                      {isSavingPrompt ? (
                        <>
                          <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                          Saving...
                        </>
                      ) : (
                        <>
                          <Save className="w-3.5 h-3.5" />
                          Save Prompt
                        </>
                      )}
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="text-[11px] text-neutral-400 flex items-center justify-between">
                <span className="truncate max-w-[320px] text-neutral-400 font-mono text-[10px]">
                  {customPrompt.slice(0, 60)}...
                </span>
                <span className="text-neutral-500 text-[10px]">
                  Click 'Edit Prompt' to customize
                </span>
              </div>
            )}
          </div>

          {/* Input & Trigger */}
          <div className="space-y-3">
            <label className="text-xs font-medium text-neutral-300 block">Target Mobile Device Query</label>
            <div className="flex gap-2">
              <input
                type="text"
                placeholder="e.g. OnePlus 12, Galaxy S24 Ultra, Pixel 8, Tecno Camon 30..."
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleGenerate()}
                className="flex-1 bg-[#181E2E] border border-[#232A3E] rounded-xl px-4 py-2.5 text-sm text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500 transition"
              />
              <button
                onClick={handleGenerate}
                disabled={isGenerating || !aiPrompt.trim()}
                className="bg-blue-600 hover:bg-blue-500 text-white font-medium px-5 py-2.5 rounded-xl text-sm transition disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-lg shadow-blue-600/20"
              >
                {isGenerating ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    Building...
                  </>
                ) : (
                  <>
                    <Sparkles className="w-4 h-4" />
                    Generate
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Generated Specification Preview */}
          {generatedResult && (
            <div className="bg-[#181E2E] border border-blue-500/30 rounded-xl p-4 space-y-3 animate-fade-in">
              <div className="flex justify-between items-center pb-2 border-b border-[#232A3E]">
                <span className="text-xs font-semibold text-blue-400">Generated Fingerprint Blueprint</span>
                <span className="text-[10px] bg-blue-500/20 text-blue-300 px-2 py-0.5 rounded font-mono">
                  {generatedResult.model_code}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div>
                  <span className="text-neutral-400">Device:</span> <span className="text-white font-medium">{generatedResult.brand} {generatedResult.model_name}</span>
                </div>
                <div>
                  <span className="text-neutral-400">SoC:</span> <span className="text-white font-medium">{generatedResult.soc}</span>
                </div>
                <div>
                  <span className="text-neutral-400">GPU:</span> <span className="text-white font-medium">{generatedResult.webgl_renderer}</span>
                </div>
                <div>
                  <span className="text-neutral-400">Viewport:</span> <span className="text-white font-medium">{generatedResult.screen_width}x{generatedResult.screenHeight || generatedResult.screen_height} @ {generatedResult.dpr}x</span>
                </div>
                <div>
                  <span className="text-neutral-400">Memory:</span> <span className="text-white font-medium">{generatedResult.ram_gb}GB RAM / {generatedResult.cpu_cores} Cores</span>
                </div>
                <div>
                  <span className="text-neutral-400">OS:</span> <span className="text-white font-medium">Android {generatedResult.android_version}</span>
                </div>
              </div>
            </div>
          )}

          <div className="text-[11px] text-neutral-400 flex items-center gap-1.5">
            <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            Verified anti-detect parameters: authentic DPR, real WebGL unmasked strings, and matching User-Agents.
          </div>
        </section>
      </div>

      {/* Section 3: Registered Fleet Profiles Table */}
      <section className="bg-[#111520] border border-[#232A3E] rounded-2xl p-6 shadow-xl space-y-4">
        <div className="flex justify-between items-center pb-4 border-b border-[#232A3E]">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-cyan-500/10 rounded-lg text-cyan-400">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-white">Fleet Profiles Hardware & Cookie Sync</h2>
              <p className="text-xs text-neutral-400">Persistent hardware sandboxes and cookie exports</p>
            </div>
          </div>
          <span className="text-xs text-neutral-400 font-medium bg-[#181E2E] px-3 py-1.5 rounded-lg border border-[#232A3E]">
            Total Profiles: <strong className="text-white">{profiles.length}</strong>
          </span>
        </div>

        {profiles.length === 0 ? (
          <div className="py-12 text-center text-neutral-400">
            <Smartphone className="w-8 h-8 mx-auto mb-2 opacity-40" />
            <p className="text-sm">No profiles found in backend database.</p>
            <p className="text-xs text-neutral-500 mt-1">Generate one above or create a profile in the mobile application.</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs text-neutral-300">
              <thead className="bg-[#181E2E] text-neutral-400 font-medium uppercase text-[10px] tracking-wider border-b border-[#232A3E]">
                <tr>
                  <th className="px-4 py-3 rounded-l-lg">Profile Name</th>
                  <th className="px-4 py-3">Hardware Model</th>
                  <th className="px-4 py-3">GPU Renderer</th>
                  <th className="px-4 py-3">Screen / DPR</th>
                  <th className="px-4 py-3">RAM / CPU</th>
                  <th className="px-4 py-3">Proxy</th>
                  <th className="px-4 py-3">Cookies</th>
                  <th className="px-4 py-3 rounded-r-lg">Cookie Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#232A3E]/60">
                {profiles.map((prof) => (
                  <tr key={prof.id} className="hover:bg-[#181E2E]/50 transition">
                    <td className="px-4 py-3 font-semibold text-white flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full bg-emerald-400" />
                      {prof.name}
                    </td>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-200">{prof.brand} {prof.model_name}</div>
                      <div className="text-[10px] text-neutral-500 font-mono">{prof.model_code}</div>
                    </td>
                    <td className="px-4 py-3 font-mono text-[11px] text-neutral-300">{prof.webgl_renderer}</td>
                    <td className="px-4 py-3 font-mono text-[11px] text-neutral-300">
                      {prof.screen_width}x{prof.screen_height} ({prof.dpr}x)
                    </td>
                    <td className="px-4 py-3">{prof.ram_gb}GB / {prof.cpu_cores} Cores</td>
                    <td className="px-4 py-3">
                      <span className={`text-[10px] font-semibold px-2 py-0.5 rounded border ${
                        prof.proxy_type === 'DIRECT' 
                          ? 'bg-neutral-800 text-neutral-400 border-neutral-700' 
                          : 'bg-blue-500/10 text-blue-400 border-blue-500/20'
                      }`}>
                        {prof.proxy_type}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-semibold">
                        {prof.cookie_count || 0} cookies
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5">
                        <button
                          onClick={() => handleDownloadCookies(prof.id)}
                          className="px-2.5 py-1 text-[11px] font-medium bg-neutral-800 hover:bg-neutral-700 text-cyan-400 rounded border border-neutral-700 transition cursor-pointer"
                          title="Download session cookies as formatted JSON"
                        >
                          Export .JSON
                        </button>
                        <button
                          onClick={() => {
                            setActiveProfileForImport(prof);
                            setImportJsonText('');
                          }}
                          className="px-2.5 py-1 text-[11px] font-medium bg-blue-600 hover:bg-blue-500 text-white rounded transition cursor-pointer"
                          title="Import cookies into this profile"
                        >
                          Import
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Universal Cookie Importer Modal */}
      {activeProfileForImport && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-[#111520] border border-[#232A3E] rounded-2xl p-6 max-w-xl w-full shadow-2xl space-y-4">
            <div className="flex justify-between items-center border-b border-[#232A3E] pb-3">
              <div>
                <h3 className="text-base font-semibold text-white">Import Cookies: {activeProfileForImport.name}</h3>
                <p className="text-xs text-neutral-400">Paste Netscape tab-separated lines or JSON cookie array</p>
              </div>
              <button
                onClick={() => setActiveProfileForImport(null)}
                className="text-neutral-400 hover:text-white text-lg font-bold px-2 cursor-pointer"
              >
                ×
              </button>
            </div>

            <textarea
              rows={8}
              value={importJsonText}
              onChange={(e) => setImportJsonText(e.target.value)}
              placeholder='[&#10;  {"name": "session_id", "value": "xyz...", "domain": ".google.com", "path": "/"}&#10;]'
              className="w-full bg-[#0B0E14] border border-[#232A3E] rounded-xl p-3 text-xs font-mono text-neutral-200 focus:outline-none focus:border-cyan-500/60"
            />

            <div className="flex justify-end gap-2">
              <button
                onClick={() => setActiveProfileForImport(null)}
                className="px-4 py-2 text-xs bg-neutral-800 hover:bg-neutral-700 text-neutral-300 rounded-lg cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleImportSubmit}
                disabled={isSubmittingImport || !importJsonText.trim()}
                className="px-4 py-2 text-xs bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white rounded-lg font-medium cursor-pointer"
              >
                {isSubmittingImport ? 'Importing...' : 'Save & Overwrite Cookies'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
