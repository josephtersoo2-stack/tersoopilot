import React, { useState, useEffect } from 'react';
import axios from '../../api';
import { 
  ShieldCheck, 
  Zap, 
  Sliders, 
  X, 
  Save, 
  Check, 
  Sparkles, 
  Layers, 
  Clock, 
  Keyboard, 
  HeartHandshake,
  Copy
} from 'lucide-react';

export default function PersonaModal({ profile, onClose, onUpdated }) {
  const [activeTab, setActiveTab] = useState('persona'); // 'persona' | 'niches'
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);

  // Editable Persona Attributes State
  const [formData, setFormData] = useState({
    trust_score: 10,
    maturation_stage: 'INFANT',
    typing_wpm: 65,
    typo_probability: 0.03,
    patience_index: 0.6,
    engagement_rate: 0.15,
  });

  // Niche Affiliations State
  const [allNiches, setAllNiches] = useState([]);
  const [selectedNicheWeights, setSelectedNicheWeights] = useState({});

  useEffect(() => {
    loadPersonaAndNiches();
  }, [profile.id]);

  const loadPersonaAndNiches = async () => {
    setIsLoading(true);
    try {
      const [personaRes, nichesRes, currentNichesRes] = await Promise.all([
        axios.get(`automation/profiles-orchestration/${profile.id}/get-persona/`),
        axios.get(`automation/niches/`),
        axios.get(`automation/profiles-orchestration/${profile.id}/get-niches/`)
      ]);

      const p = personaRes.data;
      setFormData({
        trust_score: p.trust_score ?? 10,
        maturation_stage: p.maturation_stage || 'INFANT',
        typing_wpm: p.typing_wpm ?? 65,
        typo_probability: p.typo_probability ?? 0.03,
        patience_index: p.patience_index ?? 0.6,
        engagement_rate: p.engagement_rate ?? 0.15,
      });

      setAllNiches(nichesRes.data || []);

      const weights = {};
      (currentNichesRes.data || []).forEach((item) => {
        weights[item.niche] = item.weight_percentage;
      });
      setSelectedNicheWeights(weights);
    } catch (err) {
      console.error('Failed to load persona or niches:', err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleTrustScoreChange = (score) => {
    const s = Math.max(0, Math.min(100, parseInt(score) || 0));
    let stage = 'INFANT';
    if (s > 75) stage = 'MATURE';
    else if (s > 50) stage = 'MATURING';
    else if (s > 25) stage = 'SEEDING';

    setFormData((prev) => ({
      ...prev,
      trust_score: s,
      maturation_stage: stage,
    }));
  };

  const handleStageSelect = (stage) => {
    let score = 15;
    if (stage === 'SEEDING') score = 40;
    else if (stage === 'MATURING') score = 65;
    else if (stage === 'MATURE') score = 90;

    setFormData((prev) => ({
      ...prev,
      trust_score: score,
      maturation_stage: stage,
    }));
  };

  const handleWeightChange = (nicheId, weight) => {
    setSelectedNicheWeights((prev) => ({
      ...prev,
      [nicheId]: Math.max(5, Math.min(100, parseInt(weight) || 5))
    }));
  };

  const toggleNiche = (nicheId) => {
    setSelectedNicheWeights((prev) => {
      const copy = { ...prev };
      if (copy[nicheId] !== undefined) {
        delete copy[nicheId];
      } else {
        copy[nicheId] = 50;
      }
      return copy;
    });
  };

  const handleSave = async () => {
    setIsSaving(true);
    setSaveSuccess(false);

    const personaPayload = {
      trust_score: formData.trust_score,
      maturation_stage: formData.maturation_stage,
      typing_wpm: formData.typing_wpm,
      typo_probability: formData.typo_probability,
      patience_index: formData.patience_index,
      engagement_rate: formData.engagement_rate,
    };

    const nichesPayload = {
      niches: Object.entries(selectedNicheWeights).map(([nicheId, weight]) => ({
        niche_id: nicheId,
        weight: weight
      }))
    };

    try {
      await Promise.all([
        axios.post(
          `automation/profiles-orchestration/${profile.id}/set-persona/`,
          personaPayload
        ),
        axios.post(
          `automation/profiles-orchestration/${profile.id}/set-niches/`,
          nichesPayload
        )
      ]);

      setSaveSuccess(true);
      if (onUpdated) onUpdated();
      setTimeout(() => {
        onClose();
      }, 600);
    } catch (err) {
      console.error(err);
      alert('Error saving behavioral persona: ' + (err.response?.data?.error || err.message));
    } finally {
      setIsSaving(false);
    }
  };

  const getStageColor = (stage) => {
    switch (stage) {
      case 'MATURE': return 'text-emerald-400 bg-emerald-500/10 border-emerald-500/30';
      case 'MATURING': return 'text-purple-400 bg-purple-500/10 border-purple-500/30';
      case 'SEEDING': return 'text-blue-400 bg-blue-500/10 border-blue-500/30';
      default: return 'text-amber-400 bg-amber-500/10 border-amber-500/30';
    }
  };

  return (
    <div className="fixed inset-0 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 z-50 animate-fadeIn">
      <div className="bg-[#111520] border border-[#1E2638] rounded-2xl w-full max-w-2xl max-h-[92vh] flex flex-col shadow-2xl relative overflow-hidden">
        {/* Header */}
        <div className="p-5 border-b border-[#1E2638] flex justify-between items-start">
          <div>
            <div className="flex items-center gap-2">
              <span className="w-2.5 h-2.5 rounded-full bg-blue-500 animate-pulse" />
              <h2 className="text-base font-bold text-white">
                Behavioral Persona: {profile.name}
              </h2>
            </div>
            <p className="text-xs text-neutral-400 mt-0.5">
              {profile.brand} {profile.model_name} • Hardware Blueprint Bound
            </p>
            <div className="flex items-center gap-2 mt-2 bg-[#0B0E17] border border-[#1E2638] px-2.5 py-1 rounded-lg w-fit">
              <span className="text-[9px] font-mono text-neutral-500 font-bold uppercase">UUID:</span>
              <span className="text-[11px] font-mono text-cyan-300 font-semibold select-all">{profile.id}</span>
              <button 
                type="button"
                onClick={() => navigator.clipboard.writeText(profile.id)}
                className="text-neutral-400 hover:text-white p-0.5 rounded transition cursor-pointer"
                title="Copy Profile UUID"
              >
                <Copy className="w-3 h-3 text-cyan-400" />
              </button>
            </div>
          </div>
          <button 
            onClick={onClose} 
            className="text-neutral-400 hover:text-white p-1 rounded-lg hover:bg-neutral-800 transition cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Navigation Tabs */}
        <div className="px-5 pt-3 border-b border-[#1E2638] flex gap-4 text-xs font-semibold">
          <button
            onClick={() => setActiveTab('persona')}
            className={`pb-2.5 flex items-center gap-2 border-b-2 transition cursor-pointer ${
              activeTab === 'persona'
                ? 'border-blue-500 text-blue-400'
                : 'border-transparent text-neutral-400 hover:text-neutral-200'
            }`}
          >
            <Zap className="w-4 h-4" />
            <span>Behavioral Parameters (Editable)</span>
          </button>
          <button
            onClick={() => setActiveTab('niches')}
            className={`pb-2.5 flex items-center gap-2 border-b-2 transition cursor-pointer ${
              activeTab === 'niches'
                ? 'border-blue-500 text-blue-400'
                : 'border-transparent text-neutral-400 hover:text-neutral-200'
            }`}
          >
            <Layers className="w-4 h-4" />
            <span>Weighted Niches ({Object.keys(selectedNicheWeights).length})</span>
          </button>
        </div>

        {/* Body Content */}
        <div className="p-5 overflow-y-auto flex-1 space-y-6">
          {isLoading ? (
            <div className="p-12 text-center text-neutral-400">
              <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
              <p className="text-xs">Loading persona parameters...</p>
            </div>
          ) : activeTab === 'persona' ? (
            <div className="space-y-5">
              {/* Live Preview Summary Bar */}
              <div className="bg-[#0B0E17] border border-[#1E2638] rounded-xl p-4 grid grid-cols-3 gap-3">
                <div>
                  <span className="text-[10px] text-neutral-500 uppercase font-semibold block">Trust Level</span>
                  <div className="flex items-center gap-1.5 mt-1">
                    <ShieldCheck className="w-4 h-4 text-emerald-400" />
                    <span className="text-base font-bold text-white">{formData.trust_score}/100</span>
                  </div>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded border mt-1 inline-block font-mono ${getStageColor(formData.maturation_stage)}`}>
                    {formData.maturation_stage}
                  </span>
                </div>

                <div>
                  <span className="text-[10px] text-neutral-500 uppercase font-semibold block">Typing Cadence</span>
                  <div className="flex items-center gap-1.5 mt-1">
                    <Zap className="w-4 h-4 text-amber-400" />
                    <span className="text-base font-bold text-white">{formData.typing_wpm} WPM</span>
                  </div>
                  <span className="text-[10px] text-neutral-400 block mt-1">
                    Typo Rate: {(formData.typo_probability * 100).toFixed(1)}%
                  </span>
                </div>

                <div>
                  <span className="text-[10px] text-neutral-500 uppercase font-semibold block">Patience & Engage</span>
                  <div className="flex items-center gap-1.5 mt-1">
                    <Sliders className="w-4 h-4 text-blue-400" />
                    <span className="text-base font-bold text-white">{(formData.patience_index * 10).toFixed(1)}/10</span>
                  </div>
                  <span className="text-[10px] text-neutral-400 block mt-1">
                    Engage: {(formData.engagement_rate * 100).toFixed(0)}%
                  </span>
                </div>
              </div>

              {/* Parameter 1: Trust Score & Maturation Stage */}
              <div className="bg-[#0D111D] border border-[#1E2638] rounded-xl p-4 space-y-3">
                <div className="flex justify-between items-center">
                  <div className="flex items-center gap-2">
                    <ShieldCheck className="w-4 h-4 text-emerald-400" />
                    <label className="text-xs font-bold text-white uppercase tracking-wider">
                      Trust Score & Maturation Stage
                    </label>
                  </div>
                  <span className="text-xs font-bold font-mono text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
                    {formData.trust_score} / 100
                  </span>
                </div>

                <input
                  type="range"
                  min="0"
                  max="100"
                  step="1"
                  value={formData.trust_score}
                  onChange={(e) => handleTrustScoreChange(e.target.value)}
                  className="w-full accent-emerald-500 cursor-pointer"
                />

                {/* Quick Maturation Stage Selectors */}
                <div className="grid grid-cols-4 gap-2 pt-1">
                  {[
                    { key: 'INFANT', label: 'Infant (0-25)', range: 15 },
                    { key: 'SEEDING', label: 'Seeding (26-50)', range: 40 },
                    { key: 'MATURING', label: 'Maturing (51-75)', range: 65 },
                    { key: 'MATURE', label: 'Mature (76-100)', range: 90 },
                  ].map((st) => (
                    <button
                      key={st.key}
                      type="button"
                      onClick={() => handleStageSelect(st.key)}
                      className={`text-[10px] py-1.5 px-2 rounded-lg border font-semibold transition cursor-pointer ${
                        formData.maturation_stage === st.key
                          ? 'bg-blue-600/20 border-blue-500 text-blue-400 shadow-sm'
                          : 'bg-[#111520] border-[#1E2638] text-neutral-400 hover:text-neutral-200'
                      }`}
                    >
                      {st.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Parameter 2: Typing Speed (WPM) & Typo Probability */}
              <div className="bg-[#0D111D] border border-[#1E2638] rounded-xl p-4 space-y-4">
                <div className="flex items-center gap-2">
                  <Keyboard className="w-4 h-4 text-amber-400" />
                  <label className="text-xs font-bold text-white uppercase tracking-wider">
                    Physical Keystroke Dynamics
                  </label>
                </div>

                {/* WPM Slider */}
                <div className="space-y-1.5">
                  <div className="flex justify-between text-xs">
                    <span className="text-neutral-400">Typing Speed (WPM)</span>
                    <span className="font-bold font-mono text-amber-400">{formData.typing_wpm} WPM</span>
                  </div>
                  <input
                    type="range"
                    min="30"
                    max="120"
                    step="1"
                    value={formData.typing_wpm}
                    onChange={(e) => setFormData(prev => ({ ...prev, typing_wpm: parseInt(e.target.value) || 60 }))}
                    className="w-full accent-amber-500 cursor-pointer"
                  />
                  <div className="flex justify-between text-[10px] text-neutral-500">
                    <span>30 WPM (Hesitant / Slow)</span>
                    <span>70 WPM (Average)</span>
                    <span>120 WPM (High Velocity)</span>
                  </div>
                </div>

                {/* Typo Probability Slider */}
                <div className="space-y-1.5 pt-2 border-t border-[#1E2638]/70">
                  <div className="flex justify-between text-xs">
                    <span className="text-neutral-400">Typo & Error Probability</span>
                    <span className="font-bold font-mono text-amber-400">{(formData.typo_probability * 100).toFixed(1)}%</span>
                  </div>
                  <input
                    type="range"
                    min="0"
                    max="0.15"
                    step="0.005"
                    value={formData.typo_probability}
                    onChange={(e) => setFormData(prev => ({ ...prev, typo_probability: parseFloat(e.target.value) || 0 }))}
                    className="w-full accent-amber-500 cursor-pointer"
                  />
                  <p className="text-[10px] text-neutral-500">
                    Injects natural human typos, hesitation backspaces, and correction key sequences.
                  </p>
                </div>

                {/* Typing Presets */}
                <div className="flex gap-2 pt-1">
                  <button
                    type="button"
                    onClick={() => setFormData(prev => ({ ...prev, typing_wpm: 45, typo_probability: 0.015 }))}
                    className="text-[10px] py-1 px-2.5 rounded bg-[#111520] hover:bg-[#181E2E] border border-[#1E2638] text-neutral-300 transition cursor-pointer"
                  >
                    Slow / Careful (45 WPM)
                  </button>
                  <button
                    type="button"
                    onClick={() => setFormData(prev => ({ ...prev, typing_wpm: 68, typo_probability: 0.035 }))}
                    className="text-[10px] py-1 px-2.5 rounded bg-[#111520] hover:bg-[#181E2E] border border-[#1E2638] text-neutral-300 transition cursor-pointer"
                  >
                    Natural User (68 WPM)
                  </button>
                  <button
                    type="button"
                    onClick={() => setFormData(prev => ({ ...prev, typing_wpm: 98, typo_probability: 0.055 }))}
                    className="text-[10px] py-1 px-2.5 rounded bg-[#111520] hover:bg-[#181E2E] border border-[#1E2638] text-neutral-300 transition cursor-pointer"
                  >
                    Fast Typer (98 WPM)
                  </button>
                </div>
              </div>

              {/* Parameter 3: Patience & Engagement Behavior */}
              <div className="bg-[#0D111D] border border-[#1E2638] rounded-xl p-4 space-y-4">
                <div className="flex items-center gap-2">
                  <HeartHandshake className="w-4 h-4 text-blue-400" />
                  <label className="text-xs font-bold text-white uppercase tracking-wider">
                    Dwell Patience & Engagement Profile
                  </label>
                </div>

                {/* Patience Index Slider */}
                <div className="space-y-1.5">
                  <div className="flex justify-between text-xs">
                    <span className="text-neutral-400">Patience Index (Dwell Duration)</span>
                    <span className="font-bold font-mono text-blue-400">{(formData.patience_index * 10).toFixed(1)} / 10</span>
                  </div>
                  <input
                    type="range"
                    min="0.1"
                    max="1.0"
                    step="0.05"
                    value={formData.patience_index}
                    onChange={(e) => setFormData(prev => ({ ...prev, patience_index: parseFloat(e.target.value) || 0.5 }))}
                    className="w-full accent-blue-500 cursor-pointer"
                  />
                  <div className="flex justify-between text-[10px] text-neutral-500">
                    <span>1.0 (Rapid Skimmer)</span>
                    <span>5.5 (Normal Dwell)</span>
                    <span>10.0 (Thorough Reader)</span>
                  </div>
                </div>

                {/* Engagement Rate Slider */}
                <div className="space-y-1.5 pt-2 border-t border-[#1E2638]/70">
                  <div className="flex justify-between text-xs">
                    <span className="text-neutral-400">Engagement Probability (Likes/Subscribes)</span>
                    <span className="font-bold font-mono text-blue-400">{(formData.engagement_rate * 100).toFixed(0)}%</span>
                  </div>
                  <input
                    type="range"
                    min="0.0"
                    max="0.5"
                    step="0.01"
                    value={formData.engagement_rate}
                    onChange={(e) => setFormData(prev => ({ ...prev, engagement_rate: parseFloat(e.target.value) || 0.1 }))}
                    className="w-full accent-blue-500 cursor-pointer"
                  />
                  <p className="text-[10px] text-neutral-500">
                    Controls whether GhostPilot triggers like, subscribe, or comment interactions during runs.
                  </p>
                </div>
              </div>
            </div>
          ) : (
            /* Niche Affiliations Tab */
            <div className="space-y-4">
              <div className="flex justify-between items-center">
                <div>
                  <h3 className="text-xs font-bold uppercase text-white">Target Niches Matrix</h3>
                  <p className="text-[11px] text-neutral-400">
                    Check niches to assign to this persona and adjust their relative probability weights.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    const allSelected = {};
                    allNiches.forEach(n => { allSelected[n.id] = 50; });
                    setSelectedNicheWeights(allSelected);
                  }}
                  className="text-[11px] text-blue-400 hover:text-blue-300 font-semibold cursor-pointer"
                >
                  Select All
                </button>
              </div>

              <div className="space-y-3">
                {allNiches.length === 0 ? (
                  <div className="p-8 text-center bg-[#0B0E17] border border-[#1E2638] rounded-xl text-neutral-500 text-xs">
                    No niches configured in the system yet. Create some in the Target Niches Hub.
                  </div>
                ) : (
                  allNiches.map((n) => {
                    const isAssigned = selectedNicheWeights[n.id] !== undefined;
                    return (
                      <div 
                        key={n.id} 
                        className={`border rounded-xl p-3.5 transition-all ${
                          isAssigned 
                            ? 'bg-[#0D1220] border-blue-500/40' 
                            : 'bg-[#0B0E17] border-[#1E2638]'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <label className="flex items-center gap-2.5 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={isAssigned}
                              onChange={() => toggleNiche(n.id)}
                              className="w-4 h-4 rounded bg-neutral-900 border-neutral-700 text-blue-600 focus:ring-0 cursor-pointer"
                            />
                            <div>
                              <span className="text-xs font-bold text-white block">{n.name}</span>
                              {n.keywords && (
                                <span className="text-[10px] text-neutral-400 truncate max-w-xs block">
                                  {Array.isArray(n.keywords) ? n.keywords.slice(0, 4).join(', ') : n.keywords}
                                </span>
                              )}
                            </div>
                          </label>
                          {isAssigned && (
                            <span className="text-xs font-bold font-mono text-blue-400 bg-blue-500/10 px-2 py-0.5 rounded border border-blue-500/20">
                              {selectedNicheWeights[n.id]}% Weight
                            </span>
                          )}
                        </div>

                        {isAssigned && (
                          <div className="mt-3 pt-2.5 border-t border-[#1E2638]/70">
                            <input
                              type="range"
                              min="5"
                              max="100"
                              step="5"
                              value={selectedNicheWeights[n.id]}
                              onChange={(e) => handleWeightChange(n.id, e.target.value)}
                              className="w-full accent-blue-500 cursor-pointer"
                            />
                          </div>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div className="p-4 border-t border-[#1E2638] bg-[#0D111D] flex justify-between items-center">
          <span className="text-[11px] text-neutral-400">
            {saveSuccess ? (
              <span className="text-emerald-400 font-semibold flex items-center gap-1">
                <Check className="w-3.5 h-3.5" /> Persona saved successfully!
              </span>
            ) : (
              'All parameters synchronize instantly with backend DAG runners.'
            )}
          </span>

          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={isSaving}
              className="px-4 py-2 text-xs text-neutral-400 hover:text-white rounded-lg hover:bg-neutral-800 transition cursor-pointer"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={isSaving}
              className="bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white text-xs font-semibold px-5 py-2 rounded-xl flex items-center gap-1.5 transition cursor-pointer shadow-lg shadow-blue-600/20"
            >
              {isSaving ? (
                <>
                  <div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />
                  <span>Saving...</span>
                </>
              ) : saveSuccess ? (
                <>
                  <Check className="w-3.5 h-3.5" />
                  <span>Saved!</span>
                </>
              ) : (
                <>
                  <Save className="w-3.5 h-3.5" />
                  <span>Save Persona & Niches</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
