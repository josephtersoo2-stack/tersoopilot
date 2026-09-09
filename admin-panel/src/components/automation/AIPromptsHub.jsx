import React, { useState, useEffect } from 'react';
import axios from '../../api';
import { 
  Sparkles, 
  Save, 
  Code2, 
  AlertCircle, 
  RefreshCw, 
  Radio, 
  Sliders, 
  Smartphone, 
  FileText, 
  RotateCcw,
  Search
} from 'lucide-react';
import { fetchGlobalSettings, updateGlobalSettings, fetchAvailableModels } from '../../api';

const API_ACTIVE = 'automation/ai-config/active/';
const API_BASE = 'automation/ai-config/';

export default function AIPromptsHub({ onSaved }) {
  const [activePromptTab, setActivePromptTab] = useState('TASK_RECOVERY'); // 'TASK_RECOVERY' or 'PROFILE_GEN'

  // Task Recovery Prompt State (/api/automation/ai-config/)
  const [configId, setConfigId] = useState(null);
  const [provider, setProvider] = useState('OPENROUTER');
  const [modelName, setModelName] = useState('deepseek/deepseek-chat');
  const [temperature, setTemperature] = useState(0.1);
  const [systemPrompt, setSystemPrompt] = useState('');
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);

  // Dynamic Models State
  const [availableModels, setAvailableModels] = useState([]);
  const [isLoadingModels, setIsLoadingModels] = useState(false);
  const [modelSearch, setModelSearch] = useState('');
  const [providerNotice, setProviderNotice] = useState('');

  // Profile Generation Prompt State (/api/settings/global/)
  const [profileGenPrompt, setProfileGenPrompt] = useState('');
  const [isSavingProfilePrompt, setIsSavingProfilePrompt] = useState(false);

  useEffect(() => {
    loadAllConfigs();
  }, []);

  const loadAllConfigs = async () => {
    setIsLoading(true);
    try {
      const [activeRes, globalRes] = await Promise.all([
        axios.get(API_ACTIVE).catch(() => ({ data: {} })),
        fetchGlobalSettings().catch(() => ({ data: {} }))
      ]);

      const cfg = activeRes.data || {};
      const prov = cfg.provider || 'OPENROUTER';
      const targetModel = cfg.model_name || 'deepseek/deepseek-chat';

      setConfigId(cfg.id);
      setProvider(prov);
      setModelName(targetModel);
      setTemperature(cfg.temperature ?? 0.1);
      setSystemPrompt(cfg.system_prompt || '');

      if (globalRes.data?.ai_generation_prompt) {
        setProfileGenPrompt(globalRes.data.ai_generation_prompt);
      }

      await loadDynamicModels(prov, targetModel);
    } catch (err) {
      console.error('Failed to load AI configurations:', err);
    } finally {
      setIsLoading(false);
    }
  };

  const loadDynamicModels = async (prov, targetModel = null) => {
    setIsLoadingModels(true);
    setProviderNotice('');
    try {
      const res = await fetchAvailableModels(prov.toLowerCase());
      const list = res.data.models || [];
      setAvailableModels(list);
      if (res.data.notice) {
        setProviderNotice(res.data.notice);
      }
      if (list.length > 0) {
        const exists = list.some((m) => m.id === (targetModel || modelName));
        if (targetModel) {
          setModelName(exists ? targetModel : list[0].id);
        } else if (!exists) {
          setModelName(list[0].id);
        }
      }
    } catch (err) {
      console.error('Failed to load models for provider:', err);
    } finally {
      setIsLoadingModels(false);
    }
  };

  const handleSwitchProvider = (newProv) => {
    setProvider(newProv);
    setModelSearch('');
    loadDynamicModels(newProv);
  };

  const handleSaveTaskConfig = async () => {
    setIsSaving(true);
    const payload = {
      provider,
      model_name: modelName,
      temperature: parseFloat(temperature),
      system_prompt: systemPrompt
    };

    try {
      await axios.patch(`${API_BASE}${configId}/`, payload);
      alert('GhostPilot Task Recovery Configuration updated live in backend!');
      if (onSaved) onSaved(modelName);
    } catch (err) {
      alert('Error updating configuration: ' + err.message);
    } finally {
      setIsSaving(false);
    }
  };

  const handleSaveProfileGenPrompt = async () => {
    setIsSavingProfilePrompt(true);
    try {
      await updateGlobalSettings({ ai_generation_prompt: profileGenPrompt });
      alert('AI Profile Generation Prompt saved to Django backend!');
    } catch (err) {
      alert('Error saving prompt: ' + err.message);
    } finally {
      setIsSavingProfilePrompt(false);
    }
  };

  const handleResetProfilePrompt = () => {
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
    setProfileGenPrompt(defaultPrompt);
  };

  const insertVariable = (variable) => {
    setSystemPrompt((prev) => prev + ` {${variable}}`);
  };

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64 text-neutral-400 font-mono text-xs">
        <RefreshCw className="w-4 h-4 animate-spin mr-2 text-blue-400" /> Loading AI prompts and models...
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      {/* Top Prompt Category Switcher */}
      <div className="flex p-1.5 bg-[#111520] border border-[#1E2638] rounded-2xl gap-2">
        <button
          type="button"
          onClick={() => setActivePromptTab('TASK_RECOVERY')}
          className={`flex-1 py-3 px-4 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition cursor-pointer ${
            activePromptTab === 'TASK_RECOVERY'
              ? 'bg-blue-600 text-white shadow-md shadow-blue-600/20'
              : 'text-neutral-400 hover:text-white hover:bg-[#181E2E]'
          }`}
        >
          <Sparkles className="w-4 h-4 text-amber-400" />
          <span>GhostPilot Task Decision & Recovery Prompt</span>
        </button>

        <button
          type="button"
          onClick={() => setActivePromptTab('PROFILE_GEN')}
          className={`flex-1 py-3 px-4 rounded-xl text-xs font-semibold flex items-center justify-center gap-2 transition cursor-pointer ${
            activePromptTab === 'PROFILE_GEN'
              ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/20'
              : 'text-neutral-400 hover:text-white hover:bg-[#181E2E]'
          }`}
        >
          <Smartphone className="w-4 h-4 text-indigo-300" />
          <span>Device Profile Creation Prompt & Schema</span>
        </button>
      </div>

      {/* VIEW 1: GhostPilot Task Decision & Recovery Prompt */}
      {activePromptTab === 'TASK_RECOVERY' && (
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-6">
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-5 border-b border-[#1E2638]">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
                <Sparkles className="w-5 h-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-base font-bold text-white">GhostPilot Task Decision & Recovery Prompt</h2>
                  <span className="text-[10px] bg-amber-500/10 text-amber-300 border border-amber-500/20 px-2 py-0.5 rounded-full font-mono font-semibold">
                    Live Active Backend
                  </span>
                </div>
                <p className="text-xs text-neutral-400 mt-0.5">
                  Guides GhostPilot when a browser task encounters an unskippable ad, consent wall, or abnormal DOM state.
                </p>
              </div>
            </div>

            <button
              onClick={handleSaveTaskConfig}
              disabled={isSaving}
              className="bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold px-4 py-2.5 rounded-xl shadow-lg shadow-blue-600/20 disabled:opacity-50 transition cursor-pointer flex items-center gap-1.5 shrink-0"
            >
              {isSaving ? (
                <>
                  <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                  Saving...
                </>
              ) : (
                <>
                  <Save className="w-4 h-4" /> Save Recovery Config
                </>
              )}
            </button>
          </div>

          {/* Provider and Dynamic Model Picker (Matching Hardware Blueprint Hub) */}
          <div className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {/* Provider Buttons */}
              <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 space-y-3">
                <label className="text-xs font-semibold text-neutral-300 block">AI Reasoning Provider</label>
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('OPENROUTER')}
                    className={`flex-1 py-2.5 px-3 text-xs font-medium rounded-xl border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'OPENROUTER'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm'
                        : 'bg-[#111520] text-neutral-400 border-[#1E2638] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 font-semibold">
                      <Radio className="w-3.5 h-3.5" />
                      <span>OpenRouter.ai</span>
                    </div>
                    <span className="text-[10px] opacity-75">Multi-Model Hub</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSwitchProvider('GEMINI')}
                    className={`flex-1 py-2.5 px-3 text-xs font-medium rounded-xl border transition cursor-pointer flex flex-col items-center justify-center gap-0.5 ${
                      provider === 'GEMINI'
                        ? 'bg-blue-600 text-white border-blue-500 shadow-sm'
                        : 'bg-[#111520] text-neutral-400 border-[#1E2638] hover:text-white'
                    }`}
                  >
                    <div className="flex items-center gap-1.5 font-semibold">
                      <Sparkles className="w-3.5 h-3.5" />
                      <span>Gemini API</span>
                    </div>
                    <span className="text-[10px] opacity-75">Native 2.5 Flash SDK</span>
                  </button>
                </div>
              </div>

              {/* Dynamic Model Search & Refresh */}
              <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 space-y-3">
                <div className="flex justify-between items-center">
                  <label className="text-xs font-semibold text-neutral-300">
                    Search Provider Models ({availableModels.length})
                  </label>
                  <button
                    type="button"
                    onClick={() => loadDynamicModels(provider)}
                    disabled={isLoadingModels}
                    className="p-1 rounded bg-[#111520] hover:bg-[#181E2E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer"
                    title="Refresh models directly from provider"
                  >
                    <RefreshCw className={`w-3.5 h-3.5 ${isLoadingModels ? 'animate-spin text-blue-400' : ''}`} />
                  </button>
                </div>
                <div className="relative">
                  <input
                    type="text"
                    placeholder="Filter models (e.g. gemini, deepseek, claude, llama, gpt)..."
                    value={modelSearch}
                    onChange={(e) => setModelSearch(e.target.value)}
                    className="w-full bg-[#111520] border border-[#1E2638] text-xs rounded-xl pl-8 pr-3 py-2 text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500 transition"
                  />
                  <Search className="w-3.5 h-3.5 text-neutral-500 absolute left-2.5 top-2.5" />
                </div>
              </div>
            </div>

            {/* Provider Notice & API Key Status Pill */}
            <div className="flex items-center justify-between px-3.5 py-2 bg-[#0A0D14] border border-[#1E2638] rounded-xl text-[11px]">
              <div className="flex items-center gap-1.5 text-neutral-400">
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                <span>
                  Live Provider API: <strong className="text-emerald-400 font-mono">Configured in Django</strong>
                </span>
                <span className="text-neutral-600">•</span>
                <span>{availableModels.length} models fetched directly</span>
              </div>
              {providerNotice && (
                <span className="text-amber-400/90 text-[10px] truncate max-w-[280px]">
                  {providerNotice}
                </span>
              )}
            </div>

            {/* Dynamic Model Dropdown & Temperature Control */}
            <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 items-end">
                {/* Dynamic Model Dropdown (2 cols) */}
                <div className="sm:col-span-2">
                  <div className="flex justify-between items-center mb-1.5">
                    <label className="text-xs font-semibold text-neutral-300">
                      Choose Active Model for GhostPilot ({provider === 'GEMINI' ? 'Google Gemini' : 'OpenRouter'})
                    </label>
                    <span className="text-[10px] font-mono text-cyan-400 truncate max-w-[200px]">
                      Selected: {modelName}
                    </span>
                  </div>
                  <select
                    value={modelName}
                    disabled={isLoadingModels || availableModels.length === 0}
                    onChange={(e) => setModelName(e.target.value)}
                    className="w-full bg-[#111520] border border-[#1E2638] text-xs font-medium rounded-xl px-3.5 py-2.5 text-white focus:outline-none focus:border-blue-500 disabled:opacity-50 cursor-pointer"
                  >
                    {isLoadingModels ? (
                      <option>Fetching models live from {provider}...</option>
                    ) : availableModels.length === 0 ? (
                      <option value={modelName}>{modelName} (Current Config)</option>
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

                {/* Temperature Slider (1 col) */}
                <div className="bg-[#111520] border border-[#1E2638] rounded-xl p-3 space-y-1.5">
                  <div className="flex justify-between items-center text-xs">
                    <span className="text-neutral-400 font-medium">Temperature:</span>
                    <span className="font-mono text-blue-400 font-bold">{temperature}</span>
                  </div>
                  <input
                    type="range"
                    min="0.0"
                    max="1.0"
                    step="0.05"
                    value={temperature}
                    onChange={(e) => setTemperature(parseFloat(e.target.value))}
                    className="w-full h-2 bg-[#0A0D14] rounded-lg appearance-none cursor-pointer accent-blue-500"
                  />
                  <div className="flex justify-between text-[10px] text-neutral-500 font-mono">
                    <span>0.0 (Strict)</span>
                    <span>1.0 (Creative)</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* System Prompt Template */}
          <div className="space-y-2.5">
            <div className="flex justify-between items-center">
              <div className="flex items-center gap-2">
                <Code2 className="w-4 h-4 text-blue-400" />
                <label className="text-xs font-semibold text-neutral-300">
                  GhostPilot Semantic System Prompt Template
                </label>
              </div>

              <div className="flex items-center gap-1.5 text-[10px] text-neutral-400">
                <span className="hidden sm:inline">Inject variables:</span>
                {['task_name', 'task_category', 'current_state', 'execution_context'].map((tag) => (
                  <button
                    key={tag}
                    type="button"
                    onClick={() => insertVariable(tag)}
                    className="bg-[#0A0D14] hover:bg-[#181E2E] text-neutral-300 hover:text-blue-300 px-2 py-0.5 rounded-md border border-[#1E2638] font-mono text-[9px] cursor-pointer transition"
                  >
                    +{tag}
                  </button>
                ))}
              </div>
            </div>

            <textarea
              rows={13}
              value={systemPrompt}
              onChange={(e) => setSystemPrompt(e.target.value)}
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 text-xs font-mono text-neutral-200 focus:border-blue-500 outline-none leading-relaxed selection:bg-blue-600"
            />

            <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-3.5 flex items-start gap-2.5 text-xs text-neutral-400">
              <AlertCircle className="w-4 h-4 text-blue-400 shrink-0 mt-0.5" />
              <span>
                Backend validator <code>AgentRecoveryAction</code> parses responses into atomic recovery actions (e.g. <code>TAP_COORDINATES</code>, <code>BÉZIER_SWIPE</code>).
              </span>
            </div>
          </div>
        </div>
      )}

      {/* VIEW 2: Device Profile Creation Prompt & Schema */}
      {activePromptTab === 'PROFILE_GEN' && (
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-6 shadow-xl space-y-6">
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 pb-5 border-b border-[#1E2638]">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400">
                <FileText className="w-5 h-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h2 className="text-base font-bold text-white">AI Profile Generation Prompt & Schema</h2>
                  <span className="text-[10px] bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 px-2 py-0.5 rounded-full font-mono font-semibold">
                    GlobalSettings Sync
                  </span>
                </div>
                <p className="text-xs text-neutral-400 mt-0.5">
                  Controls how the AI model synthesizes authentic hardware fingerprints (SoC, GPU, DPR, CSS resolution, User-Agent) from live phone specs.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={handleResetProfilePrompt}
                className="px-3 py-2 bg-[#0A0D14] hover:bg-[#181E2E] text-neutral-400 hover:text-white border border-[#1E2638] rounded-xl text-xs font-medium transition cursor-pointer flex items-center gap-1.5"
              >
                <RotateCcw className="w-3.5 h-3.5" /> Reset Default
              </button>
              <button
                onClick={handleSaveProfileGenPrompt}
                disabled={isSavingProfilePrompt || !profileGenPrompt.trim()}
                className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-semibold px-4 py-2.5 rounded-xl shadow-lg shadow-indigo-600/20 disabled:opacity-50 transition cursor-pointer flex items-center gap-1.5"
              >
                {isSavingProfilePrompt ? (
                  <>
                    <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    Saving...
                  </>
                ) : (
                  <>
                    <Save className="w-4 h-4" /> Save Profile Prompt
                  </>
                )}
              </button>
            </div>
          </div>

          <div className="space-y-2">
            <div className="flex justify-between items-center text-xs text-neutral-400">
              <span className="font-semibold text-neutral-300">Profile Generation System Prompt</span>
              <span className="font-mono text-[11px]">{profileGenPrompt.length} characters • Django <code>/api/settings/global/</code></span>
            </div>

            <textarea
              rows={16}
              value={profileGenPrompt}
              onChange={(e) => setProfileGenPrompt(e.target.value)}
              className="w-full bg-[#0A0D14] border border-[#1E2638] rounded-xl p-4 text-xs font-mono text-neutral-200 focus:border-indigo-500 outline-none leading-relaxed selection:bg-indigo-600"
              placeholder="Enter system prompt for generating device hardware profiles..."
            />

            <div className="bg-[#0A0D14] border border-[#1E2638] rounded-xl p-3.5 flex items-start gap-2.5 text-xs text-neutral-400">
              <AlertCircle className="w-4 h-4 text-indigo-400 shrink-0 mt-0.5" />
              <span>
                This prompt is provided to Gemini/OpenRouter during device generation. It instructs the model to preserve exact model names, synthesize authentic hardware lineages, and format output as pure JSON with verified DPR, screen sizes, and Adreno/Mali renderers.
              </span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
