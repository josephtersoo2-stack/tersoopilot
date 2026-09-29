import React, { useState, useEffect, useMemo } from 'react';
import axios, {
  fetchCustomWorkflows,
  dispatchCustomWorkflow,
  fetchAutomationTasks,
  dispatchAutomationTask,
  fetchNiches,
  fetchProfiles
} from '../../api';
import {
  Play,
  Youtube,
  Globe,
  X,
  Sliders,
  MessageSquare,
  ThumbsUp,
  UserCheck,
  FastForward,
  Sparkles,
  Plus,
  Trash2,
  CheckCircle2,
  Clock,
  Tv,
  AlertCircle,
  Search,
  Check,
  ChevronDown,
  ChevronRight,
  RotateCcw,
  Crosshair,
  Layers,
  Smartphone,
  ShieldCheck,
  Zap,
  ArrowRight,
  ExternalLink
} from 'lucide-react';
import { SPATIAL_ANCHORS, extractYouTubeVideoId } from './WorkflowBuilderHub';

function formatSeconds(sec) {
  const s = parseInt(sec, 10) || 0;
  const m = Math.floor(s / 60);
  const rem = s % 60;
  return `${m.toString().padStart(2, '0')}:${rem.toString().padStart(2, '0')}`;
}

export default function TaskDispatchModal({
  profiles = [],
  onClose,
  onDispatched,
  initialTask = null,
  initialProfileIds = []
}) {
  const [loadingWorkflows, setLoadingWorkflows] = useState(true);
  const [workflows, setWorkflows] = useState([]);
  const [selectedWorkflowId, setSelectedWorkflowId] = useState(null);
  const [workflowSearch, setWorkflowSearch] = useState('');
  const [activeSourceType, setActiveSourceType] = useState('VISUAL'); // 'VISUAL' or 'CLASSIC'
  const [classicTasks, setClassicTasks] = useState([]);

  // Campaign & Runtime Settings
  const [campaignTitle, setCampaignTitle] = useState('YouTube Search & Target Campaign');
  const [targetVideoUrl, setTargetVideoUrl] = useState('');
  const [targetVideoTitle, setTargetVideoTitle] = useState('');
  const [targetChannel, setTargetChannel] = useState('');
  const [targetDuration, setTargetDuration] = useState(180);
  const [keywords, setKeywords] = useState(['trending']);
  const [keywordInput, setKeywordInput] = useState('');
  const [minWatchSeconds, setMinWatchSeconds] = useState(60);
  const [maxWatchSeconds, setMaxWatchSeconds] = useState(180);
  const [minSearchScroll, setMinSearchScroll] = useState(2);
  const [maxSearchScroll, setMaxSearchScroll] = useState(8);
  const [enableLike, setEnableLike] = useState(true);
  const [likeProb, setLikeProb] = useState(85);
  const [enableSubscribe, setEnableSubscribe] = useState(true);
  const [subProb, setSubProb] = useState(40);
  const [enableComments, setEnableComments] = useState(true);
  const [commentInput, setCommentInput] = useState('');
  const [customComments, setCustomComments] = useState([]);
  const [staggerDelaySeconds, setStaggerDelaySeconds] = useState(5);

  // Web overrides if applicable
  const [websiteUrl, setWebsiteUrl] = useState('');
  const [websiteDwell, setWebsiteDwell] = useState(120);

  // AI scanning state
  const [isAnalyzing, setIsAnalyzing] = useState(false);
  const [analyzeError, setAnalyzeError] = useState(null);

  // Target profiles state
  const [fleetProfiles, setFleetProfiles] = useState(profiles || []);
  const [selectedProfileIds, setSelectedProfileIds] = useState(initialProfileIds || []);
  const [niches, setNiches] = useState([]);
  const [selectedNicheId, setSelectedNicheId] = useState('');
  const [profileSearch, setProfileSearch] = useState('');

  // Dispatch state
  const [dispatching, setDispatching] = useState(false);
  const [dispatchError, setDispatchError] = useState(null);

  // Load initial workflows, niches, and profiles
  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoadingWorkflows(true);
    try {
      const [wfRes, tasksRes, nichesRes, profRes] = await Promise.all([
        fetchCustomWorkflows().catch(() => ({ data: [] })),
        fetchAutomationTasks().catch(() => ({ data: [] })),
        fetchNiches().catch(() => ({ data: [] })),
        fetchProfiles().catch(() => ({ data: [] }))
      ]);

      const wfList = wfRes.data || [];
      setWorkflows(wfList);
      setClassicTasks(tasksRes.data || []);
      setNiches(nichesRes.data || []);
      if (profRes.data && profRes.data.length > 0) {
        setFleetProfiles(profRes.data);
      }

      // Check if an initial task or workflow was provided
      if (initialTask) {
        if (initialTask.journeys) {
          setSelectedWorkflowId(initialTask.id);
          applyWorkflowDefaults(initialTask);
        } else {
          // Classic task
          setActiveSourceType('CLASSIC');
          setSelectedWorkflowId(initialTask.id);
          setCampaignTitle(initialTask.name || 'Campaign');
        }
      } else if (wfList.length > 0) {
        setSelectedWorkflowId(wfList[0].id);
        applyWorkflowDefaults(wfList[0]);
      }
    } catch (err) {
      console.error('Failed to load workflow data:', err);
    } finally {
      setLoadingWorkflows(false);
    }
  };

  // When a visual workflow is selected, inspect its steps and extract smart defaults
  const applyWorkflowDefaults = (wf) => {
    if (!wf) return;
    setCampaignTitle(wf.name || 'Visual Campaign Execution');

    // Extract first journey steps
    const firstJourney = (wf.journeys || [])[0];
    const steps = firstJourney?.steps || [];

    // Search for video target step
    const videoStep = steps.find(
      (s) =>
        s.type === 'SEARCH_TARGET_VIDEO' ||
        s.type === 'VIDEO_SEARCH' ||
        s.video_url ||
        s.target_video_id
    );

    if (videoStep) {
      if (videoStep.video_url) setTargetVideoUrl(videoStep.video_url);
      if (videoStep.keyword) setKeywords([videoStep.keyword]);
      if (videoStep.dwell_min) setMinWatchSeconds(videoStep.dwell_min);
      if (videoStep.dwell_max) setMaxWatchSeconds(videoStep.dwell_max);
    }

    // Check for web navigation step
    const navStep = steps.find((s) => s.type === 'GO_TO_URL' || s.type === 'START');
    if (navStep && navStep.url && !navStep.url.includes('youtube.com')) {
      setWebsiteUrl(navStep.url);
      if (navStep.dwell_max) setWebsiteDwell(navStep.dwell_max);
    }
  };

  // Selected workflow object
  const selectedWorkflow = useMemo(() => {
    if (activeSourceType === 'VISUAL') {
      return workflows.find((w) => w.id === selectedWorkflowId) || null;
    }
    return classicTasks.find((t) => t.id === selectedWorkflowId) || null;
  }, [activeSourceType, selectedWorkflowId, workflows, classicTasks]);

  // Detected video ID from targetVideoUrl
  const extractedVideoId = useMemo(() => {
    return extractYouTubeVideoId(targetVideoUrl);
  }, [targetVideoUrl]);

  // AI Scan YouTube Video
  const handleScanVideo = async () => {
    if (!targetVideoUrl.trim()) {
      alert('Please enter a YouTube video URL first.');
      return;
    }
    setIsAnalyzing(true);
    setAnalyzeError(null);
    try {
      const res = await axios.post('automation/youtube/analyze/', {
        url: targetVideoUrl.trim()
      });
      const data = res.data;
      const duration = data.duration_seconds || 180;
      setTargetDuration(duration);
      setTargetVideoTitle(data.title || '');
      setTargetChannel(data.channel_name || '');
      setMinWatchSeconds(Math.max(15, Math.floor(duration * 0.35)));
      setMaxWatchSeconds(duration);

      if (data.suggested_keywords && data.suggested_keywords.length > 0) {
        setKeywords(data.suggested_keywords);
      }
      if (data.generated_comments && data.generated_comments.length > 0) {
        setCustomComments(data.generated_comments);
      }
    } catch (err) {
      console.error('Scan failed:', err);
      setAnalyzeError(err.response?.data?.error || 'Failed to scan YouTube video.');
    } finally {
      setIsAnalyzing(false);
    }
  };

  const addKeyword = () => {
    const kw = keywordInput.trim();
    if (kw && !keywords.includes(kw)) {
      setKeywords((prev) => [...prev, kw]);
    }
    setKeywordInput('');
  };

  const removeKeyword = (kw) => {
    setKeywords((prev) => prev.filter((k) => k !== kw));
  };

  const addComment = () => {
    const c = commentInput.trim();
    if (c && !customComments.includes(c)) {
      setCustomComments((prev) => [...prev, c]);
    }
    setCommentInput('');
  };

  const removeComment = (idx) => {
    setCustomComments((prev) => prev.filter((_, i) => i !== idx));
  };

  // Filter profiles based on search and niche
  const filteredProfiles = useMemo(() => {
    return fleetProfiles.filter((p) => {
      const matchesSearch =
        (p.name || '').toLowerCase().includes(profileSearch.toLowerCase()) ||
        (p.brand || '').toLowerCase().includes(profileSearch.toLowerCase()) ||
        (p.model_name || '').toLowerCase().includes(profileSearch.toLowerCase()) ||
        (p.proxy_ip || '').toLowerCase().includes(profileSearch.toLowerCase());

      const matchesNiche = selectedNicheId
        ? String(p.niche) === String(selectedNicheId) || String(p.niche_id) === String(selectedNicheId)
        : true;

      return matchesSearch && matchesNiche;
    });
  }, [fleetProfiles, profileSearch, selectedNicheId]);

  const toggleSelectProfile = (id) => {
    setSelectedProfileIds((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id]
    );
  };

  const selectAllProfiles = () => {
    if (selectedProfileIds.length === filteredProfiles.length) {
      setSelectedProfileIds([]);
    } else {
      setSelectedProfileIds(filteredProfiles.map((p) => p.id));
    }
  };

  const selectOnlineProfilesOnly = () => {
    const online = filteredProfiles.filter(
      (p) => p.status === 'ONLINE' || p.is_connected || p.online
    );
    setSelectedProfileIds(online.map((p) => p.id));
  };

  // Launch Campaign Dispatcher
  const handleLaunch = async () => {
    if (dispatching) return;
    if (!selectedWorkflowId) {
      alert('Please select a visual workflow or task to execute.');
      return;
    }
    if (selectedProfileIds.length === 0) {
      alert('Please select at least one target profile to run on.');
      return;
    }
    if (minWatchSeconds > maxWatchSeconds) {
      alert('Minimum watch time cannot exceed maximum watch time.');
      return;
    }

    setDispatching(true);
    setDispatchError(null);

    try {
      if (activeSourceType === 'VISUAL') {
        // Compile runtime overrides for CustomWorkflow
        const overrides = {
          campaign_title: campaignTitle,
          video_url: targetVideoUrl.trim(),
          target_video_id: extractedVideoId,
          video_id: extractedVideoId,
          keywords: keywords,
          keyword: keywords[0] || '',
          min_watch_seconds: parseInt(minWatchSeconds) || 60,
          max_watch_seconds: parseInt(maxWatchSeconds) || 180,
          scroll_depth_min: parseInt(minSearchScroll) || 2,
          scroll_depth_max: parseInt(maxSearchScroll) || 8,
          enable_like: enableLike,
          like_probability: enableLike ? parseFloat(likeProb) / 100 : 0,
          enable_subscribe: enableSubscribe,
          subscribe_probability: enableSubscribe ? parseFloat(subProb) / 100 : 0,
          enable_comments: enableComments,
          comments: customComments,
          stagger_delay_seconds: parseInt(staggerDelaySeconds) || 5,
          website_url: websiteUrl.trim(),
          website_dwell: parseInt(websiteDwell) || 120
        };

        const res = await dispatchCustomWorkflow(selectedWorkflowId, selectedProfileIds, overrides);
        console.log('Visual Workflow dispatched successfully:', res.data);
      } else {
        // Classic task dispatch
        const res = await dispatchAutomationTask(selectedWorkflowId, selectedProfileIds);
        console.log('Classic task dispatched successfully:', res.data);
      }

      // Immediately trigger onDispatched to switch to Execution Console
      if (onDispatched) {
        onDispatched();
      }
      onClose();
    } catch (err) {
      console.error('Launch campaign failed:', err);
      const errMsg =
        err.response?.data?.error ||
        err.response?.data?.detail ||
        err.message ||
        'Failed to dispatch campaign.';
      setDispatchError(errMsg);
    } finally {
      setDispatching(false);
    }
  };

  // Helper to format workflow step summary preview
  const renderStepPipeline = (wf) => {
    if (!wf || !wf.journeys || wf.journeys.length === 0) return null;
    const steps = wf.journeys[0].steps || [];
    if (steps.length === 0) return <span className="text-neutral-500 italic">No steps defined</span>;

    return (
      <div className="flex items-center gap-1.5 overflow-x-auto py-1 scrollbar-none text-[11px]">
        {steps.slice(0, 5).map((step, idx) => {
          let label = step.step_name || step.type;
          let iconColor = 'text-blue-400';
          if (step.type === 'SEARCH_TARGET_VIDEO') {
            label = `Search [${step.target_video_id ? step.target_video_id.substring(0, 6) + '..' : 'Video'}]`;
            iconColor = 'text-amber-400';
          } else if (step.type === 'SPATIAL_ANCHOR_CLICK') {
            label = `Tap [${step.spatial_anchor || 'Anchor'}]`;
            iconColor = 'text-emerald-400';
          } else if (step.type === 'WAIT_PLAYBACK') {
            label = `Watch (${step.duration_seconds || 90}s)`;
            iconColor = 'text-purple-400';
          }

          return (
            <React.Fragment key={idx}>
              <span className="flex items-center gap-1 px-2 py-0.5 rounded-md bg-neutral-900 border border-neutral-800 text-neutral-300 shrink-0">
                <span className={`w-1.5 h-1.5 rounded-full ${iconColor.replace('text-', 'bg-')}`} />
                {label}
              </span>
              {idx < Math.min(steps.length - 1, 4) && (
                <ChevronRight className="w-3 h-3 text-neutral-600 shrink-0" />
              )}
            </React.Fragment>
          );
        })}
        {steps.length > 5 && (
          <span className="px-1.5 py-0.5 rounded text-[10px] bg-neutral-800 text-neutral-400 shrink-0">
            +{steps.length - 5} more
          </span>
        )}
      </div>
    );
  };

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md flex items-center justify-center p-3 sm:p-5 z-50 animate-in fade-in duration-150">
      <div className="bg-[#0D1117] border border-neutral-800 rounded-2xl w-full max-w-5xl p-5 sm:p-7 relative max-h-[92vh] overflow-y-auto shadow-2xl flex flex-col gap-6 selection:bg-blue-600 selection:text-white">
        
        {/* Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-4 border-b border-neutral-800/80 gap-3">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-blue-600/20 shrink-0">
              <Zap className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="text-lg sm:text-xl font-bold text-white flex items-center gap-2">
                Launch Campaign & Execution Orchestrator
              </h2>
              <p className="text-xs text-neutral-400">
                Select your created visual workflow, fine-tune target parameters, assign mobile profiles, and watch live execution in the console.
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="self-end sm:self-center p-2 rounded-xl bg-neutral-800/80 hover:bg-neutral-700 text-neutral-400 hover:text-white transition cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {dispatchError && (
          <div className="p-3 bg-red-950/50 border border-red-800/80 rounded-xl text-red-200 text-xs flex items-center gap-2">
            <AlertCircle className="w-4 h-4 text-red-400 shrink-0" />
            <span>{dispatchError}</span>
          </div>
        )}

        {/* STEP 1: SELECT WORKFLOW / TASK */}
        <div className="bg-neutral-900/60 border border-neutral-800 rounded-2xl p-4 sm:p-5 space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="w-5 h-5 rounded-full bg-blue-500/20 text-blue-400 font-bold text-xs flex items-center justify-center border border-blue-500/40">
                1
              </span>
              <h3 className="text-sm font-semibold text-white tracking-wide">
                Select Workflow / Task to Execute
              </h3>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setActiveSourceType('VISUAL')}
                className={`px-3 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
                  activeSourceType === 'VISUAL'
                    ? 'bg-blue-600 text-white shadow-sm'
                    : 'bg-neutral-800 text-neutral-400 hover:text-neutral-200'
                }`}
              >
                Visual Workflows ({workflows.length})
              </button>
              {classicTasks.length > 0 && (
                <button
                  type="button"
                  onClick={() => setActiveSourceType('CLASSIC')}
                  className={`px-3 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
                    activeSourceType === 'CLASSIC'
                      ? 'bg-blue-600 text-white shadow-sm'
                      : 'bg-neutral-800 text-neutral-400 hover:text-neutral-200'
                  }`}
                >
                  Classic Tasks ({classicTasks.length})
                </button>
              )}
            </div>
          </div>

          {loadingWorkflows ? (
            <div className="py-8 flex flex-col items-center justify-center text-neutral-400 text-xs gap-2">
              <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
              <span>Loading saved visual workflows...</span>
            </div>
          ) : activeSourceType === 'VISUAL' ? (
            workflows.length === 0 ? (
              <div className="py-6 px-4 rounded-xl border border-dashed border-neutral-800 text-center space-y-2">
                <Layers className="w-8 h-8 text-neutral-600 mx-auto" />
                <p className="text-xs text-neutral-300 font-medium">No Visual Workflows Found</p>
                <p className="text-[11px] text-neutral-500">
                  Head over to the Visual Workflow Builder tab to craft your custom YouTube or web automation pipeline with calibrated spatial anchors.
                </p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-56 overflow-y-auto pr-1">
                {workflows.map((wf) => {
                  const isSelected = selectedWorkflowId === wf.id;
                  const stepCount = (wf.journeys || [])[0]?.steps?.length || 0;
                  return (
                    <div
                      key={wf.id}
                      onClick={() => {
                        setSelectedWorkflowId(wf.id);
                        applyWorkflowDefaults(wf);
                      }}
                      className={`p-3.5 rounded-xl border cursor-pointer transition-all flex flex-col gap-2 ${
                        isSelected
                          ? 'bg-blue-950/40 border-blue-500 text-white ring-1 ring-blue-500/40 shadow-lg shadow-blue-950/30'
                          : 'bg-neutral-950/60 border-neutral-800 text-neutral-300 hover:border-neutral-700 hover:bg-neutral-900/80'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="font-semibold text-xs text-white truncate">
                              {wf.name}
                            </span>
                            <span className="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-neutral-800 text-neutral-400">
                              {wf.platform || 'YOUTUBE'}
                            </span>
                          </div>
                          {wf.description && (
                            <p className="text-[11px] text-neutral-400 line-clamp-1 mt-0.5">
                              {wf.description}
                            </p>
                          )}
                        </div>
                        <div
                          className={`w-4 h-4 rounded-full border flex items-center justify-center shrink-0 ${
                            isSelected
                              ? 'bg-blue-600 border-blue-500 text-white'
                              : 'border-neutral-700'
                          }`}
                        >
                          {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                        </div>
                      </div>

                      {/* Mini Pipeline Preview */}
                      <div className="border-t border-neutral-800/80 pt-2">
                        {renderStepPipeline(wf)}
                      </div>
                    </div>
                  );
                })}
              </div>
            )
          ) : (
            /* Classic Tasks Selector */
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-56 overflow-y-auto pr-1">
              {classicTasks.map((t) => {
                const isSelected = selectedWorkflowId === t.id;
                return (
                  <div
                    key={t.id}
                    onClick={() => {
                      setSelectedWorkflowId(t.id);
                      setCampaignTitle(t.name || 'Classic Task');
                    }}
                    className={`p-3 rounded-xl border cursor-pointer transition-all flex items-center justify-between ${
                      isSelected
                        ? 'bg-blue-950/40 border-blue-500 text-white ring-1 ring-blue-500/40'
                        : 'bg-neutral-950/60 border-neutral-800 text-neutral-300 hover:border-neutral-700'
                    }`}
                  >
                    <div>
                      <p className="text-xs font-semibold text-white truncate">{t.name}</p>
                      <p className="text-[10px] text-neutral-500">{t.category} • {t.workflow_type}</p>
                    </div>
                    <div
                      className={`w-4 h-4 rounded-full border flex items-center justify-center shrink-0 ${
                        isSelected ? 'bg-blue-600 border-blue-500 text-white' : 'border-neutral-700'
                      }`}
                    >
                      {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* STEP 2: QUICK RUNTIME SETTINGS & OVERRIDES */}
        <div className="bg-neutral-900/60 border border-neutral-800 rounded-2xl p-4 sm:p-5 space-y-4">
          <div className="flex items-center gap-2">
            <span className="w-5 h-5 rounded-full bg-blue-500/20 text-blue-400 font-bold text-xs flex items-center justify-center border border-blue-500/40">
              2
            </span>
            <h3 className="text-sm font-semibold text-white tracking-wide">
              Quick Campaign & Objective Settings
            </h3>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Campaign Run Name */}
            <div>
              <label className="block text-[11px] font-semibold text-neutral-400 uppercase tracking-wider mb-1.5">
                Campaign Title
              </label>
              <input
                type="text"
                value={campaignTitle}
                onChange={(e) => setCampaignTitle(e.target.value)}
                placeholder="e.g. YouTube Search & Discover - Video Ranker"
                className="w-full bg-neutral-950 border border-neutral-800 rounded-xl px-3 py-2 text-xs text-white placeholder:text-neutral-600 outline-none focus:border-blue-500 transition"
              />
            </div>

            {/* Stagger Delay Between Profiles */}
            <div>
              <label className="block text-[11px] font-semibold text-neutral-400 uppercase tracking-wider mb-1.5 flex items-center justify-between">
                <span>Stagger Launch Delay</span>
                <span className="text-blue-400 font-mono">{staggerDelaySeconds}s per profile</span>
              </label>
              <input
                type="range"
                min="0"
                max="60"
                step="5"
                value={staggerDelaySeconds}
                onChange={(e) => setStaggerDelaySeconds(Number(e.target.value))}
                className="w-full h-1.5 bg-neutral-800 rounded-lg appearance-none cursor-pointer accent-blue-500 mt-2"
              />
            </div>
          </div>

          {/* YouTube Video Target Section */}
          <div className="bg-neutral-950/80 border border-neutral-800/90 rounded-xl p-4 space-y-3.5">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Youtube className="w-4 h-4 text-red-500" />
                <span className="text-xs font-semibold text-neutral-200">
                  Target YouTube Video & Background Match
                </span>
              </div>
              {extractedVideoId && (
                <span className="px-2 py-0.5 rounded-full text-[10px] font-mono font-semibold bg-emerald-950/60 border border-emerald-500/40 text-emerald-400 flex items-center gap-1">
                  <CheckCircle2 className="w-3 h-3 text-emerald-400" />
                  Target ID: {extractedVideoId}
                </span>
              )}
            </div>

            <div className="flex flex-col sm:flex-row gap-2">
              <input
                type="text"
                value={targetVideoUrl}
                onChange={(e) => setTargetVideoUrl(e.target.value)}
                placeholder="Drop or paste YouTube video link (e.g. https://www.youtube.com/watch?v=...)"
                className="flex-1 bg-neutral-900 border border-neutral-800 rounded-xl px-3 py-2 text-xs text-white placeholder:text-neutral-600 outline-none focus:border-blue-500 transition"
              />
              <button
                type="button"
                onClick={handleScanVideo}
                disabled={isAnalyzing || !targetVideoUrl.trim()}
                className={`flex items-center gap-1.5 px-4 py-2 rounded-xl text-xs font-semibold text-white shrink-0 transition cursor-pointer ${
                  isAnalyzing || !targetVideoUrl.trim()
                    ? 'bg-neutral-800 text-neutral-500 cursor-not-allowed'
                    : 'bg-gradient-to-r from-red-600 to-amber-600 hover:from-red-500 hover:to-amber-500 shadow-md shadow-red-950'
                }`}
              >
                <Sparkles className="w-3.5 h-3.5" />
                {isAnalyzing ? 'Scanning...' : 'One-Click AI Scan'}
              </button>
            </div>

            {targetVideoTitle && (
              <div className="text-[11px] text-neutral-400 flex items-center gap-2 bg-neutral-900/60 px-3 py-1.5 rounded-lg border border-neutral-800/80">
                <span className="text-white font-medium truncate max-w-xs">{targetVideoTitle}</span>
                {targetChannel && <span className="text-neutral-500">• {targetChannel}</span>}
                <span className="text-neutral-500">• {formatSeconds(targetDuration)}</span>
              </div>
            )}

            {/* Search Keywords */}
            <div>
              <label className="block text-[11px] font-semibold text-neutral-400 uppercase tracking-wider mb-1.5 flex items-center justify-between">
                <span>Organic Search Keywords (Typed into Search Bar)</span>
                <span className="text-neutral-500 text-[10px]">{keywords.length} keyword(s)</span>
              </label>
              <div className="flex gap-2 mb-2">
                <input
                  type="text"
                  value={keywordInput}
                  onChange={(e) => setKeywordInput(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addKeyword())}
                  placeholder="Type keyword and press Enter..."
                  className="flex-1 bg-neutral-900 border border-neutral-800 rounded-xl px-3 py-1.5 text-xs text-white placeholder:text-neutral-600 outline-none focus:border-blue-500 transition"
                />
                <button
                  type="button"
                  onClick={addKeyword}
                  className="px-3 py-1.5 bg-neutral-800 hover:bg-neutral-750 text-white rounded-xl text-xs font-medium cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                </button>
              </div>

              <div className="flex flex-wrap gap-1.5 min-h-[28px]">
                {keywords.map((kw, i) => (
                  <span
                    key={i}
                    className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs bg-neutral-900 border border-neutral-800 text-blue-300 font-medium"
                  >
                    <span>{kw}</span>
                    <button
                      type="button"
                      onClick={() => removeKeyword(kw)}
                      className="text-neutral-500 hover:text-red-400 cursor-pointer"
                    >
                      <X className="w-3 h-3" />
                    </button>
                  </span>
                ))}
              </div>
            </div>

            {/* Watch Duration & Scroll Matrix */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2 border-t border-neutral-800/80">
              <div>
                <div className="flex items-center justify-between text-[11px] font-semibold text-neutral-400 mb-1.5">
                  <span>Watch Duration Range</span>
                  <span className="text-emerald-400 font-mono font-medium">
                    {formatSeconds(minWatchSeconds)} - {formatSeconds(maxWatchSeconds)}
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="15"
                    max={maxWatchSeconds}
                    value={minWatchSeconds}
                    onChange={(e) => setMinWatchSeconds(Number(e.target.value))}
                    className="w-20 bg-neutral-900 border border-neutral-800 rounded-lg px-2 py-1 text-xs text-white text-center outline-none"
                  />
                  <span className="text-neutral-600 text-xs">to</span>
                  <input
                    type="number"
                    min={minWatchSeconds}
                    max="1800"
                    value={maxWatchSeconds}
                    onChange={(e) => setMaxWatchSeconds(Number(e.target.value))}
                    className="w-20 bg-neutral-900 border border-neutral-800 rounded-lg px-2 py-1 text-xs text-white text-center outline-none"
                  />
                  <span className="text-neutral-500 text-[11px]">sec</span>
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-[11px] font-semibold text-neutral-400 mb-1.5">
                  <span>Feed Scroll Search Depth</span>
                  <span className="text-purple-400 font-mono font-medium">
                    {minSearchScroll} to {maxSearchScroll} sweeps
                  </span>
                </div>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    max={maxSearchScroll}
                    value={minSearchScroll}
                    onChange={(e) => setMinSearchScroll(Number(e.target.value))}
                    className="w-20 bg-neutral-900 border border-neutral-800 rounded-lg px-2 py-1 text-xs text-white text-center outline-none"
                  />
                  <span className="text-neutral-600 text-xs">to</span>
                  <input
                    type="number"
                    min={minSearchScroll}
                    max="20"
                    value={maxSearchScroll}
                    onChange={(e) => setMaxSearchScroll(Number(e.target.value))}
                    className="w-20 bg-neutral-900 border border-neutral-800 rounded-lg px-2 py-1 text-xs text-white text-center outline-none"
                  />
                  <span className="text-neutral-500 text-[11px]">scrolls</span>
                </div>
              </div>
            </div>

            {/* Engagement Options (Spatial Anchors Toggles) */}
            <div className="pt-2 border-t border-neutral-800/80 flex flex-wrap gap-4">
              <label className="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={enableLike}
                  onChange={(e) => setEnableLike(e.target.checked)}
                  className="rounded bg-neutral-900 border-neutral-700 text-blue-600 cursor-pointer"
                />
                <ThumbsUp className="w-3.5 h-3.5 text-blue-400" />
                <span>Like Video ({likeProb}%)</span>
              </label>

              <label className="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={enableSubscribe}
                  onChange={(e) => setEnableSubscribe(e.target.checked)}
                  className="rounded bg-neutral-900 border-neutral-700 text-red-600 cursor-pointer"
                />
                <UserCheck className="w-3.5 h-3.5 text-red-400" />
                <span>Subscribe ({subProb}%)</span>
              </label>

              <label className="flex items-center gap-2 text-xs text-neutral-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={enableComments}
                  onChange={(e) => setEnableComments(e.target.checked)}
                  className="rounded bg-neutral-900 border-neutral-700 text-emerald-600 cursor-pointer"
                />
                <MessageSquare className="w-3.5 h-3.5 text-emerald-400" />
                <span>Post Comments</span>
              </label>
            </div>
          </div>
        </div>

        {/* STEP 3: CHOOSE PROFILES TO RUN ON */}
        <div className="bg-neutral-900/60 border border-neutral-800 rounded-2xl p-4 sm:p-5 space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="w-5 h-5 rounded-full bg-blue-500/20 text-blue-400 font-bold text-xs flex items-center justify-center border border-blue-500/40">
                3
              </span>
              <h3 className="text-sm font-semibold text-white tracking-wide">
                Assign Target Profiles ({selectedProfileIds.length}/{fleetProfiles.length})
              </h3>
            </div>
            
            <div className="flex items-center gap-2 flex-wrap">
              {niches.length > 0 && (
                <select
                  value={selectedNicheId}
                  onChange={(e) => setSelectedNicheId(e.target.value)}
                  className="bg-neutral-950 border border-neutral-800 rounded-lg px-2.5 py-1 text-xs text-white outline-none"
                >
                  <option value="">All Niches</option>
                  {niches.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </select>
              )}

              <input
                type="text"
                value={profileSearch}
                onChange={(e) => setProfileSearch(e.target.value)}
                placeholder="Search profiles..."
                className="bg-neutral-950 border border-neutral-800 rounded-lg px-2.5 py-1 text-xs text-white placeholder:text-neutral-600 outline-none w-32 sm:w-40"
              />

              <button
                type="button"
                onClick={selectAllProfiles}
                className="text-xs text-blue-400 hover:text-blue-300 bg-neutral-950 hover:bg-neutral-800 px-2.5 py-1 rounded-lg border border-neutral-800 transition cursor-pointer"
              >
                {selectedProfileIds.length === filteredProfiles.length ? 'Deselect All' : 'Select All'}
              </button>

              <button
                type="button"
                onClick={selectOnlineProfilesOnly}
                className="text-xs text-emerald-400 hover:text-emerald-300 bg-neutral-950 hover:bg-neutral-800 px-2.5 py-1 rounded-lg border border-neutral-800 transition cursor-pointer"
              >
                Online Only
              </button>
            </div>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2.5 max-h-48 overflow-y-auto pr-1">
            {filteredProfiles.map((p) => {
              const isSelected = selectedProfileIds.includes(p.id);
              const isOnline = p.status === 'ONLINE' || p.is_connected || p.online;
              return (
                <div
                  key={p.id}
                  onClick={() => toggleSelectProfile(p.id)}
                  className={`p-2.5 rounded-xl border cursor-pointer flex items-center justify-between text-xs transition-all ${
                    isSelected
                      ? 'bg-blue-950/40 border-blue-500 text-white ring-1 ring-blue-500/40 shadow-sm'
                      : 'bg-neutral-950/60 border-neutral-800 text-neutral-400 hover:border-neutral-700 hover:text-neutral-200'
                  }`}
                >
                  <div className="min-w-0 pr-2">
                    <div className="flex items-center gap-1.5">
                      <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${isOnline ? 'bg-emerald-400 shadow-sm shadow-emerald-400/50' : 'bg-neutral-600'}`} />
                      <p className="font-semibold truncate text-xs text-neutral-200">{p.name}</p>
                    </div>
                    <p className="text-[10px] text-neutral-500 truncate mt-0.5">
                      {p.brand || 'Device'} {p.model_name || p.model_code || ''}
                      {p.proxy_ip ? ` • ${p.proxy_ip}` : ''}
                    </p>
                  </div>
                  <div
                    className={`w-4 h-4 rounded-full border flex items-center justify-center shrink-0 ${
                      isSelected ? 'bg-blue-600 border-blue-500 text-white' : 'border-neutral-700'
                    }`}
                  >
                    {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* FOOTER ACTIONS */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between pt-3 border-t border-neutral-800 gap-3">
          <div className="text-xs text-neutral-400">
            <span className="font-semibold text-white">{selectedProfileIds.length}</span> profile(s) selected for{' '}
            <span className="text-blue-400 font-semibold">{selectedWorkflow?.name || 'Campaign'}</span>
          </div>

          <div className="flex items-center gap-3 self-end sm:self-center">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-xs font-semibold text-neutral-400 hover:text-white bg-neutral-800 hover:bg-neutral-750 rounded-xl transition cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleLaunch}
              disabled={dispatching || selectedProfileIds.length === 0 || !selectedWorkflowId}
              className={`flex items-center gap-2 px-6 py-2.5 rounded-xl text-xs font-bold text-white shadow-xl transition cursor-pointer ${
                dispatching || selectedProfileIds.length === 0 || !selectedWorkflowId
                  ? 'bg-neutral-800 text-neutral-500 cursor-not-allowed shadow-none'
                  : 'bg-gradient-to-r from-blue-600 via-indigo-600 to-cyan-500 hover:from-blue-500 hover:to-cyan-400 shadow-blue-600/30'
              }`}
            >
              <Play className="w-4 h-4 fill-white" />
              {dispatching ? 'Compiling & Launching...' : 'Launch Campaign'}
            </button>
          </div>
        </div>

      </div>
    </div>
  );
}
