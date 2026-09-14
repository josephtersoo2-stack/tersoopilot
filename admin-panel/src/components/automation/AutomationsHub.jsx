import React, { useState, useEffect } from 'react';
import {
  Calendar,
  Clock,
  Play,
  Pause,
  Plus,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  XCircle,
  Clock3,
  Layers,
  Users,
  Sliders,
  ChevronRight,
  ChevronDown,
  Trash2,
  Edit2,
  History,
  AlertTriangle,
  Zap,
  Activity,
  ArrowRight,
  Filter,
  Check,
  X,
  RotateCcw
} from 'lucide-react';
import {
  fetchAutomations,
  createAutomation,
  updateAutomation,
  deleteAutomation,
  runAutomationNow,
  pauseAutomation,
  resumeAutomation,
  fetchAutomationRuns,
  fetchRunExecutions,
  cancelAutomationRun,
  fetchAutomationTasks,
  fetchNiches,
  fetchProfiles
} from '../../api';

export default function AutomationsHub({ onOpenDispatch }) {
  const [automations, setAutomations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [tasks, setTasks] = useState([]);
  const [niches, setNiches] = useState([]);
  const [profiles, setProfiles] = useState([]);

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [editingAutomation, setEditingAutomation] = useState(null);

  // Run History Drawer / View state
  const [selectedAutomationForHistory, setSelectedAutomationForHistory] = useState(null);
  const [runs, setRuns] = useState([]);
  const [loadingRuns, setLoadingRuns] = useState(false);
  const [expandedRunId, setExpandedRunId] = useState(null);
  const [runExecutions, setRunExecutions] = useState({});
  const [loadingExecutions, setLoadingExecutions] = useState({});

  // Action status message
  const [statusMsg, setStatusMsg] = useState(null);

  useEffect(() => {
    loadInitialData();
  }, []);

  const showNotification = (text, type = 'success') => {
    setStatusMsg({ text, type });
    setTimeout(() => setStatusMsg(null), 4000);
  };

  const loadInitialData = async () => {
    setLoading(true);
    try {
      const [autoRes, taskRes, nicheRes, profRes] = await Promise.all([
        fetchAutomations(),
        fetchAutomationTasks(),
        fetchNiches(),
        fetchProfiles()
      ]);
      setAutomations(autoRes.data || []);
      setTasks(taskRes.data || []);
      setNiches(nicheRes.data || []);
      setProfiles(profRes.data || []);
    } catch (err) {
      console.error('Failed to load automations data:', err);
      showNotification('Failed to load automations data', 'error');
    } finally {
      setLoading(false);
    }
  };

  const refreshAutomations = async () => {
    setRefreshing(true);
    try {
      const res = await fetchAutomations();
      setAutomations(res.data || []);
    } catch (err) {
      console.error('Failed to refresh automations:', err);
    } finally {
      setRefreshing(false);
    }
  };

  const handleRunNow = async (automation) => {
    try {
      const res = await runAutomationNow(automation.id);
      showNotification(`Triggered run for '${automation.name}'. Run Key: ${res.data.run_key}`);
      refreshAutomations();
      if (selectedAutomationForHistory?.id === automation.id) {
        loadHistory(automation);
      }
    } catch (err) {
      const errMsg = err.response?.data?.error || 'Failed to trigger automation run.';
      showNotification(errMsg, 'error');
    }
  };

  const handleTogglePause = async (automation) => {
    try {
      if (automation.enabled) {
        await pauseAutomation(automation.id);
        showNotification(`Paused '${automation.name}' schedule.`);
      } else {
        await resumeAutomation(automation.id);
        showNotification(`Resumed '${automation.name}' schedule.`);
      }
      refreshAutomations();
    } catch (err) {
      showNotification('Failed to toggle automation state', 'error');
    }
  };

  const handleDelete = async (automation) => {
    if (!window.confirm(`Are you sure you want to delete automation '${automation.name}'?`)) return;
    try {
      await deleteAutomation(automation.id);
      showNotification(`Deleted automation '${automation.name}'.`);
      setAutomations((prev) => prev.filter((a) => a.id !== automation.id));
      if (selectedAutomationForHistory?.id === automation.id) {
        setSelectedAutomationForHistory(null);
      }
    } catch (err) {
      showNotification('Failed to delete automation', 'error');
    }
  };

  const loadHistory = async (automation) => {
    setSelectedAutomationForHistory(automation);
    setLoadingRuns(true);
    setRuns([]);
    try {
      const res = await fetchAutomationRuns();
      const filtered = (res.data || []).filter((r) => r.automation === automation.id);
      setRuns(filtered);
    } catch (err) {
      showNotification('Failed to load run history', 'error');
    } finally {
      setLoadingRuns(false);
    }
  };

  const toggleRunExecutions = async (runId) => {
    if (expandedRunId === runId) {
      setExpandedRunId(null);
      return;
    }
    setExpandedRunId(runId);
    if (!runExecutions[runId]) {
      setLoadingExecutions((prev) => ({ ...prev, [runId]: true }));
      try {
        const res = await fetchRunExecutions(runId);
        setRunExecutions((prev) => ({ ...prev, [runId]: res.data || [] }));
      } catch (err) {
        showNotification('Failed to load executions for run', 'error');
      } finally {
        setLoadingExecutions((prev) => ({ ...prev, [runId]: false }));
      }
    }
  };

  const handleCancelRun = async (runId) => {
    if (!window.confirm('Cancel this active automation run and abort pending work?')) return;
    try {
      await cancelAutomationRun(runId);
      showNotification('Automation run cancelled.');
      if (selectedAutomationForHistory) {
        loadHistory(selectedAutomationForHistory);
      }
    } catch (err) {
      showNotification(err.response?.data?.error || 'Failed to cancel run', 'error');
    }
  };

  // Helper for human-readable schedule description
  const formatSchedule = (automation) => {
    const cfg = automation.schedule_config || {};
    const tz = automation.timezone || 'UTC';
    if (automation.schedule_type === 'DAILY') {
      return `Daily at ${cfg.time || '00:00'} (${tz})`;
    }
    if (automation.schedule_type === 'INTERVAL') {
      return `Every ${cfg.interval_minutes || 60} minutes`;
    }
    if (automation.schedule_type === 'ONE_TIME') {
      return cfg.run_at ? `One-time at ${new Date(cfg.run_at).toLocaleString()}` : 'One-time (Immediate)';
    }
    return automation.schedule_type;
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-neutral-400">
        <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin mb-3" />
        <p className="text-xs font-mono">Loading Automations Control Plane...</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Toast Notification */}
      {statusMsg && (
        <div
          className={`fixed bottom-6 right-6 z-50 px-4 py-3 rounded-xl shadow-2xl border text-sm flex items-center gap-2 backdrop-blur-md transition-all ${
            statusMsg.type === 'error'
              ? 'bg-rose-950/90 text-rose-200 border-rose-800'
              : 'bg-emerald-950/90 text-emerald-200 border-emerald-800'
          }`}
        >
          {statusMsg.type === 'error' ? <AlertCircle className="w-4 h-4 text-rose-400" /> : <CheckCircle2 className="w-4 h-4 text-emerald-400" />}
          <span>{statusMsg.text}</span>
        </div>
      )}

      {/* Header Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-[#0D111A] p-6 rounded-2xl border border-[#1E2638]">
        <div>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-600 via-indigo-600 to-cyan-500 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <Calendar className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold text-white tracking-tight">Automations Hub</h1>
              <p className="text-xs text-neutral-400">
                Server-orchestrated unattended batch scheduling, target policies, and run lifecycle supervision.
              </p>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={refreshAutomations}
            disabled={refreshing}
            className="px-3.5 py-2 rounded-xl bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 hover:text-white text-xs font-medium border border-[#1E2638] flex items-center gap-2 transition-colors"
            title="Refresh automations"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin text-blue-400' : ''}`} />
            <span>Sync</span>
          </button>
          <button
            onClick={() => {
              setEditingAutomation(null);
              setShowModal(true);
            }}
            className="px-4 py-2 rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white text-xs font-medium flex items-center gap-2 shadow-lg shadow-blue-600/20 transition-all"
          >
            <Plus className="w-4 h-4" />
            <span>Create Automation</span>
          </button>
        </div>
      </div>

      {/* Automations Table / Grid */}
      <div className="bg-[#0D111A] rounded-2xl border border-[#1E2638] overflow-hidden">
        <div className="p-4 border-b border-[#1E2638] flex items-center justify-between bg-[#0A0D14]/50">
          <div className="flex items-center gap-2">
            <Sliders className="w-4 h-4 text-blue-400" />
            <span className="text-xs font-bold uppercase tracking-wider text-neutral-300">Active Schedules & Rules</span>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-blue-500/10 text-blue-400 border border-blue-500/20">
              {automations.length} total
            </span>
          </div>
        </div>

        {automations.length === 0 ? (
          <div className="py-16 text-center text-neutral-400 space-y-3">
            <Clock className="w-10 h-10 mx-auto text-neutral-600" />
            <p className="text-sm font-medium">No automated schedules configured yet.</p>
            <p className="text-xs text-neutral-500 max-w-sm mx-auto">
              Create your first automation rule to run unattended profile warmup, YouTube browsing, or custom DAG recipes on schedule.
            </p>
            <button
              onClick={() => {
                setEditingAutomation(null);
                setShowModal(true);
              }}
              className="mt-2 inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium"
            >
              <Plus className="w-4 h-4" />
              <span>Create Schedule</span>
            </button>
          </div>
        ) : (
          <div className="divide-y divide-[#1E2638]">
            {automations.map((automation) => {
              const taskObj = tasks.find((t) => t.id === automation.task) || { name: 'Unknown Task', category: 'GENERAL' };
              return (
                <div
                  key={automation.id}
                  className="p-5 hover:bg-[#121722] transition-colors flex flex-col lg:flex-row lg:items-center justify-between gap-4"
                >
                  {/* Left: Info */}
                  <div className="space-y-2 flex-1 min-w-0">
                    <div className="flex items-center gap-3">
                      <h3 className="font-semibold text-sm text-white truncate">{automation.name}</h3>
                      <span
                        className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border flex items-center gap-1 ${
                          automation.enabled
                            ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'
                            : 'bg-neutral-800 text-neutral-400 border-neutral-700'
                        }`}
                      >
                        <span className={`w-1.5 h-1.5 rounded-full ${automation.enabled ? 'bg-emerald-400 animate-pulse' : 'bg-neutral-500'}`} />
                        {automation.enabled ? 'SCHEDULED' : 'PAUSED'}
                      </span>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded-md bg-neutral-800/80 text-neutral-300 border border-neutral-700">
                        {automation.schedule_type}
                      </span>
                    </div>

                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-neutral-400">
                      <div className="flex items-center gap-1.5 text-neutral-300">
                        <Zap className="w-3.5 h-3.5 text-amber-400" />
                        <span>Task: <strong className="text-white font-medium">{taskObj.name}</strong></span>
                      </div>
                      <div className="flex items-center gap-1 text-neutral-400">
                        <Clock className="w-3.5 h-3.5 text-blue-400" />
                        <span>{formatSchedule(automation)}</span>
                      </div>
                      <div className="flex items-center gap-1 text-neutral-400">
                        <Users className="w-3.5 h-3.5 text-indigo-400" />
                        <span>Target: <span className="text-neutral-200">{automation.selection_mode}</span></span>
                      </div>
                    </div>

                    {/* Safeguards / Policies info */}
                    <div className="flex flex-wrap items-center gap-2 pt-1 text-[11px] text-neutral-500">
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        Concurrency: <strong className="text-neutral-300">{automation.concurrency_limit}</strong>
                      </span>
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        Cooldown: <strong className="text-neutral-300">{automation.cooldown_minutes}m</strong>
                      </span>
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        Retries: <strong className="text-neutral-300">{automation.max_retries}</strong>
                      </span>
                      <span className="px-2 py-0.5 rounded bg-[#161B26] border border-[#1E2638]">
                        Breaker: <strong className="text-neutral-300">{automation.failure_threshold_percent}%</strong>
                      </span>
                      {automation.last_run_at && (
                        <span>Last Run: {new Date(automation.last_run_at).toLocaleString()}</span>
                      )}
                    </div>
                  </div>

                  {/* Right: Actions */}
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={() => handleRunNow(automation)}
                      className="px-3 py-1.5 rounded-lg bg-blue-600/20 hover:bg-blue-600/30 text-blue-300 border border-blue-500/30 text-xs font-medium flex items-center gap-1.5 transition-colors"
                      title="Trigger run immediately"
                    >
                      <Play className="w-3.5 h-3.5 fill-blue-300" />
                      <span>Run Now</span>
                    </button>

                    <button
                      onClick={() => handleTogglePause(automation)}
                      className={`px-3 py-1.5 rounded-lg border text-xs font-medium flex items-center gap-1.5 transition-colors ${
                        automation.enabled
                          ? 'bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border-amber-500/30'
                          : 'bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
                      }`}
                      title={automation.enabled ? 'Pause schedule' : 'Resume schedule'}
                    >
                      {automation.enabled ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5" />}
                      <span>{automation.enabled ? 'Pause' : 'Resume'}</span>
                    </button>

                    <button
                      onClick={() => loadHistory(automation)}
                      className={`px-3 py-1.5 rounded-lg border text-xs font-medium flex items-center gap-1.5 transition-colors ${
                        selectedAutomationForHistory?.id === automation.id
                          ? 'bg-indigo-600/30 text-indigo-200 border-indigo-500/50'
                          : 'bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 border-[#1E2638]'
                      }`}
                      title="View execution run history"
                    >
                      <History className="w-3.5 h-3.5 text-indigo-400" />
                      <span>History</span>
                    </button>

                    <button
                      onClick={() => {
                        setEditingAutomation(automation);
                        setShowModal(true);
                      }}
                      className="p-1.5 rounded-lg bg-[#161B26] hover:bg-[#1E2638] text-neutral-400 hover:text-white border border-[#1E2638] transition-colors"
                      title="Edit automation"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>

                    <button
                      onClick={() => handleDelete(automation)}
                      className="p-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 transition-colors"
                      title="Delete automation"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* History & Executions Drawer */}
      {selectedAutomationForHistory && (
        <div className="bg-[#0D111A] rounded-2xl border border-[#1E2638] overflow-hidden space-y-4 p-6">
          <div className="flex items-center justify-between pb-4 border-b border-[#1E2638]">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-lg bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
                <History className="w-4 h-4 text-indigo-400" />
              </div>
              <div>
                <h2 className="text-sm font-bold text-white flex items-center gap-2">
                  <span>Run History: {selectedAutomationForHistory.name}</span>
                  <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-neutral-800 text-neutral-400">
                    {runs.length} runs recorded
                  </span>
                </h2>
                <p className="text-xs text-neutral-400">
                  Inspect execution batches, profile progress, and self-healing recovery events.
                </p>
              </div>
            </div>
            <button
              onClick={() => setSelectedAutomationForHistory(null)}
              className="text-neutral-400 hover:text-white p-1 rounded-lg hover:bg-[#161B26]"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {loadingRuns ? (
            <div className="py-10 text-center text-neutral-400 text-xs font-mono">
              Loading run records...
            </div>
          ) : runs.length === 0 ? (
            <div className="py-10 text-center text-neutral-400 text-xs">
              No historical runs logged yet for this automation.
            </div>
          ) : (
            <div className="space-y-3">
              {runs.map((run) => {
                const isExpanded = expandedRunId === run.id;
                const execs = runExecutions[run.id] || [];
                const isLoadingExecs = loadingExecutions[run.id];

                return (
                  <div key={run.id} className="rounded-xl border border-[#1E2638] bg-[#0A0D14] overflow-hidden">
                    {/* Run Header Row */}
                    <div
                      onClick={() => toggleRunExecutions(run.id)}
                      className="p-4 cursor-pointer hover:bg-[#121722] flex flex-col md:flex-row md:items-center justify-between gap-3 transition-colors"
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        {isExpanded ? (
                          <ChevronDown className="w-4 h-4 text-neutral-400 shrink-0" />
                        ) : (
                          <ChevronRight className="w-4 h-4 text-neutral-400 shrink-0" />
                        )}
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="font-mono text-xs text-blue-400 font-semibold">{run.run_key}</span>
                            <span
                              className={`text-[9px] font-bold px-1.5 py-0.5 rounded border uppercase ${
                                run.status === 'COMPLETED'
                                  ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'
                                  : run.status === 'RUNNING'
                                  ? 'bg-blue-500/10 text-blue-300 border-blue-500/30 animate-pulse'
                                  : run.status === 'PARTIAL'
                                  ? 'bg-amber-500/10 text-amber-300 border-amber-500/30'
                                  : 'bg-rose-500/10 text-rose-300 border-rose-500/30'
                              }`}
                            >
                              {run.status}
                            </span>
                          </div>
                          <p className="text-[11px] text-neutral-400 pt-0.5">
                            Scheduled: {new Date(run.scheduled_for).toLocaleString()}
                          </p>
                        </div>
                      </div>

                      {/* Run Counters */}
                      <div className="flex flex-wrap items-center gap-2 text-[11px]">
                        <span className="px-2 py-0.5 rounded bg-neutral-800/80 text-neutral-300 border border-neutral-700">
                          Targets: <strong>{run.total_target_profiles}</strong>
                        </span>
                        <span className="px-2 py-0.5 rounded bg-blue-500/10 text-blue-300 border border-blue-500/20">
                          Queued: <strong>{run.queued_count}</strong>
                        </span>
                        <span className="px-2 py-0.5 rounded bg-indigo-500/10 text-indigo-300 border border-indigo-500/20">
                          Running: <strong>{run.running_count}</strong>
                        </span>
                        <span className="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-300 border border-emerald-500/20">
                          Success: <strong>{run.success_count}</strong>
                        </span>
                        <span className="px-2 py-0.5 rounded bg-rose-500/10 text-rose-300 border border-rose-500/20">
                          Failed: <strong>{run.failure_count}</strong>
                        </span>
                        {run.stalled_count > 0 && (
                          <span className="px-2 py-0.5 rounded bg-amber-500/10 text-amber-300 border border-amber-500/20">
                            Stalled: <strong>{run.stalled_count}</strong>
                          </span>
                        )}

                        {run.status === 'RUNNING' && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleCancelRun(run.id);
                            }}
                            className="px-2.5 py-1 rounded bg-rose-600/20 hover:bg-rose-600/30 text-rose-300 border border-rose-500/30 text-[10px] font-semibold flex items-center gap-1 transition-colors"
                          >
                            <XCircle className="w-3 h-3" />
                            <span>Abort</span>
                          </button>
                        )}
                      </div>
                    </div>

                    {/* Expandable Executions List */}
                    {isExpanded && (
                      <div className="p-4 border-t border-[#1E2638] bg-[#080B10] space-y-2">
                        <div className="text-[11px] font-bold text-neutral-400 uppercase tracking-wider mb-2">
                          Executions Breakdown ({execs.length})
                        </div>

                        {isLoadingExecs ? (
                          <div className="py-4 text-center text-xs text-neutral-500">Loading executions...</div>
                        ) : execs.length === 0 ? (
                          <div className="py-4 text-center text-xs text-neutral-500">No executions recorded.</div>
                        ) : (
                          <div className="divide-y divide-[#1A2234] border border-[#1E2638] rounded-lg overflow-hidden">
                            {execs.map((exec) => {
                              const profObj = profiles.find((p) => p.id === exec.profile) || { name: 'Unknown Profile' };
                              return (
                                <div key={exec.id} className="p-3 bg-[#0D111A] flex flex-col md:flex-row md:items-center justify-between gap-2 text-xs">
                                  <div>
                                    <div className="flex items-center gap-2">
                                      <strong className="text-white font-medium">{profObj.name}</strong>
                                      <span
                                        className={`text-[9px] font-bold px-1.5 py-0.2 rounded border uppercase ${
                                          exec.status === 'SUCCESS'
                                            ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30'
                                            : exec.status === 'FAILED'
                                            ? 'bg-rose-500/10 text-rose-300 border-rose-500/30'
                                            : exec.status === 'STALLED'
                                            ? 'bg-amber-500/10 text-amber-300 border-amber-500/30'
                                            : 'bg-blue-500/10 text-blue-300 border-blue-500/30'
                                        }`}
                                      >
                                        {exec.status}
                                      </span>
                                      {exec.recovery_status !== 'NONE' && (
                                        <span className="text-[9px] font-bold px-1.5 py-0.2 rounded border bg-indigo-500/10 text-indigo-300 border-indigo-500/30">
                                          Recovery: {exec.recovery_status}
                                        </span>
                                      )}
                                    </div>
                                    {exec.error_message && (
                                      <p className="text-[11px] text-rose-400/90 pt-1 font-mono">{exec.error_message}</p>
                                    )}
                                  </div>

                                  <div className="flex items-center gap-3 text-[11px] text-neutral-400">
                                    <span>Retries: {exec.retry_count}/{exec.max_retries}</span>
                                    {exec.last_confirmed_state && (
                                      <span>State: <strong className="text-neutral-300">{exec.last_confirmed_state}</strong></span>
                                    )}
                                  </div>
                                </div>
                              );
                            })}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* Create / Edit Automation Modal */}
      {showModal && (
        <AutomationModal
          automation={editingAutomation}
          tasks={tasks}
          niches={niches}
          profiles={profiles}
          onClose={() => setShowModal(false)}
          onSaved={() => {
            setShowModal(false);
            refreshAutomations();
          }}
          showNotification={showNotification}
        />
      )}
    </div>
  );
}

// Modal component for creating and editing automations
function AutomationModal({
  automation,
  tasks,
  niches,
  profiles,
  onClose,
  onSaved,
  showNotification
}) {
  const isEdit = !!automation;

  const [formData, setFormData] = useState({
    name: automation?.name || '',
    description: automation?.description || '',
    task: automation?.task || (tasks[0]?.id || ''),
    enabled: automation?.enabled !== undefined ? automation.enabled : true,
    schedule_type: automation?.schedule_type || 'DAILY',
    schedule_time: automation?.schedule_config?.time || '08:00',
    schedule_interval_minutes: automation?.schedule_config?.interval_minutes || 60,
    schedule_run_at: automation?.schedule_config?.run_at || '',
    timezone: automation?.timezone || 'UTC',
    selection_mode: automation?.selection_mode || 'ALL_ELIGIBLE',
    target_niches: automation?.target_niches || [],
    target_profiles: automation?.target_profiles || [],
    concurrency_limit: automation?.concurrency_limit || 5,
    cooldown_minutes: automation?.cooldown_minutes || 30,
    max_runtime_seconds: automation?.max_runtime_seconds || 1800,
    max_retries: automation?.max_retries !== undefined ? automation.max_retries : 2,
    failure_threshold_percent: automation?.failure_threshold_percent || 30,
    priority: automation?.priority || 10
  });

  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!formData.name.trim()) {
      showNotification('Automation name is required', 'error');
      return;
    }
    if (!formData.task) {
      showNotification('Please select an automation task template', 'error');
      return;
    }

    setSubmitting(true);

    // Build schedule config
    const schedule_config = {};
    if (formData.schedule_type === 'DAILY') {
      schedule_config.time = formData.schedule_time;
    } else if (formData.schedule_type === 'INTERVAL') {
      schedule_config.interval_minutes = parseInt(formData.schedule_interval_minutes, 10);
    } else if (formData.schedule_type === 'ONE_TIME') {
      schedule_config.run_at = formData.schedule_run_at || new Date().toISOString();
    }

    const payload = {
      name: formData.name.trim(),
      description: formData.description.trim(),
      task: formData.task,
      enabled: formData.enabled,
      schedule_type: formData.schedule_type,
      schedule_config,
      timezone: formData.timezone,
      selection_mode: formData.selection_mode,
      target_niches: formData.target_niches,
      target_profiles: formData.target_profiles,
      concurrency_limit: parseInt(formData.concurrency_limit, 10),
      cooldown_minutes: parseInt(formData.cooldown_minutes, 10),
      max_runtime_seconds: parseInt(formData.max_runtime_seconds, 10),
      max_retries: parseInt(formData.max_retries, 10),
      failure_threshold_percent: parseInt(formData.failure_threshold_percent, 10),
      priority: parseInt(formData.priority, 10)
    };

    try {
      if (isEdit) {
        await updateAutomation(automation.id, payload);
        showNotification(`Updated automation '${payload.name}'.`);
      } else {
        await createAutomation(payload);
        showNotification(`Created automation '${payload.name}'.`);
      }
      onSaved();
    } catch (err) {
      console.error('Failed to save automation:', err);
      const errMsg = err.response?.data ? JSON.stringify(err.response.data) : 'Failed to save automation.';
      showNotification(errMsg, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const toggleNiche = (nicheId) => {
    setFormData((prev) => {
      const exists = prev.target_niches.includes(nicheId);
      return {
        ...prev,
        target_niches: exists
          ? prev.target_niches.filter((id) => id !== nicheId)
          : [...prev.target_niches, nicheId]
      };
    });
  };

  const toggleProfile = (profileId) => {
    setFormData((prev) => {
      const exists = prev.target_profiles.includes(profileId);
      return {
        ...prev,
        target_profiles: exists
          ? prev.target_profiles.filter((id) => id !== profileId)
          : [...prev.target_profiles, profileId]
      };
    });
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4 overflow-y-auto">
      <div className="bg-[#0D111A] border border-[#1E2638] rounded-2xl w-full max-w-2xl max-h-[90vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        {/* Header */}
        <div className="p-5 border-b border-[#1E2638] flex items-center justify-between bg-[#0A0D14]">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-xl bg-blue-600/20 border border-blue-500/30 flex items-center justify-center">
              <Calendar className="w-4 h-4 text-blue-400" />
            </div>
            <div>
              <h3 className="font-bold text-sm text-white">
                {isEdit ? 'Edit Automation Rule' : 'New Automation Schedule'}
              </h3>
              <p className="text-xs text-neutral-400">Configure target profiles, cadence, and failure safeguards.</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg text-neutral-400 hover:text-white hover:bg-[#161B26]">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <form onSubmit={handleSubmit} className="p-6 space-y-6 overflow-y-auto flex-1">
          {/* Section 1: Basic Information */}
          <div className="space-y-4">
            <h4 className="text-xs font-bold uppercase tracking-wider text-blue-400">1. Basic Information</h4>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="md:col-span-2">
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Automation Name *</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Daily Tech Niche Cookie Warmup"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white placeholder-neutral-500 focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Task Template *</label>
                <select
                  value={formData.task}
                  onChange={(e) => setFormData({ ...formData, task: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                >
                  {tasks.map((t) => (
                    <option key={t.id} value={t.id}>
                      [{t.category}] {t.name}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Schedule State</label>
                <select
                  value={formData.enabled ? 'true' : 'false'}
                  onChange={(e) => setFormData({ ...formData, enabled: e.target.value === 'true' })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                >
                  <option value="true">Enabled (Actively Evaluated)</option>
                  <option value="false">Paused (Disabled)</option>
                </select>
              </div>
            </div>
          </div>

          {/* Section 2: Schedule & Cadence */}
          <div className="space-y-4 pt-4 border-t border-[#1E2638]">
            <h4 className="text-xs font-bold uppercase tracking-wider text-indigo-400">2. Schedule & Cadence</h4>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Schedule Type</label>
                <select
                  value={formData.schedule_type}
                  onChange={(e) => setFormData({ ...formData, schedule_type: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                >
                  <option value="DAILY">DAILY (Every day at fixed time)</option>
                  <option value="INTERVAL">INTERVAL (Every X minutes)</option>
                  <option value="ONE_TIME">ONE TIME (Single execution)</option>
                </select>
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Timezone</label>
                <select
                  value={formData.timezone}
                  onChange={(e) => setFormData({ ...formData, timezone: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                >
                  <option value="UTC">UTC</option>
                  <option value="Africa/Lagos">Africa/Lagos (WAT, UTC+1)</option>
                  <option value="America/New_York">America/New_York (EST/EDT)</option>
                  <option value="America/Los_Angeles">America/Los_Angeles (PST/PDT)</option>
                  <option value="Europe/London">Europe/London (GMT/BST)</option>
                  <option value="Asia/Dubai">Asia/Dubai (GST, UTC+4)</option>
                </select>
              </div>

              {formData.schedule_type === 'DAILY' && (
                <div>
                  <label className="block text-xs text-neutral-300 mb-1 font-medium">Execution Time</label>
                  <input
                    type="time"
                    value={formData.schedule_time}
                    onChange={(e) => setFormData({ ...formData, schedule_time: e.target.value })}
                    className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
              )}

              {formData.schedule_type === 'INTERVAL' && (
                <div>
                  <label className="block text-xs text-neutral-300 mb-1 font-medium">Interval (Minutes)</label>
                  <input
                    type="number"
                    min="5"
                    max="1440"
                    value={formData.schedule_interval_minutes}
                    onChange={(e) => setFormData({ ...formData, schedule_interval_minutes: e.target.value })}
                    className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
              )}

              {formData.schedule_type === 'ONE_TIME' && (
                <div>
                  <label className="block text-xs text-neutral-300 mb-1 font-medium">Run At (ISO / Date)</label>
                  <input
                    type="datetime-local"
                    value={formData.schedule_run_at}
                    onChange={(e) => setFormData({ ...formData, schedule_run_at: e.target.value })}
                    className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                  />
                </div>
              )}
            </div>
          </div>

          {/* Section 3: Profile Targeting */}
          <div className="space-y-4 pt-4 border-t border-[#1E2638]">
            <h4 className="text-xs font-bold uppercase tracking-wider text-cyan-400">3. Profile Targeting</h4>
            <div>
              <label className="block text-xs text-neutral-300 mb-1 font-medium">Target Selection Mode</label>
              <select
                value={formData.selection_mode}
                onChange={(e) => setFormData({ ...formData, selection_mode: e.target.value })}
                className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
              >
                <option value="ALL_ELIGIBLE">ALL_ELIGIBLE (Run across all active unleased profiles)</option>
                <option value="NICHE">NICHE (Target specific persona niches)</option>
                <option value="EXPLICIT_PROFILES">EXPLICIT_PROFILES (Hand-pick specific profiles)</option>
              </select>
            </div>

            {formData.selection_mode === 'NICHE' && (
              <div className="space-y-2">
                <label className="block text-xs text-neutral-300 font-medium">Select Target Niches</label>
                <div className="flex flex-wrap gap-2 max-h-36 overflow-y-auto p-2 bg-[#161B26] border border-[#1E2638] rounded-xl">
                  {niches.map((n) => {
                    const selected = formData.target_niches.includes(n.id);
                    return (
                      <button
                        type="button"
                        key={n.id}
                        onClick={() => toggleNiche(n.id)}
                        className={`px-3 py-1 rounded-lg text-xs font-medium border flex items-center gap-1.5 transition-colors ${
                          selected
                            ? 'bg-blue-600/30 text-blue-200 border-blue-500/50'
                            : 'bg-[#0D111A] text-neutral-400 border-neutral-700 hover:text-white'
                        }`}
                      >
                        {selected && <Check className="w-3 h-3" />}
                        <span>{n.name}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            {formData.selection_mode === 'EXPLICIT_PROFILES' && (
              <div className="space-y-2">
                <label className="block text-xs text-neutral-300 font-medium">Select Target Profiles</label>
                <div className="grid grid-cols-2 gap-2 max-h-40 overflow-y-auto p-2 bg-[#161B26] border border-[#1E2638] rounded-xl">
                  {profiles.map((p) => {
                    const selected = formData.target_profiles.includes(p.id);
                    return (
                      <button
                        type="button"
                        key={p.id}
                        onClick={() => toggleProfile(p.id)}
                        className={`p-2 rounded-lg text-left text-xs border flex items-center justify-between transition-colors ${
                          selected
                            ? 'bg-blue-600/30 text-blue-200 border-blue-500/50'
                            : 'bg-[#0D111A] text-neutral-400 border-neutral-800 hover:text-white'
                        }`}
                      >
                        <span className="truncate">{p.name}</span>
                        {selected && <Check className="w-3.5 h-3.5 text-blue-400 shrink-0" />}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
          </div>

          {/* Section 4: Execution Policy & Failure Threshold */}
          <div className="space-y-4 pt-4 border-t border-[#1E2638]">
            <h4 className="text-xs font-bold uppercase tracking-wider text-amber-400">4. Policies & Safeguards</h4>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Concurrency Limit</label>
                <input
                  type="number"
                  min="1"
                  max="20"
                  value={formData.concurrency_limit}
                  onChange={(e) => setFormData({ ...formData, concurrency_limit: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Cooldown (Minutes)</label>
                <input
                  type="number"
                  min="0"
                  max="1440"
                  value={formData.cooldown_minutes}
                  onChange={(e) => setFormData({ ...formData, cooldown_minutes: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Max Retries</label>
                <input
                  type="number"
                  min="0"
                  max="5"
                  value={formData.max_retries}
                  onChange={(e) => setFormData({ ...formData, max_retries: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Max Runtime (Sec)</label>
                <input
                  type="number"
                  min="60"
                  max="7200"
                  value={formData.max_runtime_seconds}
                  onChange={(e) => setFormData({ ...formData, max_runtime_seconds: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Failure Breaker (%)</label>
                <input
                  type="number"
                  min="5"
                  max="100"
                  value={formData.failure_threshold_percent}
                  onChange={(e) => setFormData({ ...formData, failure_threshold_percent: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-xs text-neutral-300 mb-1 font-medium">Priority (1-100)</label>
                <input
                  type="number"
                  min="1"
                  max="100"
                  value={formData.priority}
                  onChange={(e) => setFormData({ ...formData, priority: e.target.value })}
                  className="w-full bg-[#161B26] border border-[#1E2638] rounded-xl px-3.5 py-2 text-xs text-white focus:outline-none focus:border-blue-500"
                />
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="pt-4 border-t border-[#1E2638] flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl bg-[#161B26] hover:bg-[#1E2638] text-neutral-300 hover:text-white text-xs font-medium transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="px-5 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold shadow-lg shadow-blue-600/20 flex items-center gap-2 transition-all disabled:opacity-50"
            >
              {submitting ? <RotateCcw className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
              <span>{isEdit ? 'Save Changes' : 'Create Automation'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
