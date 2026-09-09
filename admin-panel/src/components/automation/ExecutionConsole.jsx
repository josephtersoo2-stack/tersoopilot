import React, { useState, useEffect } from 'react';
import axios from '../../api';
import { 
  Activity, 
  StopCircle, 
  RefreshCw, 
  Terminal, 
  CheckCircle2, 
  AlertCircle, 
  Clock, 
  Play, 
  Filter, 
  Radio, 
  Sparkles,
  Search,
  Check,
  Zap,
  Globe,
  Youtube
} from 'lucide-react';

export default function ExecutionConsole({ onOpenDispatch }) {
  const [jobs, setJobs] = useState([]);
  const [activeJobId, setActiveJobId] = useState(null);
  const [liveLogs, setLiveLogs] = useState([]);
  const [activeState, setActiveState] = useState('');
  const [jobStatus, setJobStatus] = useState('');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [isAutoScroll, setIsAutoScroll] = useState(true);

  useEffect(() => {
    loadJobs();
    const interval = setInterval(loadJobs, 4000);
    return () => clearInterval(interval);
  }, [activeJobId]);

  const loadJobs = async () => {
    try {
      const res = await axios.get('automation/ghostpilot/');
      setJobs(res.data);
      if (res.data.length > 0 && !activeJobId) {
        selectJob(res.data[0].id, res.data[0].logs, res.data[0].current_state_id, res.data[0].status);
      }
    } catch (err) {
      console.error('Failed to load ghostpilot jobs:', err);
    }
  };

  const selectJob = (jobId, initialLogs, state, status) => {
    setActiveJobId(jobId);
    setLiveLogs(initialLogs || []);
    setActiveState(state || '');
    setJobStatus(status || '');
  };

  useEffect(() => {
    if (!activeJobId) return;
    let stopped = false;
    let timer;
    const controller = new AbortController();
    const refresh = async () => {
      try {
        const { data } = await axios.get(`automation/ghostpilot/${activeJobId}/`, { signal: controller.signal });
        if (!stopped) {
          setLiveLogs(data.logs || []);
          setActiveState(data.current_state_id || '');
          setJobStatus(data.status || '');
        }
      } catch (error) {
        if (!stopped) console.error('Could not refresh execution details:', error.message);
      } finally {
        if (!stopped) timer = setTimeout(refresh, 2000);
      }
    };
    refresh();
    return () => { stopped = true; clearTimeout(timer); controller.abort(); };
  }, [activeJobId]);

  const handleAbort = async (jobId) => {
    if (!window.confirm('Emergency Abort this execution?')) return;
    try {
      await axios.post(`automation/ghostpilot/${jobId}/abort/`);
      loadJobs();
    } catch (err) {
      alert('Abort failed: ' + err.message);
    }
  };

  // Metrics Calculations
  const totalCount = jobs.length;
  const runningCount = jobs.filter((j) => j.status === 'RUNNING').length;
  const successCount = jobs.filter((j) => j.status === 'SUCCESS').length;
  const failedCount = jobs.filter((j) => j.status === 'FAILED' || j.status === 'ABORTED').length;

  const filteredJobs = jobs.filter((job) => {
    const matchesStatus = 
      filterStatus === 'ALL' ? true :
      filterStatus === 'RUNNING' ? job.status === 'RUNNING' :
      filterStatus === 'SUCCESS' ? job.status === 'SUCCESS' :
      filterStatus === 'FAILED' ? (job.status === 'FAILED' || job.status === 'ABORTED') : true;

    const matchesSearch = 
      !searchQuery ||
      job.profile_name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      job.task_name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      job.current_state_id?.toLowerCase().includes(searchQuery.toLowerCase());

    return matchesStatus && matchesSearch;
  });

  const getStatusBadge = (status) => {
    switch (status) {
      case 'RUNNING':
        return (
          <span className="flex items-center gap-1 text-[10px] font-semibold text-amber-400 bg-amber-950/50 px-2 py-0.5 rounded-full border border-amber-800">
            <Activity className="w-3 h-3 animate-spin" /> Running
          </span>
        );
      case 'SUCCESS':
        return (
          <span className="flex items-center gap-1 text-[10px] font-semibold text-emerald-400 bg-emerald-950/50 px-2 py-0.5 rounded-full border border-emerald-800">
            <CheckCircle2 className="w-3 h-3" /> Completed
          </span>
        );
      case 'FAILED':
      case 'ABORTED':
        return (
          <span className="flex items-center gap-1 text-[10px] font-semibold text-rose-400 bg-rose-950/50 px-2 py-0.5 rounded-full border border-rose-800">
            <AlertCircle className="w-3 h-3" /> {status}
          </span>
        );
      default:
        return (
          <span className="flex items-center gap-1 text-[10px] font-medium text-neutral-400 bg-neutral-900 px-2 py-0.5 rounded-full border border-neutral-800">
            <Clock className="w-3 h-3" /> {status}
          </span>
        );
    }
  };

  return (
    <div className="space-y-6">
      {/* Top Stat Counters Bar */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Total Executions</span>
            <div className="text-2xl font-bold text-white mt-0.5">{totalCount}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-400">
            <Clock className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Active Running</span>
            <div className="text-2xl font-bold text-amber-400 mt-0.5 flex items-center gap-2">
              {runningCount}
              {runningCount > 0 && <span className="w-2.5 h-2.5 rounded-full bg-amber-400 animate-ping" />}
            </div>
          </div>
          <div className="p-2.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-400">
            <Activity className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Successful DAGs</span>
            <div className="text-2xl font-bold text-emerald-400 mt-0.5">{successCount}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
            <CheckCircle2 className="w-5 h-5" />
          </div>
        </div>

        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-4 flex items-center justify-between">
          <div>
            <span className="text-[11px] font-medium text-neutral-400">Aborted / Failed</span>
            <div className="text-2xl font-bold text-rose-400 mt-0.5">{failedCount}</div>
          </div>
          <div className="p-2.5 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400">
            <AlertCircle className="w-5 h-5" />
          </div>
        </div>
      </div>

      {/* Main 2-Column Split: Queue & Live Terminal */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Execution Queue (5 cols) */}
        <div className="lg:col-span-5 bg-[#111520] border border-[#1E2638] rounded-2xl p-4 space-y-4">
          <div className="flex items-center justify-between pb-3 border-b border-[#1E2638]">
            <div className="flex items-center gap-2">
              <Radio className="w-4 h-4 text-blue-400 animate-pulse" />
              <h2 className="text-sm font-bold text-white tracking-wide">Execution Queue</h2>
            </div>
            <button 
              onClick={loadJobs} 
              className="text-neutral-400 hover:text-white p-1 rounded-lg hover:bg-[#181E2E] transition cursor-pointer"
              title="Refresh queue"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>

          {/* Filter Pills & Search */}
          <div className="space-y-2.5">
            <div className="flex gap-1.5 p-1 bg-[#0A0D14] rounded-xl border border-[#1E2638] text-[11px]">
              {['ALL', 'RUNNING', 'SUCCESS', 'FAILED'].map((st) => (
                <button
                  key={st}
                  onClick={() => setFilterStatus(st)}
                  className={`flex-1 py-1 rounded-lg font-medium transition cursor-pointer ${
                    filterStatus === st 
                      ? 'bg-blue-600 text-white shadow-sm' 
                      : 'text-neutral-400 hover:text-white'
                  }`}
                >
                  {st}
                </button>
              ))}
            </div>

            <div className="relative">
              <input
                type="text"
                placeholder="Search by profile or task..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full bg-[#0A0D14] border border-[#1E2638] text-xs rounded-xl pl-8 pr-3 py-2 text-white placeholder:text-neutral-500 focus:outline-none focus:border-blue-500 transition"
              />
              <Search className="w-3.5 h-3.5 text-neutral-500 absolute left-2.5 top-2.5" />
            </div>
          </div>

          {/* Job List */}
          <div className="space-y-2 max-h-[580px] overflow-y-auto pr-1">
            {filteredJobs.length === 0 ? (
              <div className="py-12 text-center text-neutral-500 text-xs">
                <Radio className="w-8 h-8 mx-auto mb-2 opacity-30 text-neutral-400" />
                <p>No executions matching current filter.</p>
                {onOpenDispatch && (
                  <button
                    onClick={onOpenDispatch}
                    className="mt-3 text-blue-400 hover:text-blue-300 text-xs font-semibold cursor-pointer"
                  >
                    + Launch a new task
                  </button>
                )}
              </div>
            ) : (
              filteredJobs.map((job) => {
                const isSelected = activeJobId === job.id;
                const isYouTube = job.task_name?.toLowerCase().includes('youtube');
                return (
                  <div
                    key={job.id}
                    onClick={() => selectJob(job.id, job.logs, job.current_state_id, job.status)}
                    className={`p-3.5 rounded-xl border cursor-pointer transition-all ${
                      isSelected
                        ? 'bg-blue-600/10 border-blue-500/60 shadow-md shadow-blue-500/10'
                        : 'bg-[#0A0D14] border-[#1E2638] hover:border-[#2C374E]'
                    }`}
                  >
                    <div className="flex justify-between items-start mb-1.5">
                      <div className="flex items-center gap-2 truncate pr-2">
                        {isYouTube ? (
                          <Youtube className="w-3.5 h-3.5 text-red-400 shrink-0" />
                        ) : (
                          <Globe className="w-3.5 h-3.5 text-emerald-400 shrink-0" />
                        )}
                        <span className="font-semibold text-xs text-white truncate">{job.profile_name}</span>
                      </div>
                      {getStatusBadge(job.status)}
                    </div>

                    <p className="text-[11px] text-neutral-400 truncate">{job.task_name}</p>

                    <div className="flex justify-between items-center mt-2.5 pt-2 border-t border-[#1E2638]/70 text-[10px]">
                      <span className="text-neutral-500">
                        Node: <code className="text-cyan-400 font-mono font-semibold bg-[#131722] px-1.5 py-0.5 rounded">{job.current_state_id || 'INIT'}</code>
                      </span>

                      {job.status === 'RUNNING' && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            handleAbort(job.id);
                          }}
                          className="text-rose-400 hover:text-rose-300 font-medium flex items-center gap-1 cursor-pointer"
                        >
                          <StopCircle className="w-3 h-3" /> Abort
                        </button>
                      )}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* Right Column: Live Telemetry Terminal (7 cols) */}
        <div className="lg:col-span-7 bg-[#0A0D14] border border-[#1E2638] rounded-2xl flex flex-col h-[700px] shadow-2xl overflow-hidden">
          {/* Terminal Window Chrome */}
          <div className="bg-[#111520] border-b border-[#1E2638] px-4 py-3 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-1.5 mr-2">
                <span className="w-3 h-3 rounded-full bg-rose-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-amber-500/80 inline-block" />
                <span className="w-3 h-3 rounded-full bg-emerald-500/80 inline-block" />
              </div>
              <Terminal className="w-4 h-4 text-blue-400 ml-1" />
              <span className="text-xs font-mono font-semibold text-neutral-200">
                ghostpilot-telemetry.log
              </span>
              {activeJobId && (
                <span className="text-[10px] font-mono text-neutral-500 hidden sm:inline truncate max-w-[140px]">
                  [{activeJobId.slice(0, 8)}]
                </span>
              )}
            </div>

            <div className="flex items-center gap-2">
              {activeJobId && (
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-[#181E2E] border border-[#232A3E] text-blue-300">
                  {activeState || 'STANDBY'}
                </span>
              )}

              {jobStatus === 'RUNNING' && (
                <button
                  onClick={() => handleAbort(activeJobId)}
                  className="bg-rose-600 hover:bg-rose-500 text-white text-[11px] font-semibold px-2.5 py-1 rounded-lg flex items-center gap-1 cursor-pointer transition shadow-md shadow-rose-600/20"
                >
                  <StopCircle className="w-3 h-3" /> Kill Task
                </button>
              )}
            </div>
          </div>

          {/* Terminal Body */}
          <div className="bg-[#07090E] p-4 flex-1 overflow-y-auto font-mono text-xs text-neutral-300 space-y-2">
            {!activeJobId ? (
              <div className="h-full flex flex-col items-center justify-center text-center p-6 space-y-4">
                <div className="w-16 h-16 rounded-2xl bg-blue-500/10 border border-blue-500/20 flex items-center justify-center text-blue-400">
                  <Zap className="w-8 h-8" />
                </div>
                <div className="max-w-md space-y-1.5">
                  <h3 className="text-sm font-semibold text-white">No Active Telemetry Selected</h3>
                  <p className="text-xs text-neutral-400 leading-relaxed">
                    Select an execution job from the queue on the left, or launch an automated YouTube or Cookie Warmer scenario to stream live state transitions.
                  </p>
                </div>
                {onOpenDispatch && (
                  <button
                    onClick={onOpenDispatch}
                    className="bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold px-4 py-2 rounded-xl transition cursor-pointer shadow-md shadow-blue-600/25 flex items-center gap-1.5"
                  >
                    <Play className="w-3.5 h-3.5 fill-white" /> Dispatch Sample Campaign
                  </button>
                )}
              </div>
            ) : liveLogs.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-neutral-500 text-xs">
                <Activity className="w-6 h-6 animate-spin text-blue-400 mb-2 opacity-50" />
                <span>Awaiting live SSE telemetry packets from Android client...</span>
              </div>
            ) : (
              liveLogs.map((log, idx) => (
                <div key={idx} className="leading-relaxed border-b border-neutral-900/60 pb-1.5 hover:bg-neutral-900/30 px-1 rounded transition">
                  {log.type === 'AI_DECISION' ? (
                    <div className="text-amber-300 bg-amber-950/25 p-3 rounded-xl border border-amber-900/50 my-1 space-y-1">
                      <div className="flex items-center justify-between text-[10px] font-bold text-amber-400">
                        <span className="flex items-center gap-1.5">
                          <Sparkles className="w-3.5 h-3.5" />
                          [AI DECISION ENGINE - {log.provider || 'AI'}]
                        </span>
                        <span className="font-mono text-[9px] text-amber-500/90">{log.model}</span>
                      </div>
                      <div className="text-xs font-semibold text-white">
                        Action: <span className="text-amber-300">{log.action}</span>
                      </div>
                      <p className="text-[11px] text-neutral-300 leading-relaxed font-sans">
                        {log.reasoning}
                      </p>
                    </div>
                  ) : (
                    <div className="flex flex-wrap items-center gap-1 text-[11px]">
                      <span className="text-neutral-500 text-[10px]">
                        [{log.timestamp ? new Date(log.timestamp).toLocaleTimeString() : 'LOG'}]
                      </span>
                      <span className="text-blue-400 font-semibold">{log.from || log.from_state || 'START'}</span>
                      <span className="text-neutral-600">→</span>
                      <span className="text-emerald-400 font-bold bg-emerald-950/40 px-1.5 py-0.2 rounded border border-emerald-900/50 text-[10px]">
                        {log.outcome || 'STEP'}
                      </span>
                      <span className="text-neutral-600">→</span>
                      <span className="text-purple-400 font-semibold">{log.to || log.to_state || 'NEXT'}</span>
                    </div>
                  )}
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
