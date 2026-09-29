import React, { useState, useEffect, useMemo } from 'react';
import {
  fetchCustomWorkflows, dispatchCustomWorkflow, fetchAutomationTasks,
  dispatchAutomationTask, fetchNiches, fetchProfiles,
} from '../../api';
import {
  Youtube, CheckCircle2, Clock, AlertCircle, Search, Check, ChevronRight,
  Smartphone, ArrowRight, Rocket, GitFork, Users, RefreshCw,
  ChevronLeft, Loader2, Settings2, ShieldCheck, PlayCircle
} from 'lucide-react';

function formatSeconds(sec) {
  const s = parseInt(sec, 10) || 0;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r.toString().padStart(2, '0')}`;
}

const STEP_COLORS = {
  SEARCH_TARGET_VIDEO: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
  VIDEO_SEARCH: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
  SCROLL_TARGET_VIDEO: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/30',
  WAIT_PLAYBACK: 'bg-purple-500/15 text-purple-300 border-purple-500/30',
  SPATIAL_ANCHOR_CLICK: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
  LIKE_VIDEO: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
  YT_LIKE_VIDEO: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
  SUBSCRIBE: 'bg-red-500/15 text-red-300 border-red-500/30',
  YT_SUBSCRIBE_CHANNEL: 'bg-red-500/15 text-red-300 border-red-500/30',
  START: 'bg-blue-500/15 text-blue-300 border-blue-500/30',
  GO_TO_URL: 'bg-blue-500/15 text-blue-300 border-blue-500/30',
};

function StepBadge({ step }) {
  const label = step.step_name || (step.type || '').replace(/_/g, ' ') || 'Step';
  const color = STEP_COLORS[step.type] || 'bg-neutral-800/60 text-neutral-400 border-neutral-700/60';
  return <span className={`inline-flex items-center px-2 py-0.5 rounded-md border text-[10px] font-medium shrink-0 ${color}`}>{label}</span>;
}

export default function LaunchCampaignHub({ onDispatched }) {
  const [loading, setLoading] = useState(true);
  const [workflows, setWorkflows] = useState([]);
  const [classicTasks, setClassicTasks] = useState([]);
  const [allProfiles, setAllProfiles] = useState([]);
  const [niches, setNiches] = useState([]);
  const [step, setStep] = useState(1);
  const [sourceType, setSourceType] = useState('VISUAL');
  const [selectedWorkflowId, setSelectedWorkflowId] = useState(null);
  const [workflowSearch, setWorkflowSearch] = useState('');
  const [selectedProfileIds, setSelectedProfileIds] = useState([]);
  const [profileSearch, setProfileSearch] = useState('');
  const [selectedNicheId, setSelectedNicheId] = useState('');
  const [campaignTitle, setCampaignTitle] = useState('');
  const [dispatching, setDispatching] = useState(false);
  const [dispatchError, setDispatchError] = useState(null);
  const [dispatchSuccess, setDispatchSuccess] = useState(false);

  useEffect(() => { loadAll(); }, []);

  const loadAll = async () => {
    setLoading(true);
    try {
      const [wfR, taskR, nicheR, profR] = await Promise.all([
        fetchCustomWorkflows().catch(() => ({ data: [] })),
        fetchAutomationTasks().catch(() => ({ data: [] })),
        fetchNiches().catch(() => ({ data: [] })),
        fetchProfiles().catch(() => ({ data: [] })),
      ]);
      setWorkflows(wfR.data || []);
      setClassicTasks(taskR.data || []);
      setNiches(nicheR.data || []);
      setAllProfiles(profR.data || []);
    } finally { setLoading(false); }
  };

  const selectedWorkflow = useMemo(() => {
    const list = sourceType === 'VISUAL' ? workflows : classicTasks;
    return list.find(w => w.id === selectedWorkflowId) || null;
  }, [sourceType, selectedWorkflowId, workflows, classicTasks]);

  const filteredWorkflows = useMemo(() => {
    const q = workflowSearch.toLowerCase();
    const list = sourceType === 'VISUAL' ? workflows : classicTasks;
    return q ? list.filter(w => (w.name || '').toLowerCase().includes(q) || (w.description || '').toLowerCase().includes(q)) : list;
  }, [workflowSearch, workflows, classicTasks, sourceType]);

  const filteredProfiles = useMemo(() => allProfiles.filter(p => {
    const q = profileSearch.toLowerCase();
    const ms = !q || (p.name || '').toLowerCase().includes(q) || (p.brand || '').toLowerCase().includes(q) || (p.proxy_ip || '').toLowerCase().includes(q);
    const mn = !selectedNicheId || String(p.niche) === String(selectedNicheId) || String(p.niche_id) === String(selectedNicheId);
    return ms && mn;
  }), [allProfiles, profileSearch, selectedNicheId]);

  const selectWorkflow = (wf) => {
    setSelectedWorkflowId(wf.id);
    setCampaignTitle(wf.name || 'Visual Campaign');
  };

  const handleLaunch = async () => {
    if (dispatching || !selectedWorkflowId || selectedProfileIds.length === 0) return;
    setDispatching(true); setDispatchError(null);
    try {
      if (sourceType === 'VISUAL') {
        const overrides = campaignTitle ? { campaign_title: campaignTitle } : {};
        await dispatchCustomWorkflow(selectedWorkflowId, selectedProfileIds, overrides);
      } else {
        await dispatchAutomationTask(selectedWorkflowId, selectedProfileIds);
      }
      setDispatchSuccess(true);
      setTimeout(() => { if (onDispatched) onDispatched(); }, 2000);
    } catch (err) {
      setDispatchError(err.response?.data?.error || err.response?.data?.detail || err.message || 'Dispatch failed.');
    } finally { setDispatching(false); }
  };

  if (loading) return (
    <div className="flex-1 flex flex-col items-center justify-center gap-4 text-neutral-400">
      <Loader2 className="w-8 h-8 animate-spin text-blue-500" />
      <p className="text-sm">Loading campaign data...</p>
    </div>
  );

  if (dispatchSuccess) return (
    <div className="flex-1 flex flex-col items-center justify-center gap-6 text-center px-8">
      <div className="w-24 h-24 rounded-full bg-emerald-500/15 border border-emerald-500/30 flex items-center justify-center">
        <Rocket className="w-10 h-10 text-emerald-400" />
      </div>
      <div>
        <h2 className="text-2xl font-bold text-white mb-2">Campaign Launched!</h2>
        <p className="text-sm text-neutral-400">Dispatched to <span className="text-emerald-400 font-semibold">{selectedProfileIds.length} profile{selectedProfileIds.length !== 1 ? 's' : ''}</span>. Redirecting to Execution Console...</p>
      </div>
    </div>
  );

  const STEPS = [
    { n: 1, label: 'Select Workflow', icon: GitFork },
    { n: 2, label: 'Assign Profiles', icon: Users },
    { n: 3, label: 'Workflow Configuration', icon: Settings2 },
    { n: 4, label: 'Review & Launch', icon: Rocket },
  ];

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden">
      <div className="px-4 sm:px-8 pt-4 sm:pt-6 pb-3 sm:pb-4 border-b border-neutral-800/80 flex items-center justify-between gap-4 shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-blue-600/20 shrink-0">
            <Rocket className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="text-base sm:text-lg font-bold text-white">Launch Campaign</h1>
            <p className="text-xs text-neutral-400">Select workflow - assign profiles - configure - dispatch</p>
          </div>
        </div>
        <button onClick={loadAll} className="p-2 rounded-lg bg-neutral-800/60 hover:bg-neutral-700 text-neutral-400 hover:text-white transition cursor-pointer" title="Refresh">
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      <div className="px-4 sm:px-8 pt-3 sm:pt-4 pb-2 shrink-0 overflow-x-auto">
        <div className="flex items-center min-w-max">
          {STEPS.map(({ n, label }, i) => {
            const done = step > n; const active = step === n;
            return (
              <React.Fragment key={n}>
                <button onClick={() => (done || active) && setStep(n)}
                  className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition ${active ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40 cursor-pointer' : done ? 'text-emerald-400 hover:bg-neutral-800/60 cursor-pointer' : 'text-neutral-600 cursor-default'}`}>
                  <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold border ${active ? 'bg-blue-600 border-blue-500 text-white' : done ? 'bg-emerald-500/20 border-emerald-500/50 text-emerald-400' : 'bg-neutral-800 border-neutral-700 text-neutral-500'}`}>
                    {done ? <Check className="w-3 h-3 stroke-[3]" /> : n}
                  </span>
                  <span>{label}</span>
                </button>
                {i < STEPS.length - 1 && <div className={`h-px w-8 sm:w-12 mx-1 ${done ? 'bg-emerald-500/40' : 'bg-neutral-800'}`} />}
              </React.Fragment>
            );
          })}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-4 sm:px-8 py-4 sm:py-5">

        {step === 1 && (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-3 flex-wrap">
              <div>
                <h2 className="text-sm font-semibold text-white">Select a Workflow</h2>
                <p className="text-xs text-neutral-400 mt-0.5">Choose the automation workflow to run on your devices.</p>
              </div>
              <div className="flex gap-2">
                {[['VISUAL', workflows.length], ['CLASSIC', classicTasks.length]].map(([t, count]) => {
                  if (t === 'CLASSIC' && count === 0) return null;
                  return (
                    <button key={t} onClick={() => { setSourceType(t); setSelectedWorkflowId(null); }}
                      className={`px-3 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${sourceType === t ? 'bg-blue-600 text-white' : 'bg-neutral-800 text-neutral-400 hover:text-neutral-200'}`}>
                      {t === 'VISUAL' ? 'Visual' : 'Classic'} ({count})
                    </button>
                  );
                })}
              </div>
            </div>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-neutral-500" />
              <input value={workflowSearch} onChange={e => setWorkflowSearch(e.target.value)} placeholder="Search workflows..."
                className="w-full pl-9 pr-4 py-2 bg-neutral-900 border border-neutral-800 rounded-xl text-xs text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500/60 transition" />
            </div>
            {filteredWorkflows.length === 0 ? (
              <div className="py-16 flex flex-col items-center gap-3 text-center">
                <GitFork className="w-10 h-10 text-neutral-700" />
                <p className="text-sm text-neutral-400 font-medium">No workflows found</p>
                <p className="text-xs text-neutral-500">Build one in the Visual Workflows builder tab first.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
                {filteredWorkflows.map(wf => {
                  const isSelected = selectedWorkflowId === wf.id;
                  const steps = (wf.journeys || [])[0]?.steps || [];
                  return (
                    <div key={wf.id} onClick={() => selectWorkflow(wf)}
                      className={`p-4 rounded-xl border cursor-pointer transition-all flex flex-col gap-3 group ${isSelected ? 'bg-blue-950/40 border-blue-500 ring-1 ring-blue-500/30 shadow-lg shadow-blue-950/40' : 'bg-neutral-900/60 border-neutral-800 hover:border-neutral-600 hover:bg-neutral-900/90'}`}>
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="font-semibold text-sm text-white truncate">{wf.name}</span>
                            <span className="text-[10px] px-1.5 py-0.5 rounded bg-neutral-800 text-neutral-400 font-mono shrink-0">{wf.platform || 'YOUTUBE'}</span>
                          </div>
                          {wf.description && <p className="text-[11px] text-neutral-400 mt-1 line-clamp-2">{wf.description}</p>}
                        </div>
                        <div className={`w-5 h-5 rounded-full border flex items-center justify-center shrink-0 transition ${isSelected ? 'bg-blue-600 border-blue-500' : 'border-neutral-700 group-hover:border-neutral-500'}`}>
                          {isSelected && <Check className="w-3 h-3 text-white stroke-[3]" />}
                        </div>
                      </div>
                      <div className="border-t border-neutral-800/80 pt-2.5">
                        {steps.length === 0 ? <span className="text-[11px] text-neutral-600 italic">No steps defined</span> : (
                          <div className="flex items-center gap-1.5 flex-wrap gap-y-1">
                            {steps.slice(0, 4).map((s, i) => (
                              <React.Fragment key={i}>
                                <StepBadge step={s} />
                                {i < Math.min(steps.length - 1, 3) && <ChevronRight className="w-3 h-3 text-neutral-700 shrink-0" />}
                              </React.Fragment>
                            ))}
                            {steps.length > 4 && <span className="text-[10px] px-1.5 py-0.5 rounded bg-neutral-800 text-neutral-500">+{steps.length - 4}</span>}
                          </div>
                        )}
                      </div>
                      <div className="flex items-center justify-between text-[10px] text-neutral-500">
                        <span>{steps.length} step{steps.length !== 1 ? 's' : ''}</span>
                        <span>{wf.updated_at ? new Date(wf.updated_at).toLocaleDateString() : ''}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
            <div className="pt-2 flex justify-end">
              <button onClick={() => setStep(2)} disabled={!selectedWorkflowId}
                className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold transition disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer shadow-lg shadow-blue-600/20">
                Continue to Profiles <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-3 flex-wrap">
              <div>
                <h2 className="text-sm font-semibold text-white">Assign Target Profiles</h2>
                <p className="text-xs text-neutral-400 mt-0.5">Choose which Android profiles will run <span className="text-blue-400 font-medium">{selectedWorkflow?.name}</span>.</p>
              </div>
              <span className={`px-2.5 py-1 rounded-lg border text-xs font-medium ${selectedProfileIds.length > 0 ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-400' : 'bg-neutral-800 border-neutral-700 text-neutral-400'}`}>
                {selectedProfileIds.length} selected
              </span>
            </div>
            <div className="flex gap-2 flex-wrap">
              <div className="relative flex-1 min-w-[180px]">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-neutral-500" />
                <input value={profileSearch} onChange={e => setProfileSearch(e.target.value)} placeholder="Search profiles..."
                  className="w-full pl-9 pr-4 py-2 bg-neutral-900 border border-neutral-800 rounded-xl text-xs text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500/60 transition" />
              </div>
              {niches.length > 0 && (
                <select value={selectedNicheId} onChange={e => setSelectedNicheId(e.target.value)}
                  className="py-2 px-3 bg-neutral-900 border border-neutral-800 rounded-xl text-xs text-neutral-300 focus:outline-none cursor-pointer">
                  <option value="">All Niches</option>
                  {niches.map(n => <option key={n.id} value={n.id}>{n.name}</option>)}
                </select>
              )}
              <button onClick={() => selectedProfileIds.length === filteredProfiles.length && filteredProfiles.length > 0 ? setSelectedProfileIds([]) : setSelectedProfileIds(filteredProfiles.map(p => p.id))}
                className="px-3 py-2 bg-neutral-800 hover:bg-neutral-700 text-neutral-300 hover:text-white rounded-xl text-xs font-medium transition cursor-pointer border border-neutral-700">
                {selectedProfileIds.length === filteredProfiles.length && filteredProfiles.length > 0 ? 'Deselect All' : 'Select All'}
              </button>
              <button onClick={() => setSelectedProfileIds(filteredProfiles.filter(p => p.status === 'ONLINE' || p.is_connected || p.online).map(p => p.id))}
                className="px-3 py-2 bg-emerald-900/40 hover:bg-emerald-900/60 text-emerald-400 rounded-xl text-xs font-medium transition cursor-pointer border border-emerald-700/40">
                Online Only
              </button>
            </div>
            {filteredProfiles.length === 0 ? (
              <div className="py-12 text-center text-neutral-500 text-sm">No profiles match.</div>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-2.5">
                {filteredProfiles.map(p => {
                  const isSel = selectedProfileIds.includes(p.id);
                  const isOnline = p.status === 'ONLINE' || p.is_connected || p.online;
                  return (
                    <div key={p.id} onClick={() => setSelectedProfileIds(prev => prev.includes(p.id) ? prev.filter(x => x !== p.id) : [...prev, p.id])}
                      className={`p-3 rounded-xl border cursor-pointer transition-all flex flex-col gap-2 ${isSel ? 'bg-blue-950/40 border-blue-500 ring-1 ring-blue-500/30' : 'bg-neutral-900/50 border-neutral-800 hover:border-neutral-600'}`}>
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          <div className="relative shrink-0">
                            <Smartphone className="w-6 h-6 text-neutral-400" />
                            <span className={`absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full border-2 border-neutral-900 ${isOnline ? 'bg-emerald-400' : 'bg-neutral-600'}`} />
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs font-semibold text-white truncate">{p.name || `Profile #${p.id}`}</p>
                            <p className="text-[10px] text-neutral-500 truncate">{p.brand || p.model_name || 'Android'}</p>
                          </div>
                        </div>
                        <div className={`w-4 h-4 rounded-full border flex items-center justify-center shrink-0 ${isSel ? 'bg-blue-600 border-blue-500' : 'border-neutral-600'}`}>
                          {isSel && <Check className="w-2.5 h-2.5 text-white stroke-[3]" />}
                        </div>
                      </div>
                      {p.proxy_ip && <p className="text-[10px] text-neutral-600 font-mono truncate">{p.proxy_ip}</p>}
                    </div>
                  );
                })}
              </div>
            )}
            <div className="pt-2 flex items-center justify-between">
              <button onClick={() => setStep(1)} className="flex items-center gap-2 px-4 py-2 rounded-xl bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-xs font-medium transition cursor-pointer">
                <ChevronLeft className="w-4 h-4" /> Back
              </button>
              <button onClick={() => setStep(3)} disabled={selectedProfileIds.length === 0}
                className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold transition disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer shadow-lg shadow-blue-600/20">
                Continue to Configuration <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {step === 3 && (
          <div className="space-y-5 max-w-4xl mx-auto">
            <div>
              <h2 className="text-sm font-semibold text-white">Workflow Configuration</h2>
              <p className="text-xs text-neutral-400 mt-0.5">Pre-assigned action sequences and execution parameters from your workflow definition.</p>
            </div>

            <div className="p-4 bg-blue-950/20 border border-blue-800/40 rounded-xl flex items-start gap-3">
              <ShieldCheck className="w-5 h-5 text-blue-400 shrink-0 mt-0.5" />
              <div>
                <h4 className="text-xs font-semibold text-blue-300">Pre-assigned Configuration Active</h4>
                <p className="text-xs text-neutral-400 mt-1 leading-relaxed">
                  All action sequences, target URLs, search keywords, calibrated spatial touchpoints, and timers have already been assigned in your visual workflow builder. This task will execute directly on the target devices using these pre-assigned parameters without any runtime overrides.
                </p>
              </div>
            </div>

            <div className="p-4 bg-neutral-900/60 border border-neutral-800 rounded-xl space-y-2">
              <label className="text-xs font-medium text-neutral-300">Campaign Title</label>
              <input value={campaignTitle} onChange={e => setCampaignTitle(e.target.value)} placeholder="Campaign Title"
                className="w-full px-3 py-2 bg-neutral-950 border border-neutral-700 rounded-xl text-xs text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500/60 transition" />
            </div>

            <div className="p-4 bg-neutral-900/60 border border-neutral-800 rounded-xl space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-semibold text-neutral-300">Assigned Journey Steps in Sequence</h3>
                <span className="text-[11px] text-neutral-500 font-mono">{(selectedWorkflow?.journeys?.[0]?.steps || []).length} steps</span>
              </div>
              <div className="space-y-2">
                {(selectedWorkflow?.journeys?.[0]?.steps || []).length === 0 ? (
                  <p className="text-xs text-neutral-500 italic py-2">No journey steps defined in this workflow.</p>
                ) : (
                  (selectedWorkflow?.journeys?.[0]?.steps || []).map((s, idx) => (
                    <div key={idx} className="p-3 bg-neutral-950/70 border border-neutral-800/80 rounded-xl flex items-center justify-between gap-3">
                      <div className="flex items-center gap-3 min-w-0">
                        <span className="w-6 h-6 rounded-lg bg-neutral-800 border border-neutral-700 text-neutral-400 flex items-center justify-center text-xs font-mono shrink-0">
                          {idx + 1}
                        </span>
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <StepBadge step={s} />
                            <span className="text-xs font-medium text-white truncate">{s.step_name || s.type}</span>
                          </div>
                          <div className="text-[11px] text-neutral-400 mt-1 flex flex-wrap gap-x-3 gap-y-1">
                            {s.keyword && <span className="text-amber-300/90 font-medium">Query: <span className="text-neutral-300 font-normal">"{s.keyword}"</span></span>}
                            {s.url && <span className="text-blue-300/90 font-medium">URL: <span className="text-neutral-300 font-normal">{s.url}</span></span>}
                            {s.video_url && <span className="text-rose-300/90 font-medium">Target Video: <span className="text-neutral-300 font-normal">{s.video_url}</span></span>}
                            {s.search_anchor && <span className="text-emerald-300/90 font-medium">Search Anchor: <span className="text-neutral-300 font-normal">{s.search_anchor}</span></span>}
                            {s.spatial_anchor && <span className="text-emerald-300/90 font-medium">Spatial Anchor: <span className="text-neutral-300 font-normal">{s.spatial_anchor}</span></span>}
                            {(s.dwell_min || s.dwell_max) && <span className="text-purple-300/90 font-medium">Duration: <span className="text-neutral-300 font-normal">{s.dwell_min || 0}s - {s.dwell_max || 0}s</span></span>}
                          </div>
                        </div>
                      </div>
                      <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                    </div>
                  ))
                )}
              </div>
            </div>

            <div className="pt-2 flex items-center justify-between">
              <button onClick={() => setStep(2)} className="flex items-center gap-2 px-4 py-2 rounded-xl bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-xs font-medium transition cursor-pointer">
                <ChevronLeft className="w-4 h-4" /> Back
              </button>
              <button onClick={() => setStep(4)}
                className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold transition cursor-pointer shadow-lg shadow-blue-600/20">
                Continue to Review <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        )}

        {step === 4 && (
          <div className="space-y-5 max-w-3xl mx-auto">
            <div>
              <h2 className="text-sm font-semibold text-white">Review and Launch</h2>
              <p className="text-xs text-neutral-400 mt-0.5">Confirm details before dispatching to devices.</p>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div className="p-4 rounded-xl bg-blue-950/30 border border-blue-800/40 space-y-1">
                <div className="flex items-center gap-2 text-xs text-blue-400 font-semibold"><GitFork className="w-3.5 h-3.5" /> Workflow</div>
                <p className="text-sm text-white font-medium truncate">{selectedWorkflow?.name || ''}</p>
                <p className="text-[11px] text-neutral-400">{(selectedWorkflow?.journeys?.[0]?.steps?.length || 0)} steps • {selectedWorkflow?.platform || 'YOUTUBE'}</p>
              </div>
              <div className="p-4 rounded-xl bg-emerald-950/30 border border-emerald-800/40 space-y-1">
                <div className="flex items-center gap-2 text-xs text-emerald-400 font-semibold"><Users className="w-3.5 h-3.5" /> Profiles</div>
                <p className="text-2xl font-bold text-white">{selectedProfileIds.length}</p>
                <p className="text-[11px] text-neutral-400">device{selectedProfileIds.length !== 1 ? 's' : ''} assigned</p>
              </div>
              <div className="p-4 rounded-xl bg-purple-950/30 border border-purple-800/40 space-y-1">
                <div className="flex items-center gap-2 text-xs text-purple-400 font-semibold"><ShieldCheck className="w-3.5 h-3.5" /> Execution Mode</div>
                <p className="text-sm text-white font-medium">Pre-assigned</p>
                <p className="text-[11px] text-neutral-400">Direct Workflow Actions</p>
              </div>
            </div>
            <div className="p-4 bg-neutral-900/60 border border-neutral-800 rounded-xl space-y-3">
              <h3 className="text-xs font-semibold text-neutral-300">Target Action Flow</h3>
              <div className="flex items-center gap-2 flex-wrap">
                {(selectedWorkflow?.journeys?.[0]?.steps || []).map((s, idx) => (
                  <React.Fragment key={idx}>
                    <StepBadge step={s} />
                    {idx < (selectedWorkflow?.journeys?.[0]?.steps?.length || 0) - 1 && (
                      <ChevronRight className="w-3 h-3 text-neutral-600 shrink-0" />
                    )}
                  </React.Fragment>
                ))}
              </div>
            </div>
            {dispatchError && (
              <div className="p-3 bg-red-950/50 border border-red-800/80 rounded-xl text-red-200 text-xs flex items-start gap-2">
                <AlertCircle className="w-4 h-4 text-red-400 shrink-0 mt-0.5" /><span>{dispatchError}</span>
              </div>
            )}
            <div className="pt-2 flex items-center justify-between">
              <button onClick={() => setStep(3)} className="flex items-center gap-2 px-4 py-2 rounded-xl bg-neutral-800 hover:bg-neutral-700 text-neutral-300 text-xs font-medium transition cursor-pointer">
                <ChevronLeft className="w-4 h-4" /> Back
              </button>
              <button onClick={handleLaunch} disabled={dispatching}
                className="flex items-center gap-2.5 px-7 py-3 rounded-xl bg-gradient-to-r from-blue-600 via-blue-500 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white text-sm font-bold transition disabled:opacity-60 cursor-pointer shadow-xl shadow-blue-600/25 active:scale-[0.98]">
                {dispatching ? <><Loader2 className="w-4 h-4 animate-spin" /> Launching...</> : <><Rocket className="w-4 h-4" /> Launch Campaign</>}
              </button>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}
