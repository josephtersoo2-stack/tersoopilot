import React, { useState, useEffect } from 'react';
import { 
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
  fetchProfiles 
} from '../../api';

export default function HardwareBlueprintHub({
  settings,
  setSettings,
  profiles = [],
  setProfiles,
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
  const [showPromptEditor, setShowPromptEditor] = useState(true);
  const [targetSearchSites, setTargetSearchSites] = useState(
    settings.target_search_sites || "gsmarena.com\ndevicespecifications.com\nphonearena.com\nkimovil.com\nnanoreview.net"
  );
  const [isSavingSites, setIsSavingSites] = useState(false);

  useEffect(() => {
    if (settings.ai_generation_prompt) {
      setCustomPrompt(settings.ai_generation_prompt);
    }
    if (settings.target_search_sites) {
      setTargetSearchSites(settings.target_search_sites);
    }
    const curProv = settings.selected_ai_provider || 'openrouter';
    setProvider(curProv);
    const targetModel = curProv === 'gemini' ? settings.saved_gemini_model : settings.saved_openrouter_model;
    loadModels(curProv, targetModel);
  }, [settings]);

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
      if (showNotification) showNotification(`Failed to load ${prov} models from backend`, 'error');
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
      if (showNotification) showNotification(`Saved backend model for ${provider === 'gemini' ? 'Google Gemini' : 'OpenRouter'}: ${selectedModel}`);
    } catch (err) {
      if (showNotification) showNotification('Failed to save AI model preference', 'error');
    } finally {
      setIsSavingModel(false);
    }
  };

  const handleSaveSearchSites = async () => {
    setIsSavingSites(true);
    try {
      const res = await updateGlobalSettings({ target_search_sites: targetSearchSites });
      setSettings(res.data);
      if (showNotification) showNotification('Authoritative Spec Search Sources saved.');
    } catch (err) {
      if (showNotification) showNotification('Failed to save search sources', 'error');
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
      if (showNotification) showNotification('AI Profile Generation Prompt saved to Django backend.');
    } catch (err) {
      if (showNotification) showNotification('Failed to save AI prompt', 'error');
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
      if (showNotification) showNotification(`Generated blueprint: ${res.data.brand} ${res.data.model_name}`);
      setAiPrompt('');
      const profRes = await fetchProfiles();
      setProfiles(profRes.data);
    } catch (e) {
      if (showNotification) showNotification('Device specification generation failed', 'error');
    } finally {
      setIsGenerating(false);
    }
  };

  const activeSitesCount = targetSearchSites.split('\n').filter(s => s.trim()).length;

  return (
    <div className="space-y-6">
      {/* Metrics Row */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Fingerprint Blueprints</span>
            <div className="text-2xl font-bold text-white mt-0.5">{profiles.length}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Smartphone className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Spec Grounding Sources</span>
            <div className="text-2xl font-bold text-emerald-400 mt-0.5">{activeSitesCount} sites</div>
          </div>
          <div className="p-2.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
            <Globe className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Spec Synthesis Model</span>
            <div className="text-sm font-bold text-cyan-400 mt-1 truncate max-w-[180px]">
              {selectedModel || 'deepseek/chat'}
            </div>
          </div>
          <div className="p-2.5 rounded-xl bg-cyan-500/10 border border-cyan-500/20 text-cyan-400">
            <Sparkles className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* 2-Column Split: Generator & Search Grounding */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: AI Hardware Blueprint Generator (7 cols) */}
        <div className="lg:col-span-7 bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-5">
          <div className="flex items-center gap-3 pb-4 border-b border-[#1E2638]">
            <div className="p-2 bg-indigo-500/10 rounded-lg text-indigo-400">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-bold text-white">Dynamic AI Hardware Blueprint Generator</h2>
              <p className="text-xs text-neutral-400">Synthesize authentic mobile hardware fingerprints without hallucinated specs</p>
            </div>
          </div>

          {/* Provider and Dynamic Model Picker */}
          <div className="space-y-3">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-semibold text-neutral-300 mb-1.5 block">AI Provider</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('openrouter')}
                    className={`flex-1 py-2 px-2 text-xs font-medium rounded-xl border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'openrouter'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm'
                        : 'bg-[#0A0D14] text-neutral-400 border-[#1E2638] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 font-semibold">
                      <Radio className="w-3.5 h-3.5" />
                      <span>OpenRouter</span>
                    </div>
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('gemini')}
                    className={`flex-1 py-2 px-2 text-xs font-medium rounded-xl border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'gemini'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm'
                        : 'bg-[#0A0D14] text-neutral-400 border-[#1E2638] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 font-semibold">
                      <Sparkles className="w-3.5 h-3.5" />
                      <span>Gemini API</span>
                    </div>
                  </button>
                </div>
              </div>

              <div>
                <div className="flex justify-between items-center mb-1.5">
                  <label className="text-xs font-semibold text-neutral-300">
                    Model Selection
                  </label>
                  <button
                    type="button"
                    onClick={() => loadModels(provider)}
                    disabled={isLoadingModels}
                    className="p-1 rounded bg-[#0A0D14] hover:bg-[#181E2E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer"
                    title="Refresh models"
                  >
                    <RefreshCw className={`w-3 h-3 ${isLoadingModels ? 'animate-spin text-blue-400' : ''}`} />
                  </button>
                </div>
                <input
                  type="text"
                  placeholder="Filter models (e.g. gemini, deepseek, claude)..."
                  value={modelSearch}
                  onChange={(e) => setModelSearch(e.target.value)}
                  className="w-full bg-[#0A0D14] border border-[#1E2638] text-xs rounded-xl px-3 py-2 text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500"
                />
              </div>
            </div>

            {/* Provider Notice & API Key Status */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-[#0A0D14] border border-[#1E2638] rounded-xl text-[11px]">
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
            <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-3.5 space-y-3">
              <div className="flex flex-col sm:flex-row gap-2.5 items-center justify-between">
                <div className="w-full sm:flex-1">
                  <label className="text-[11px] font-medium text-neutral-400 mb-1 block">
                    Active Blueprint Model ({provider === 'gemini' ? 'Google Gemini' : 'OpenRouter'})
                  </label>
                  <select
                    value={selectedModel}
                    disabled={isLoadingModels || availableModels.length === 0}
                    onChange={(e) => setSelectedModel(e.target.value)}
                    className="w-full bg-[#111520] border border-[#1E2638] text-xs font-medium rounded-lg px-3 py-2 text-white focus:outline-none focus:border-blue-500 disabled:opacity-50 cursor-pointer"
                  >
                    {isLoadingModels ? (
                      <option>Loading models...</option>
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
                            {m.name || m.id}
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
                  <Save className="w-3.5 h-3.5" />
                  Save Model
                </button>
              </div>
            </div>
          </div>

          {/* Section: AI System Prompt for Creating Profiles (RESTORED & PROMINENT) */}
          <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 space-y-3">
            <div className="flex justify-between items-center">
              <div className="flex items-center gap-2">
                <FileText className="w-4 h-4 text-indigo-400" />
                <div>
                  <span className="text-xs font-semibold text-white">AI Profile Generation Prompt & Schema</span>
                  <span className="ml-2 text-[10px] bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 px-1.5 py-0.2 rounded font-mono">
                    Device Creation Engine
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
                  This system prompt instructs the AI model (Gemini or OpenRouter) on required device fingerprint fields (SoC, GPU renderer, DPR, screen resolution, User-Agent, Android version).
                </p>
                <textarea
                  value={customPrompt}
                  onChange={(e) => setCustomPrompt(e.target.value)}
                  rows={8}
                  className="w-full bg-[#111520] border border-[#1E2638] rounded-xl p-3 text-xs font-mono text-neutral-200 focus:outline-none focus:border-indigo-500 leading-relaxed resize-y selection:bg-indigo-600"
                  placeholder="Enter system prompt for AI model..."
                />
                <div className="flex flex-col sm:flex-row gap-2 items-center justify-between text-[11px] text-neutral-400">
                  <span>{customPrompt.length} characters • Persisted to Django <code>/api/settings/global/</code></span>
                  <div className="flex items-center gap-2 self-end">
                    <button
                      type="button"
                      onClick={handleResetPrompt}
                      className="px-2.5 py-1.5 rounded-lg bg-[#111520] hover:bg-[#181E2E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer flex items-center gap-1 text-xs"
                    >
                      <RotateCcw className="w-3 h-3" />
                      Reset Default
                    </button>
                    <button
                      type="button"
                      onClick={handleSavePrompt}
                      disabled={isSavingPrompt || !customPrompt.trim()}
                      className="px-3.5 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-medium transition cursor-pointer flex items-center gap-1.5 shadow-sm shadow-indigo-600/30 disabled:opacity-50 text-xs"
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
                  {customPrompt.slice(0, 70)}...
                </span>
                <span className="text-neutral-500 text-[10px]">
                  Click 'Edit Prompt' to inspect
                </span>
              </div>
            )}
          </div>

          {/* Live Grounding Sources Banner */}
          <div className="flex items-center justify-between px-3 py-2 bg-[#0A0D14] border border-emerald-500/30 rounded-xl text-xs">
            <div className="flex items-center gap-2 text-emerald-300">
              <Globe className="w-3.5 h-3.5 text-emerald-400" />
              <span className="font-semibold text-white">Live Grounding Sources:</span>
              <span className="truncate max-w-[240px] text-emerald-300/90 font-mono text-[11px]">
                {targetSearchSites.split('\n').map(s => s.trim()).filter(Boolean).slice(0, 3).join(', ')}
                {targetSearchSites.split('\n').map(s => s.trim()).filter(Boolean).length > 3 ? ` (+${targetSearchSites.split('\n').map(s => s.trim()).filter(Boolean).length - 3} more)` : ''}
              </span>
            </div>
            <span className="text-[10px] text-emerald-300 bg-emerald-500/10 px-2 py-0.5 rounded-md font-mono border border-emerald-500/20 font-semibold">
              Live Search Grounding
            </span>
          </div>

          {/* Device Query Input & Generator */}
          <div className="space-y-3 pt-1">
            <label className="text-xs font-semibold text-neutral-300 block">Target Mobile Device Query</label>
            <div className="flex gap-2">
              <input
                type="text"
                placeholder="e.g. OnePlus 12, Galaxy S24 Ultra, Pixel 8, itel S26 Ultra..."
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleGenerate()}
                className="flex-1 bg-[#0A0D14] border border-[#1E2638] rounded-xl px-4 py-2.5 text-sm text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500 transition"
              />
              <button
                onClick={handleGenerate}
                disabled={isGenerating || !aiPrompt.trim()}
                className="bg-blue-600 hover:bg-blue-500 text-white font-semibold px-5 py-2.5 rounded-xl text-sm transition disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-lg shadow-blue-600/20"
              >
                {isGenerating ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    Generating...
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
            <div className="bg-[#0A0D14] border border-blue-500/40 rounded-xl p-4 space-y-3 animate-fade-in">
              <div className="flex justify-between items-center pb-2 border-b border-[#1E2638]">
                <span className="text-xs font-bold text-blue-400">Generated Fingerprint Blueprint</span>
                <span className="text-[10px] bg-blue-500/20 text-blue-300 px-2 py-0.5 rounded font-mono">
                  {generatedResult.model_code}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div>
                  <span className="text-neutral-500">Device:</span> <span className="text-white font-medium">{generatedResult.brand} {generatedResult.model_name}</span>
                </div>
                <div>
                  <span className="text-neutral-500">SoC:</span> <span className="text-white font-medium">{generatedResult.soc}</span>
                </div>
                <div>
                  <span className="text-neutral-500">GPU:</span> <span className="text-white font-medium">{generatedResult.webgl_renderer}</span>
                </div>
                <div>
                  <span className="text-neutral-500">Screen:</span> <span className="text-white font-medium">{generatedResult.screen_width}x{generatedResult.screenHeight || generatedResult.screen_height} ({generatedResult.dpr}x)</span>
                </div>
                <div>
                  <span className="text-neutral-500">Memory:</span> <span className="text-white font-medium">{generatedResult.ram_gb}GB RAM</span>
                </div>
                <div>
                  <span className="text-neutral-500">OS:</span> <span className="text-white font-medium">Android {generatedResult.android_version}</span>
                </div>
              </div>
            </div>
          )}

          <div className="text-[11px] text-neutral-400 flex items-center gap-1.5">
            <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            Live anti-detect parameters: authentic DPR, WebGL unmasked strings, and matching User-Agents.
          </div>
        </div>

        {/* Right Column: Spec Search Sources (5 cols) */}
        <div className="lg:col-span-5 bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-4">
          <div className="flex items-center justify-between pb-3 border-b border-[#1E2638]">
            <div className="flex items-center gap-2">
              <Globe className="w-4 h-4 text-emerald-400" />
              <h2 className="text-sm font-bold text-white">Authoritative Search Sources</h2>
            </div>
            <span className="text-[10px] bg-emerald-500/10 text-emerald-300 border border-emerald-500/20 px-2 py-0.5 rounded-full font-mono font-semibold">
              Live Grounding
            </span>
          </div>

          <p className="text-xs text-neutral-400 leading-relaxed">
            The backend queries these authoritative domains in real time when generating any profile to eliminate hallucinations.
          </p>

          <div>
            <label className="text-xs font-semibold text-neutral-300 block mb-1.5">
              Quick Add Trusted Sources:
            </label>
            <div className="flex flex-wrap gap-1.5">
              {[
                { label: 'GSMArena', domain: 'gsmarena.com' },
                { label: 'DeviceSpecifications', domain: 'devicespecifications.com' },
                { label: 'PhoneArena', domain: 'phonearena.com' },
                { label: 'Kimovil', domain: 'kimovil.com' },
                { label: 'NanoReview', domain: 'nanoreview.net' }
              ].map((preset) => {
                const isAdded = targetSearchSites.split('\n').some(l => l.trim().toLowerCase() === preset.domain.toLowerCase());
                return (
                  <button
                    key={preset.domain}
                    type="button"
                    onClick={() => handleAddSitePreset(preset.domain)}
                    disabled={isAdded}
                    className={`px-2 py-1 text-[11px] font-medium rounded-lg border transition cursor-pointer flex items-center gap-1 ${
                      isAdded
                        ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300 cursor-default opacity-80'
                        : 'bg-[#0A0D14] hover:bg-[#181E2E] border-[#1E2638] text-neutral-300 hover:text-white'
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
              <label className="text-xs font-semibold text-neutral-300">
                Domains (one per line):
              </label>
              <span className="text-[10px] text-neutral-500 font-mono">
                {activeSitesCount} sources
              </span>
            </div>
            <textarea
              value={targetSearchSites}
              onChange={(e) => setTargetSearchSites(e.target.value)}
              rows={6}
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-3 text-xs font-mono text-emerald-300 focus:outline-none focus:border-emerald-500 leading-relaxed resize-y"
              placeholder="gsmarena.com&#10;devicespecifications.com..."
            />
          </div>

          <div className="flex justify-between items-center pt-2 border-t border-[#1E2638]">
            <button
              type="button"
              onClick={handleResetSearchSites}
              className="px-2.5 py-1.5 rounded-lg bg-[#0A0D14] hover:bg-[#181E2E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer flex items-center gap-1 text-xs"
            >
              <RotateCcw className="w-3 h-3" /> Reset
            </button>
            <button
              type="button"
              onClick={handleSaveSearchSites}
              disabled={isSavingSites || !targetSearchSites.trim()}
              className="px-3.5 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-medium transition cursor-pointer flex items-center gap-1.5 text-xs shadow-sm shadow-emerald-600/30"
            >
              <Save className="w-3.5 h-3.5" /> Save Sources
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
