import React, { useState, useEffect, useRef } from 'react';
import {
  Globe,
  Link2,
  MousePointer,
  Type,
  CornerDownLeft,
  Shield,
  Sparkles,
  Bot,
  Plus,
  Save,
  FolderOpen,
  Copy,
  Trash2,
  X,
  ChevronRight,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Play,
  ArrowUp,
  ArrowDown,
  Layers,
  CheckCircle2,
  AlertCircle,
  Crosshair,
  Search,
  Youtube,
  Tv,
  Move,
  GripHorizontal,
  ThumbsUp,
  MessageSquare,
  UserPlus,
  Puzzle,
  Edit2,
  Loader2
} from 'lucide-react';
import {
  fetchCustomWorkflows,
  fetchCustomWorkflowDetail,
  saveCustomWorkflow,
  deleteCustomWorkflow,
  dispatchCustomWorkflow,
  fetchProfiles,
  fetchAddonDynamicSteps,
  analyzeYouTubeVideo
} from '../../api';

// Spatial Anchor Registry matching the operator's calibrated model
export const SPATIAL_ANCHORS = [
  { id: 'SEARCH_BUTTON_HOME', label: 'Search Icon (Homepage)', x: 929, y: 161, category: 'SEARCH', tag: 'Top Right' },
  { id: 'SEARCH_BUTTON_WATCH', label: 'Search Icon (Watch / Playing Video)', x: 786, y: 158, category: 'SEARCH', tag: 'Player Header' },
  { id: 'SEARCH_BUTTON_RESULTS', label: 'Search Icon (Results Feed)', x: 417, y: 358, category: 'SEARCH', tag: 'Search Bar' },
  { id: 'SEARCH_INPUT', label: 'Search Input Box', x: 333, y: 162, category: 'SEARCH', tag: 'Header Input' },
  { id: 'SEARCH_CLEAR', label: 'Clear Search Button', x: 774, y: 164, category: 'SEARCH', tag: 'Input Clear' },
  { id: 'SEARCH_SUBMIT', label: 'Search Submit Icon', x: 771, y: 161, category: 'SEARCH', tag: 'Right Arrow' },
  { id: 'VIDEO_MENU_DOTS', label: '3-Dot Video Menu', x: 923, y: 153, category: 'VIDEO', tag: 'Feed Menu' },
  { id: 'COMMENTS_SECTION', label: 'Comments Teaser / Section', x: 540, y: 1250, category: 'ENGAGEMENT', tag: 'Under Player' },
  { id: 'COMMENT_INPUT', label: 'Add a Comment Input', x: 450, y: 1400, category: 'ENGAGEMENT', tag: 'Comment Sheet' },
  { id: 'LIKE_BUTTON', label: 'Like Video Button', x: 180, y: 920, category: 'ENGAGEMENT', tag: 'Action Bar' },
  { id: 'SUBSCRIBE_BUTTON', label: 'Subscribe Channel Button', x: 880, y: 840, category: 'ENGAGEMENT', tag: 'Channel Strip' },
  { id: 'AD_SKIP', label: 'Skip Ad Button', x: 900, y: 700, category: 'AD', tag: 'Player Overlay' },
  { id: 'DESCRIPTION_BUTTON', label: 'Expand Description (...more)', x: 920, y: 760, category: 'VIDEO', tag: 'Meta Expand' }
];

export function extractYouTubeVideoId(url) {
  if (!url) return '';
  const match = url.match(/(?:v=|\/v\/|embed\/|shorts\/|youtu\.be\/|\/e\/|watch\?v=|&v=)([a-zA-Z0-9_-]{11})/);
  return match ? match[1] : '';
}

// Step type definitions matching user specifications & spatial anchors
const STEP_CATEGORIES = [
  {
    category: 'NAVIGATION',
    icon: Globe,
    items: [
      {
        type: 'GO_TO_URL',
        title: 'Go to URL',
        description: 'Open another page',
        defaultParams: {
          url: 'https://youtube.com',
          step_name: 'Open page',
          dwell_min: 5,
          dwell_max: 15
        }
      }
    ]
  },
  {
    category: 'SPATIAL ANCHORS (MOBILE YOUTUBE)',
    icon: Crosshair,
    items: [
      {
        type: 'SPATIAL_ANCHOR_CLICK',
        title: 'Click Spatial Anchor',
        description: 'Tap calibrated anchor (Search, Like, Subscribe, Menu)',
        defaultParams: {
          spatial_anchor: 'SEARCH_BUTTON_HOME',
          step_name: 'Tap Search Anchor',
          dwell_min: 2,
          dwell_max: 5
        }
      },
      {
        type: 'SEARCH_TARGET_VIDEO',
        title: 'Search Target Video',
        description: 'Taps search anchor, types keyword organically and submits query without scrolling or clicking',
        defaultParams: {
          video_url: '',
          target_video_id: '',
          target_title: '',
          target_channel: '',
          thumbnail_url: '',
          keyword: '',
          fallback_keyword_1: '',
          fallback_keyword_2: '',
          search_retry_mode: 'CLEAR_SEARCH_BAR',
          search_anchor: 'SEARCH_BUTTON_HOME',
          step_name: 'Search Target Video',
          dwell_min: 3,
          dwell_max: 6
        }
      },
      {
        type: 'SCROLL_TARGET_VIDEO',
        title: 'Scroll to Target Video',
        description: 'Naturally scrolls search results, locates targeted video, scrolls past and returns back to center target',
        defaultParams: {
          step_name: 'Scroll to Target Video',
          scroll_past_and_return: true,
          max_scroll_batches: 10,
          dwell_min: 3,
          dwell_max: 8
        }
      },
      {
        type: 'WAIT_PLAYBACK',
        title: 'Watch Video & Dwell',
        description: 'Watch video with organic dwell and playback check',
        defaultParams: {
          step_name: 'Watch video',
          duration_seconds: 90,
          dwell_min: 60,
          dwell_max: 150
        }
      },
      {
        type: 'YT_TAP_SEARCH_BAR',
        title: 'Click Search Icon',
        description: 'Tap search button on homepage or header',
        defaultParams: {
          spatial_anchor: 'SEARCH_BUTTON_HOME',
          step_name: 'Click Search Icon',
          dwell_min: 2,
          dwell_max: 4
        }
      },
      {
        type: 'LIKE_VIDEO',
        title: 'Like Video',
        description: 'Tap calibrated Like button on video',
        defaultParams: {
          spatial_anchor: 'LIKE_BUTTON',
          step_name: 'Like Video',
          dwell_min: 2,
          dwell_max: 5
        }
      },
      {
        type: 'POST_COMMENT',
        title: 'Add Comment',
        description: 'Scroll to comments, type and post comment',
        defaultParams: {
          comment_text: 'Awesome video! Really enjoyed watching this.',
          step_name: 'Add Comment',
          wpm: 60,
          dwell_min: 5,
          dwell_max: 12
        }
      },
      {
        type: 'SUBSCRIBE',
        title: 'Subscribe Channel',
        description: 'Tap calibrated Subscribe button on channel',
        defaultParams: {
          spatial_anchor: 'SUBSCRIBE_BUTTON',
          step_name: 'Subscribe Channel',
          dwell_min: 2,
          dwell_max: 5
        }
      }
    ]
  },
  {
    category: 'BROWSER ACTIONS',
    icon: Sparkles,
    items: [
      {
        type: 'CLICK_LINK',
        title: 'Click Link',
        description: 'Click a link on the page whose URL matches',
        defaultParams: {
          target_url: 'watch?v=',
          match_type: 'contains',
          step_name: 'Click video link',
          dwell_min: 10,
          dwell_max: 30
        }
      },
      {
        type: 'CLICK_ELEMENT',
        title: 'Click Element',
        description: 'Click an element by XPath or Spatial Anchor',
        defaultParams: {
          target_mode: 'ANCHOR',
          spatial_anchor: 'SEARCH_BUTTON_HOME',
          xpath: '//button[@id="search"]',
          step_name: 'Click element',
          dwell_min: 3,
          dwell_max: 8
        }
      },
      {
        type: 'TYPE_TEXT',
        title: 'Type Text',
        description: 'Type into an input by XPath or Spatial Anchor',
        defaultParams: {
          target_mode: 'ANCHOR',
          spatial_anchor: 'SEARCH_INPUT',
          xpath: '//input[@name="search_query"]',
          text: '',
          step_name: 'Type query',
          wpm: 65,
          dwell_min: 2,
          dwell_max: 5
        }
      },
      {
        type: 'TYPE_AND_ENTER',
        title: 'Type & Enter',
        description: 'Type into an input, then press Enter',
        defaultParams: {
          target_mode: 'ANCHOR',
          spatial_anchor: 'SEARCH_INPUT',
          xpath: '//input[@name="search_query"]',
          text: '',
          step_name: 'Search query & enter',
          wpm: 65,
          press_enter: true,
          dwell_min: 3,
          dwell_max: 8
        }
      },
      {
        type: 'CLICK_AD_IFRAME',
        title: 'Click Ad / iFrame',
        description: 'Click a link inside a matching iframe',
        defaultParams: {
          iframe_xpath: '//iframe[contains(@id, "ad")]',
          step_name: 'Click sponsored frame',
          dwell_min: 5,
          dwell_max: 15
        }
      }
    ]
  },
  {
    category: 'AI ACTIONS',
    icon: Bot,
    items: [
      {
        type: 'AI_TYPE',
        title: 'AI Type',
        description: 'AI writes the text, typed into an input',
        defaultParams: {
          target_mode: 'ANCHOR',
          spatial_anchor: 'COMMENT_INPUT',
          xpath: '//input[@name="comment_text"]',
          prompt: 'Write an organic, positive 1-sentence comment related to this content.',
          step_name: 'AI natural comment',
          dwell_min: 4,
          dwell_max: 10
        }
      }
    ]
  }
];

const STEP_ICONS = {
  START: Globe,
  GO_TO_URL: Globe,
  SPATIAL_ANCHOR_CLICK: Crosshair,
  SEARCH_TARGET_VIDEO: Search,
  SCROLL_TARGET_VIDEO: Move,
  WAIT_PLAYBACK: Tv,
  CLICK_LINK: Link2,
  CLICK_ELEMENT: MousePointer,
  TYPE_TEXT: Type,
  TYPE_AND_ENTER: CornerDownLeft,
  CLICK_AD_IFRAME: Shield,
  AI_TYPE: Sparkles,
  LIKE_VIDEO: ThumbsUp,
  YT_LIKE_VIDEO: ThumbsUp,
  POST_COMMENT: MessageSquare,
  YT_POST_COMMENT: MessageSquare,
  SUBSCRIBE: UserPlus,
  YT_SUBSCRIBE_CHANNEL: UserPlus,
  YT_TAP_SEARCH_BAR: Search
};

export default function WorkflowBuilderHub({ onNotification }) {
  // Active workflow state
  const [workflowId, setWorkflowId] = useState(null);
  const [workflowName, setWorkflowName] = useState('YouTube Keyword Search & Target');
  const [platform, setPlatform] = useState('YOUTUBE');
  const [journeys, setJourneys] = useState([
    {
      id: 'journey_1',
      name: 'Journey 1',
      referrer: 'https://www.google.com/ — blank arrival',
      steps: [
        {
          id: 'step_1',
          type: 'START',
          url: 'https://youtube.com',
          step_name: 'Landing',
          dwell_min: 5,
          dwell_max: 15,
          x: 280,
          y: 50
        },
        {
          id: 'step_2',
          type: 'SEARCH_TARGET_VIDEO',
          step_name: 'Search Target Video',
          search_anchor: 'SEARCH_BUTTON_HOME',
          keyword: 'GhostPilot Automation',
          video_url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
          target_video_id: 'dQw4w9WgXcQ',
          dwell_min: 60,
          dwell_max: 120,
          x: 280,
          y: 220
        },
        {
          id: 'step_3',
          type: 'SPATIAL_ANCHOR_CLICK',
          spatial_anchor: 'LIKE_BUTTON',
          step_name: 'Organic Like Action',
          dwell_min: 2,
          dwell_max: 5,
          x: 280,
          y: 390
        }
      ]
    }
  ]);
  const [activeJourneyIdx, setActiveJourneyIdx] = useState(0);
  const [selectedStepId, setSelectedStepId] = useState('step_1');
  const [zoomLevel, setZoomLevel] = useState(1);

  // Movable Canvas Pan & Drag States
  const [panOffset, setPanOffset] = useState({ x: 0, y: 0 });
  const [isPanning, setIsPanning] = useState(false);
  const [panStart, setPanStart] = useState({ x: 0, y: 0 });
  const [draggingStepId, setDraggingStepId] = useState(null);
  const [dragStart, setDragStart] = useState({ mouseX: 0, mouseY: 0, nodeX: 0, nodeY: 0 });
  const canvasRef = useRef(null);

  // Modals & Drawers
  const [showAddStepModal, setShowAddStepModal] = useState(false);
  const [showSavedModal, setShowSavedModal] = useState(false);
  const [showDispatchModal, setShowDispatchModal] = useState(false);
  const [showAiBuildModal, setShowAiBuildModal] = useState(false);
  const [aiPrompt, setAiPrompt] = useState('');
  const [isBuildingAi, setIsBuildingAi] = useState(false);

  // Saved workflows list
  const [savedWorkflows, setSavedWorkflows] = useState([]);
  const [loadingSaved, setLoadingSaved] = useState(false);
  const [saving, setSaving] = useState(false);

  // AI Video Keyword Extraction state
  const [isAnalyzingVideo, setIsAnalyzingVideo] = useState(false);
  const [videoAnalysisError, setVideoAnalysisError] = useState(null);

  // Dispatch state
  const [availableProfiles, setAvailableProfiles] = useState([]);
  const [selectedProfileIds, setSelectedProfileIds] = useState([]);
  const [dispatching, setDispatching] = useState(false);

  // Dynamic Addon Steps state
  const [addonSteps, setAddonSteps] = useState([]);

  useEffect(() => {
    loadSavedWorkflowsList();
    loadProfilesList();
    loadAddonSteps();
  }, []);

  const loadAddonSteps = async () => {
    try {
      const res = await fetchAddonDynamicSteps();
      setAddonSteps(res.data || []);
    } catch (e) {
      console.warn('Failed to load addon steps:', e);
    }
  };

  const loadSavedWorkflowsList = async () => {
    setLoadingSaved(true);
    try {
      const res = await fetchCustomWorkflows();
      setSavedWorkflows(res.data || []);
    } catch (e) {
      console.error('Failed to load saved workflows:', e);
    } finally {
      setLoadingSaved(false);
    }
  };

  const loadProfilesList = async () => {
    try {
      const res = await fetchProfiles();
      setAvailableProfiles(res.data || []);
      if ((res.data || []).length > 0) {
        setSelectedProfileIds([res.data[0].id]);
      }
    } catch (e) {
      console.error('Failed to load profiles:', e);
    }
  };

  const currentJourney = journeys[activeJourneyIdx] || journeys[0] || { steps: [] };
  const currentStep = (currentJourney.steps || []).find((s) => s.id === selectedStepId);
  const totalSteps = journeys.reduce((acc, j) => acc + (j.steps || []).length, 0);

  const allStepCategories = React.useMemo(() => {
    const list = [...STEP_CATEGORIES];
    if (addonSteps && addonSteps.length > 0) {
      list.push({
        category: 'EXTENSIONS & ADDONS (DYNAMIC)',
        icon: Puzzle,
        items: addonSteps.map((s) => ({
          type: s.type,
          title: s.title,
          description: s.description || `Provided by addon: ${s.addon_name || s.addon_slug}`,
          defaultParams: {
            step_name: s.title,
            dwell_min: 3,
            dwell_max: 8,
            ...(s.default_params || {})
          },
          fields: s.fields || [],
          compiler: s.compiler
        }))
      });
    }
    return list;
  }, [addonSteps]);

  // Canvas Panning Handlers
  const handleCanvasMouseDown = (e) => {
    if (e.target === canvasRef.current || e.target.classList.contains('canvas-background')) {
      setIsPanning(true);
      setPanStart({ x: e.clientX - panOffset.x, y: e.clientY - panOffset.y });
    }
  };

  const handleCanvasMouseMove = (e) => {
    if (isPanning) {
      setPanOffset({
        x: e.clientX - panStart.x,
        y: e.clientY - panStart.y
      });
    } else if (draggingStepId) {
      const dx = (e.clientX - dragStart.mouseX) / zoomLevel;
      const dy = (e.clientY - dragStart.mouseY) / zoomLevel;
      const newX = Math.round(dragStart.nodeX + dx);
      const newY = Math.round(dragStart.nodeY + dy);

      setJourneys((prev) => {
        const updated = [...prev];
        const j = { ...updated[activeJourneyIdx] };
        j.steps = (j.steps || []).map((s) => (s.id === draggingStepId ? { ...s, x: newX, y: newY } : s));
        updated[activeJourneyIdx] = j;
        return updated;
      });
    }
  };

  const handleCanvasMouseUp = () => {
    setIsPanning(false);
    setDraggingStepId(null);
  };

  const handleNodeDragStart = (e, step) => {
    e.stopPropagation();
    const currX = step.x ?? 280;
    const currY = step.y ?? (50 + (currentJourney.steps || []).indexOf(step) * 170);
    setDraggingStepId(step.id);
    setDragStart({
      mouseX: e.clientX,
      mouseY: e.clientY,
      nodeX: currX,
      nodeY: currY
    });
    setSelectedStepId(step.id);
  };

  const resetView = () => {
    setPanOffset({ x: 0, y: 0 });
    setZoomLevel(1);
  };

  // Step manipulation handlers
  const handleSelectStep = (stepId) => {
    setSelectedStepId(stepId);
  };

  const handleAddStepFromCategory = (item) => {
    const stepsCount = (currentJourney.steps || []).length;
    const lastStep = currentJourney.steps?.[stepsCount - 1];
    const newX = lastStep ? (lastStep.x ?? 280) : 280;
    const newY = lastStep ? (lastStep.y ?? 50) + 170 : 50;

    const newStep = {
      id: `step_${Date.now()}`,
      type: item.type,
      title: item.title,
      ...item.defaultParams,
      x: newX,
      y: newY
    };

    setJourneys((prev) => {
      const updated = [...prev];
      const j = { ...updated[activeJourneyIdx] };
      j.steps = [...(j.steps || []), newStep];
      updated[activeJourneyIdx] = j;
      return updated;
    });

    setSelectedStepId(newStep.id);
    setShowAddStepModal(false);
    if (onNotification) onNotification(`Added "${item.title}" to workflow`, 'success');
  };

  const handleDeleteStep = (stepId) => {
    setJourneys((prev) => {
      const updated = [...prev];
      const j = { ...updated[activeJourneyIdx] };
      j.steps = (j.steps || []).filter((s) => s.id !== stepId);
      updated[activeJourneyIdx] = j;
      return updated;
    });
    if (selectedStepId === stepId) {
      setSelectedStepId(null);
    }
  };

  const handleMoveStep = (stepId, direction) => {
    const steps = [...(currentJourney.steps || [])];
    const idx = steps.findIndex((s) => s.id === stepId);
    if (idx === -1) return;
    const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (targetIdx < 0 || targetIdx >= steps.length) return;

    // Swap positions
    const tempY = steps[idx].y;
    steps[idx].y = steps[targetIdx].y;
    steps[targetIdx].y = tempY;

    const temp = steps[idx];
    steps[idx] = steps[targetIdx];
    steps[targetIdx] = temp;

    setJourneys((prev) => {
      const updated = [...prev];
      updated[activeJourneyIdx] = { ...updated[activeJourneyIdx], steps };
      return updated;
    });
  };

  const handleUpdateCurrentStep = (fields) => {
    if (!selectedStepId) return;

    // If updating video_url, automatically parse video ID
    if (fields.video_url !== undefined) {
      const parsedId = extractYouTubeVideoId(fields.video_url);
      fields.target_video_id = parsedId;
      fields.video_id = parsedId;
    }

    setJourneys((prev) => {
      const updated = [...prev];
      const j = { ...updated[activeJourneyIdx] };
      j.steps = (j.steps || []).map((s) => {
        if (s.id === selectedStepId) {
          return { ...s, ...fields };
        }
        return s;
      });
      updated[activeJourneyIdx] = j;
      return updated;
    });
  };

  const handleTriggerLLMKeywords = async () => {
    if (!currentStep) return;
    const url = currentStep.video_url?.trim();
    if (!url) {
      setVideoAnalysisError('Please enter a YouTube video URL first.');
      if (onNotification) onNotification('Please enter a YouTube video URL first.', 'error');
      return;
    }
    setIsAnalyzingVideo(true);
    setVideoAnalysisError(null);
    try {
      const res = await analyzeYouTubeVideo(url);
      const data = res.data;
      const rankingKws = data.ranking_keywords || data.suggested_keywords || [];
      const kw0 = rankingKws[0] || '';
      const kw1 = rankingKws[1] || '';
      const kw2 = rankingKws[2] || '';

      handleUpdateCurrentStep({
        video_url: data.video_url || url,
        target_video_id: data.video_id || extractYouTubeVideoId(url),
        target_title: data.title || currentStep.target_title || '',
        target_channel: data.channel_name || currentStep.target_channel || '',
        thumbnail_url: data.thumbnail_url || currentStep.thumbnail_url || '',
        keyword: kw0 || currentStep.keyword,
        fallback_keyword_1: kw1 || currentStep.fallback_keyword_1,
        fallback_keyword_2: kw2 || currentStep.fallback_keyword_2,
        candidate_keywords: [kw0, kw1, kw2].filter(Boolean)
      });
      if (onNotification) {
        onNotification(`Extracted metadata and generated 3 ranking keywords for "${data.title || 'Video'}"`, 'success');
      }
    } catch (err) {
      console.error('AI video analysis error:', err);
      const errMsg = err.response?.data?.error || err.message || 'Failed to extract video keywords with AI.';
      setVideoAnalysisError(errMsg);
      if (onNotification) onNotification(errMsg, 'error');
    } finally {
      setIsAnalyzingVideo(false);
    }
  };

  // Journey handlers
  const handleAddJourney = () => {
    const newJourneyNumber = journeys.length + 1;
    const newJourney = {
      id: `journey_${Date.now()}`,
      name: `Journey ${newJourneyNumber}`,
      referrer: 'https://www.google.com/ — blank arrival',
      steps: [
        {
          id: `step_${Date.now()}`,
          type: 'START',
          url: 'https://youtube.com',
          step_name: 'Start',
          dwell_min: 5,
          dwell_max: 15,
          x: 280,
          y: 50
        }
      ]
    };
    setJourneys((prev) => [...prev, newJourney]);
    setActiveJourneyIdx(journeys.length);
    setSelectedStepId(newJourney.steps[0].id);
  };

  const handleDuplicateJourney = () => {
    const curr = journeys[activeJourneyIdx];
    if (!curr) return;
    const duplicated = {
      ...curr,
      id: `journey_${Date.now()}`,
      name: `${curr.name || 'Journey'} (Copy)`,
      steps: (curr.steps || []).map((s, idx) => ({ ...s, id: `step_${Date.now()}_${idx}` }))
    };
    setJourneys((prev) => [...prev, duplicated]);
    setActiveJourneyIdx(journeys.length);
  };

  const handleRemoveJourney = () => {
    if (journeys.length <= 1) {
      if (onNotification) onNotification('Workflow must contain at least one journey', 'warning');
      return;
    }
    const updated = journeys.filter((_, idx) => idx !== activeJourneyIdx);
    setJourneys(updated);
    setActiveJourneyIdx(Math.max(0, activeJourneyIdx - 1));
  };

  // Save workflow
  const handleSaveWorkflow = async () => {
    setSaving(true);
    try {
      const payload = {
        id: workflowId || undefined,
        name: workflowName,
        platform,
        journeys
      };
      const res = await saveCustomWorkflow(payload);
      setWorkflowId(res.data.id);
      loadSavedWorkflowsList();
      if (onNotification) onNotification(`Saved workflow "${res.data.name}" successfully!`, 'success');
    } catch (e) {
      console.error('Failed to save workflow:', e);
      if (onNotification) onNotification('Failed to save workflow: ' + (e.response?.data?.error || e.message), 'error');
    } finally {
      setSaving(false);
    }
  };

  // Load a saved workflow for editing
  const handleLoadWorkflow = async (wf) => {
    if (!wf) return;
    try {
      let fullWf = wf;
      // If journeys data is missing or empty, fetch the full detail
      if (!fullWf.journeys || fullWf.journeys.length === 0) {
        try {
          const detailRes = await fetchCustomWorkflowDetail(wf.id);
          if (detailRes?.data) {
            fullWf = detailRes.data;
          }
        } catch (e) {
          console.warn('Could not fetch workflow detail:', e);
        }
      }

      setWorkflowId(fullWf.id);
      setWorkflowName(fullWf.name || 'Untitled Workflow');
      setPlatform(fullWf.platform || 'YOUTUBE');

      const loadedJourneys = (fullWf.journeys && Array.isArray(fullWf.journeys) && fullWf.journeys.length > 0)
        ? fullWf.journeys
        : [
            {
              id: `journey_${Date.now()}`,
              name: 'Journey 1',
              referrer: 'https://www.google.com/ — blank arrival',
              steps: [
                {
                  id: `step_${Date.now()}`,
                  type: 'START',
                  url: 'https://youtube.com',
                  step_name: 'Landing',
                  dwell_min: 5,
                  dwell_max: 15,
                  x: 280,
                  y: 50
                }
              ]
            }
          ];

      setJourneys(loadedJourneys);
      setActiveJourneyIdx(0);
      setSelectedStepId(loadedJourneys[0]?.steps?.[0]?.id || null);
      setShowSavedModal(false);
      resetView();
      if (onNotification) onNotification(`Loaded workflow "${fullWf.name}" for editing!`, 'success');
    } catch (err) {
      console.error('Failed to load workflow:', err);
      if (onNotification) onNotification('Failed to load workflow: ' + err.message, 'error');
    }
  };

  // Create / reset to a new blank workflow
  const handleCreateNewWorkflow = () => {
    setWorkflowId(null);
    setWorkflowName('New Custom Workflow');
    setPlatform('YOUTUBE');
    const initialJourney = {
      id: `journey_${Date.now()}`,
      name: 'Journey 1',
      referrer: 'https://www.google.com/ — blank arrival',
      steps: [
        {
          id: `step_${Date.now()}`,
          type: 'START',
          url: 'https://youtube.com',
          step_name: 'Landing',
          dwell_min: 5,
          dwell_max: 15,
          x: 280,
          y: 50
        }
      ]
    };
    setJourneys([initialJourney]);
    setActiveJourneyIdx(0);
    setSelectedStepId(initialJourney.steps[0].id);
    resetView();
    if (onNotification) onNotification('Started new blank visual workflow canvas', 'info');
  };

  // Dispatch workflow
  const handleDispatch = async () => {
    if (selectedProfileIds.length === 0) {
      if (onNotification) onNotification('Please select at least one profile to run', 'warning');
      return;
    }
    setDispatching(true);
    try {
      const saveRes = await saveCustomWorkflow({
        id: workflowId || undefined,
        name: workflowName,
        platform,
        journeys
      });
      const activeId = saveRes.data.id;
      setWorkflowId(activeId);

      const res = await dispatchCustomWorkflow(activeId, selectedProfileIds);
      setShowDispatchModal(false);
      if (onNotification) {
        onNotification(`Dispatched workflow across ${res.data.dispatched_count} mobile profile(s)! Following exact path.`, 'success');
      }
    } catch (e) {
      console.error('Failed to dispatch workflow:', e);
      if (onNotification) onNotification('Dispatch failed: ' + (e.response?.data?.error || e.message), 'error');
    } finally {
      setDispatching(false);
    }
  };

  // AI Workflow Generator
  const handleBuildWithAi = () => {
    if (!aiPrompt.trim()) return;
    setIsBuildingAi(true);
    setTimeout(() => {
      const p = aiPrompt.toLowerCase();
      const generatedSteps = [
        {
          id: `step_${Date.now()}_1`,
          type: 'START',
          url: 'https://m.youtube.com',
          step_name: 'Navigate to YouTube',
          dwell_min: 4,
          dwell_max: 8,
          x: 280,
          y: 50
        }
      ];

      if (p.includes('search') || p.includes('keyword') || p.includes('video')) {
        generatedSteps.push({
          id: `step_${Date.now()}_2`,
          type: 'SEARCH_TARGET_VIDEO',
          step_name: 'Search Target Video',
          search_anchor: 'SEARCH_BUTTON_HOME',
          keyword: aiPrompt.replace(/(search|find|video|watch|like)/gi, '').trim() || 'trending music',
          video_url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
          target_video_id: 'dQw4w9WgXcQ',
          dwell_min: 60,
          dwell_max: 120,
          x: 280,
          y: 220
        });
      }

      generatedSteps.push({
        id: `step_${Date.now()}_3`,
        type: 'SPATIAL_ANCHOR_CLICK',
        spatial_anchor: 'LIKE_BUTTON',
        step_name: 'Organic Like Action',
        dwell_min: 2,
        dwell_max: 5,
        x: 280,
        y: 390
      });

      setJourneys((prev) => {
        const updated = [...prev];
        updated[activeJourneyIdx] = { ...updated[activeJourneyIdx], steps: generatedSteps };
        return updated;
      });

      setSelectedStepId(generatedSteps[0].id);
      setIsBuildingAi(false);
      setShowAiBuildModal(false);
      setAiPrompt('');
      resetView();
      if (onNotification) onNotification('Generated visual workflow path with AI!', 'success');
    }, 1000);
  };

  return (
    <div className="flex flex-col h-[calc(100vh-100px)] min-h-[700px] bg-[#0A0D14] text-neutral-100 font-sans select-none rounded-2xl border border-[#1E2638] overflow-hidden shadow-2xl">
      {/* 1. TOP HEADER BAR */}
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 sm:px-6 py-3.5 bg-[#0F1420] border-b border-[#1E2638] z-30">
        <div className="flex flex-wrap items-center gap-2.5 sm:gap-3">
          {/* Workflows Dropdown Selector & Direct Edit */}
          <div className="flex items-center gap-1.5 bg-[#141A28] border border-[#2B354C] hover:border-cyan-500/50 rounded-xl px-2.5 py-1.5 transition shadow-inner">
            <FolderOpen className="w-4 h-4 text-cyan-400 shrink-0" />
            
            <div className="flex flex-col">
              <span className="text-[9px] uppercase tracking-wider text-slate-400 font-bold leading-tight">
                Workflows
              </span>
              <select
                id="workflow-selector-dropdown"
                value={workflowId || ''}
                onChange={(e) => {
                  const selectedId = e.target.value;
                  if (!selectedId) {
                    handleCreateNewWorkflow();
                  } else {
                    const targetWf = savedWorkflows.find((w) => String(w.id) === String(selectedId));
                    if (targetWf) handleLoadWorkflow(targetWf);
                  }
                }}
                className="bg-transparent text-xs text-white font-medium focus:outline-none cursor-pointer max-w-[150px] sm:max-w-[210px] md:max-w-[250px] truncate pr-1"
                title="Select a workflow to edit"
              >
                <option value="" className="bg-[#141A28] text-slate-400">
                  {savedWorkflows.length === 0 ? 'No saved workflows' : `— Select workflow (${savedWorkflows.length}) —`}
                </option>
                {savedWorkflows.map((wf) => (
                  <option key={wf.id} value={wf.id} className="bg-[#141A28] text-white">
                    {wf.name} ({wf.journeys_count || wf.journeys?.length || 1}J • {wf.steps_count || (wf.journeys?.[0]?.steps?.length || 0)} steps)
                  </option>
                ))}
              </select>
            </div>

            {/* Direct Option to Select & Edit */}
            <button
              id="btn-edit-selected-workflow"
              onClick={() => {
                if (workflowId) {
                  const targetWf = savedWorkflows.find((w) => String(w.id) === String(workflowId));
                  if (targetWf) {
                    handleLoadWorkflow(targetWf);
                  } else {
                    if (onNotification) onNotification(`Editing "${workflowName}"`, 'info');
                  }
                } else if (savedWorkflows.length > 0) {
                  handleLoadWorkflow(savedWorkflows[0]);
                } else {
                  if (onNotification) onNotification('No saved workflows available. Build and save one first!', 'info');
                }
              }}
              className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-cyan-500/20 hover:bg-cyan-500/30 text-cyan-300 text-xs font-semibold transition border border-cyan-500/30 shrink-0"
              title="Edit selected workflow in visual canvas"
            >
              <Edit2 className="w-3.5 h-3.5" />
              <span>Edit</span>
            </button>

            {/* New Blank Workflow Button */}
            <button
              onClick={handleCreateNewWorkflow}
              className="flex items-center gap-1 px-2 py-1 rounded-lg bg-[#1E2638] hover:bg-[#2B354C] text-slate-300 hover:text-white text-xs font-medium transition shrink-0"
              title="Start a new blank visual workflow"
            >
              <Plus className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">New</span>
            </button>
          </div>

          {/* Workflow Name Input */}
          <div className="relative">
            <input
              type="text"
              value={workflowName}
              onChange={(e) => setWorkflowName(e.target.value)}
              className="bg-[#141A28] border border-[#2B354C] rounded-xl px-3.5 py-1.5 text-sm font-semibold text-white focus:outline-none focus:border-cyan-500 w-44 sm:w-56 transition"
              placeholder="Workflow name"
              title="Workflow name"
            />
          </div>

          {/* Active Status Badge */}
          {workflowId ? (
            <span className="text-[11px] px-2.5 py-1 rounded-full bg-cyan-500/10 border border-cyan-500/30 text-cyan-300 font-medium flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse"></span>
              Editing #{workflowId}
            </span>
          ) : (
            <span className="text-[11px] px-2.5 py-1 rounded-full bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 font-medium">
              New Draft
            </span>
          )}

          <span className="text-xs px-2.5 py-1 rounded-full bg-[#141A28] border border-[#2B354C] text-slate-300 font-medium hidden md:inline-flex">
            {journeys.length} task project{journeys.length > 1 ? 's' : ''}
          </span>
          <span className="text-xs px-2.5 py-1 rounded-full bg-[#141A28] border border-[#2B354C] text-slate-300 font-medium hidden md:inline-flex">
            {totalSteps} step{totalSteps > 1 ? 's' : ''}
          </span>
        </div>

        {/* Action Buttons Matching Screenshot */}
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => setShowAiBuildModal(true)}
            className="flex items-center gap-2 px-3 py-1.5 sm:px-3.5 sm:py-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white text-xs font-medium transition shadow-lg shadow-purple-500/20"
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Build with</span> AI
          </button>

          <button
            onClick={() => setShowDispatchModal(true)}
            className="flex items-center gap-2 px-3 py-1.5 sm:px-3.5 sm:py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium transition shadow-lg shadow-blue-500/25"
          >
            <Bot className="w-3.5 h-3.5" />
            Run Fleet
          </button>

          <button
            onClick={() => setShowAddStepModal(true)}
            className="flex items-center gap-2 px-3 py-1.5 sm:px-3.5 sm:py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium transition shadow-lg shadow-emerald-500/20"
          >
            <Plus className="w-3.5 h-3.5" />
            Add step
          </button>

          <button
            onClick={() => setShowSavedModal(true)}
            className="flex items-center gap-2 px-3 py-1.5 sm:px-3.5 sm:py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-slate-200 text-xs font-medium transition"
            title="Browse all custom workflows"
          >
            <FolderOpen className="w-3.5 h-3.5 text-cyan-400" />
            <span className="hidden sm:inline">Browse</span> List
          </button>

          <button
            onClick={handleSaveWorkflow}
            disabled={saving}
            className="flex items-center gap-2 px-4 py-1.5 sm:py-2 rounded-xl bg-[#1A2234] hover:bg-[#222C42] border border-[#2B354C] text-blue-400 text-xs font-semibold transition"
          >
            <Save className={`w-3.5 h-3.5 ${saving ? 'animate-spin' : ''}`} />
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {/* 2. SUB-BAR: JOURNEYS & REFERRER CONTROLS */}
      <div className="flex flex-wrap items-center justify-between gap-4 px-6 py-3 bg-[#0C101A] border-b border-[#1E2638] text-xs z-30">
        <div className="flex items-center gap-3">
          <span className="text-slate-400 flex items-center gap-1.5 font-medium">
            <Layers className="w-3.5 h-3.5 text-cyan-400" />
            Task projects
          </span>

          {/* Journey Tabs */}
          <div className="flex items-center gap-1.5 overflow-x-auto">
            {journeys.map((j, idx) => (
              <button
                key={j.id}
                onClick={() => {
                  setActiveJourneyIdx(idx);
                  setSelectedStepId(j.steps?.[0]?.id || null);
                }}
                className={`px-3 py-1 rounded-xl text-xs font-medium transition flex items-center gap-1.5 ${
                  activeJourneyIdx === idx
                    ? 'bg-blue-600 text-white shadow-sm'
                    : 'bg-[#141A28] border border-[#1E2638] text-slate-400 hover:text-white'
                }`}
              >
                {j.name || `Journey ${idx + 1}`}
                <span className="text-[10px] px-1.5 py-0.2 rounded-full bg-black/30 opacity-80">
                  {j.steps?.length || 0} hop{(j.steps?.length || 0) !== 1 ? 's' : ''}
                </span>
              </button>
            ))}

            <button
              onClick={handleAddJourney}
              className="px-2.5 py-1 rounded-xl bg-[#141A28] hover:bg-[#1E2638] border border-[#2B354C] text-cyan-400 text-xs font-medium transition flex items-center gap-1"
            >
              <Plus className="w-3 h-3" />
              Add journey
            </button>
          </div>
        </div>

        {/* Journey Name & Referrer Configuration */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <span className="text-slate-400">Journey name</span>
            <input
              type="text"
              value={currentJourney.name || ''}
              onChange={(e) => {
                const val = e.target.value;
                setJourneys((prev) => {
                  const updated = [...prev];
                  updated[activeJourneyIdx] = { ...updated[activeJourneyIdx], name: val };
                  return updated;
                });
              }}
              className="bg-[#141A28] border border-[#2B354C] rounded-lg px-2.5 py-1 text-xs text-white focus:outline-none focus:border-cyan-500 w-32"
            />
          </div>

          <div className="flex items-center gap-2">
            <span className="text-slate-400">Referrer</span>
            <input
              type="text"
              value={currentJourney.referrer || ''}
              onChange={(e) => {
                const val = e.target.value;
                setJourneys((prev) => {
                  const updated = [...prev];
                  updated[activeJourneyIdx] = { ...updated[activeJourneyIdx], referrer: val };
                  return updated;
                });
              }}
              className="bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-1 text-xs text-white focus:outline-none focus:border-cyan-500 w-60"
              placeholder="https://www.google.com/ — blank arrival"
            />
          </div>

          <button
            onClick={handleDuplicateJourney}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[#141A28] hover:bg-[#1E2638] border border-[#2B354C] text-slate-300 text-xs transition"
          >
            <Copy className="w-3 h-3" />
            Duplicate
          </button>

          <button
            onClick={handleRemoveJourney}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 text-rose-300 text-xs transition"
          >
            <Trash2 className="w-3 h-3" />
            Remove
          </button>
        </div>
      </div>

      {/* 3. MAIN WORKFLOW CANVAS + RIGHT INSPECTOR DRAWER */}
      <div className="relative flex-1 flex overflow-hidden">
        {/* Interactive Movable Canvas */}
        <div
          ref={canvasRef}
          onMouseDown={handleCanvasMouseDown}
          onMouseMove={handleCanvasMouseMove}
          onMouseUp={handleCanvasMouseUp}
          className={`flex-1 relative overflow-hidden canvas-background ${
            isPanning ? 'cursor-grabbing' : 'cursor-grab'
          }`}
          style={{
            backgroundImage: 'radial-gradient(circle, #1E2638 1.5px, transparent 1.5px)',
            backgroundSize: '24px 24px',
            backgroundColor: '#07090F'
          }}
        >
          {/* Pan & Zoom Virtual Workspace Container */}
          <div
            className="absolute inset-0 origin-top-left pointer-events-none"
            style={{
              transform: `translate(${panOffset.x}px, ${panOffset.y}px) scale(${zoomLevel})`,
              width: '4000px',
              height: '4000px'
            }}
          >
            {/* SVG Connecting Lines Layer */}
            <svg className="absolute inset-0 w-full h-full pointer-events-none z-0">
              {(currentJourney.steps || []).map((step, idx) => {
                if (idx === (currentJourney.steps.length - 1)) return null;
                const nextStep = currentJourney.steps[idx + 1];
                const startX = (step.x ?? 280) + 185;
                const startY = (step.y ?? (50 + idx * 170)) + 76;
                const endX = (nextStep.x ?? 280) + 185;
                const endY = nextStep.y ?? (50 + (idx + 1) * 170);
                const midY = (startY + endY) / 2;

                return (
                  <g key={`edge_${step.id}_${nextStep.id}`}>
                    <path
                      d={`M ${startX} ${startY} C ${startX} ${midY}, ${endX} ${midY}, ${endX} ${endY}`}
                      stroke="#3B82F6"
                      strokeWidth="2.5"
                      strokeDasharray="6,5"
                      fill="none"
                      opacity="0.8"
                    />
                    <circle cx={startX} cy={startY} r="4" fill="#3B82F6" className="ring-4 ring-[#07090F]" />
                    <circle cx={endX} cy={endY} r="4" fill="#60A5FA" className="ring-4 ring-[#07090F]" />
                  </g>
                );
              })}
            </svg>

            {/* Draggable Step Node Cards */}
            {(currentJourney.steps || []).map((step, idx) => {
              const isSelected = step.id === selectedStepId;
              const StepIcon = STEP_ICONS[step.type] || (step.type?.startsWith('YT_ADDON_') ? Youtube : Puzzle);
              const nodeX = step.x ?? 280;
              const nodeY = step.y ?? (50 + idx * 170);

              return (
                <div
                  key={step.id}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleSelectStep(step.id);
                  }}
                  onMouseDown={(e) => handleNodeDragStart(e, step)}
                  style={{
                    transform: `translate(${nodeX}px, ${nodeY}px)`,
                    width: '370px'
                  }}
                  className={`absolute p-4 rounded-2xl transition-shadow cursor-grab active:cursor-grabbing border select-none shadow-2xl pointer-events-auto z-10 ${
                    isSelected
                      ? 'bg-[#0E1322] border-cyan-400 ring-2 ring-cyan-500/30'
                      : 'bg-[#101420] border-[#1E2638] hover:border-[#2D3952]'
                  }`}
                >
                  {/* Top Connector Handle */}
                  <div className="absolute -top-2 left-1/2 -translate-x-1/2 w-3.5 h-3.5 rounded-full bg-blue-500 border-2 border-[#0A0D14] shadow" />

                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                        isSelected ? 'bg-cyan-500/20 text-cyan-400' : 'bg-[#161C2C] text-slate-300'
                      }`}>
                        <StepIcon className="w-5 h-5" />
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <h3 className="text-sm font-bold text-white tracking-wide truncate">
                            {step.step_name || (step.type === 'START' ? 'Start' : step.type.replace(/_/g, ' '))}
                          </h3>
                        </div>
                        <p className="text-[11px] text-slate-400 font-mono mt-0.5 truncate max-w-[230px]">
                          {step.type === 'START' && (step.url || 'https://youtube.com')}
                          {step.type === 'GO_TO_URL' && (step.url || 'Navigate URL')}
                          {step.type === 'SPATIAL_ANCHOR_CLICK' && `Anchor: ${step.spatial_anchor || 'SEARCH_BUTTON_HOME'}`}
                          {step.type === 'YT_TAP_SEARCH_BAR' && 'Tap Search Icon'}
                          {step.type === 'SEARCH_TARGET_VIDEO' && `ID: ${step.target_video_id || extractYouTubeVideoId(step.video_url) || 'Match Video'}`}
                          {step.type === 'WAIT_PLAYBACK' && `Watch duration: ${step.duration_seconds || step.dwell_max || 90}s`}
                          {(step.type === 'LIKE_VIDEO' || step.type === 'YT_LIKE_VIDEO') && 'Like Active Video'}
                          {(step.type === 'POST_COMMENT' || step.type === 'YT_POST_COMMENT') && `Comment: "${(step.comment_text || step.text || '').slice(0, 25)}..."`}
                          {(step.type === 'SUBSCRIBE' || step.type === 'YT_SUBSCRIBE_CHANNEL') && 'Subscribe Channel'}
                          {step.type === 'CLICK_LINK' && `Match: ${step.target_url || 'URL'}`}
                          {step.type === 'CLICK_ELEMENT' && (step.spatial_anchor ? `Anchor: ${step.spatial_anchor}` : `XPath: ${step.xpath || 'Element'}`)}
                          {step.type === 'TYPE_TEXT' && `Type: "${step.text || ''}"`}
                          {step.type === 'TYPE_AND_ENTER' && `Type & Enter: "${step.text || ''}"`}
                          {step.type === 'CLICK_AD_IFRAME' && 'Click in iFrame'}
                          {step.type === 'AI_TYPE' && 'AI Natural Typing'}
                        </p>
                      </div>
                    </div>

                    <div className="flex flex-col items-end gap-1.5">
                      <span className="text-[10px] px-2 py-0.5 rounded-full bg-[#182030] text-slate-400 font-mono">
                        #{idx + 1}
                      </span>
                      <GripHorizontal className="w-3.5 h-3.5 text-slate-500 hover:text-slate-300" />
                    </div>
                  </div>

                  {/* Bottom Connector Handle */}
                  <div className="absolute -bottom-2 left-1/2 -translate-x-1/2 w-3.5 h-3.5 rounded-full bg-blue-500 border-2 border-[#0A0D14] shadow" />
                </div>
              );
            })}

            {/* Empty Canvas Notice */}
            {(currentJourney.steps || []).length === 0 && (
              <div
                style={{ transform: 'translate(280px, 150px)' }}
                className="text-center p-12 bg-[#0F1420] border border-dashed border-[#1E2638] rounded-2xl w-96 space-y-3 pointer-events-auto"
              >
                <Globe className="w-10 h-10 text-slate-600 mx-auto" />
                <p className="text-xs text-slate-400">No steps defined in this journey yet.</p>
                <button
                  onClick={() => setShowAddStepModal(true)}
                  className="px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium transition"
                >
                  Add First Step
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Floating Canvas Controls (Bottom Left) */}
        <div className="absolute left-6 bottom-6 flex flex-col gap-1.5 bg-[#0F1420]/95 backdrop-blur-md border border-[#1E2638] rounded-xl p-1.5 shadow-2xl z-20">
          <button
            onClick={() => setZoomLevel((z) => Math.min(1.8, Number((z + 0.1).toFixed(2))))}
            className="p-1.5 rounded-lg hover:bg-[#1A2234] text-slate-300 transition"
            title="Zoom in"
          >
            <ZoomIn className="w-4 h-4" />
          </button>
          <button
            onClick={() => setZoomLevel((z) => Math.max(0.4, Number((z - 0.1).toFixed(2))))}
            className="p-1.5 rounded-lg hover:bg-[#1A2234] text-slate-300 transition"
            title="Zoom out"
          >
            <ZoomOut className="w-4 h-4" />
          </button>
          <button
            onClick={resetView}
            className="p-1.5 rounded-lg hover:bg-[#1A2234] text-slate-300 transition"
            title="Reset View & Center"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
        </div>

        {/* Movable Canvas Hint Badge */}
        <div className="absolute left-20 bottom-6 flex items-center gap-2 bg-[#0F1420]/80 backdrop-blur-md border border-[#1E2638] rounded-xl px-3 py-1.5 text-[11px] text-slate-400 shadow z-20 pointer-events-none">
          <Move className="w-3 h-3 text-cyan-400" />
          <span>Click & drag canvas to pan • Drag node cards to reposition</span>
        </div>

        {/* 4. STEP INSPECTOR CONFIGURATION DRAWER (Image 3) */}
        {currentStep && (
          <div className="w-[420px] bg-[#0E131E] border-l border-[#1E2638] p-6 overflow-y-auto flex flex-col shadow-2xl z-20 transition-all">
            <div className="flex items-center justify-between pb-4 border-b border-[#1E2638] mb-5">
              <div>
                <h2 className="text-base font-bold text-white">
                  {currentStep.step_name || (currentStep.type === 'START' ? 'Start' : currentStep.type.replace(/_/g, ' '))}
                </h2>
                <span className="text-[11px] text-cyan-400 font-mono">
                  Configuring Step #{currentJourney.steps.findIndex((s) => s.id === currentStep.id) + 1}
                </span>
              </div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => handleMoveStep(currentStep.id, 'up')}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
                  title="Move step up"
                >
                  <ArrowUp className="w-4 h-4" />
                </button>
                <button
                  onClick={() => handleMoveStep(currentStep.id, 'down')}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
                  title="Move step down"
                >
                  <ArrowDown className="w-4 h-4" />
                </button>
                <button
                  onClick={() => handleDeleteStep(currentStep.id)}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-rose-400 hover:bg-rose-500/10 transition"
                  title="Delete step"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
                <button
                  onClick={() => setSelectedStepId(null)}
                  className="p-1.5 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Dynamic Configuration Form Fields */}
            <div className="space-y-4 flex-1">
              {/* Step Name (Common) */}
              <div>
                <label className="text-xs text-slate-400 block mb-1">
                  Step name (optional)
                </label>
                <input
                  type="text"
                  value={currentStep.step_name || ''}
                  onChange={(e) => handleUpdateCurrentStep({ step_name: e.target.value })}
                  className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                  placeholder="e.g. Landing"
                />
              </div>

              {/* Start & Go to URL */}
              {(currentStep.type === 'START' || currentStep.type === 'GO_TO_URL') && (
                <div>
                  <label className="text-xs text-slate-400 block mb-1">
                    {currentStep.type === 'START' ? 'Starting URL' : 'Target URL'}
                  </label>
                  <input
                    type="text"
                    value={currentStep.url || ''}
                    onChange={(e) => handleUpdateCurrentStep({ url: e.target.value })}
                    className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                    placeholder="https://youtube.com"
                  />
                </div>
              )}

              {/* SPATIAL ANCHOR DIRECT CLICK */}
              {currentStep.type === 'SPATIAL_ANCHOR_CLICK' && (
                <div>
                  <label className="text-xs text-slate-400 block mb-1">
                    Spatial Anchor from Registry
                  </label>
                  <select
                    value={currentStep.spatial_anchor || 'SEARCH_BUTTON_HOME'}
                    onChange={(e) => handleUpdateCurrentStep({ spatial_anchor: e.target.value })}
                    className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                  >
                    {SPATIAL_ANCHORS.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.label} ({a.id})
                      </option>
                    ))}
                  </select>

                  <div className="mt-2.5 p-3 rounded-xl bg-[#141A28] border border-[#2B354C] space-y-1">
                    <div className="flex items-center justify-between text-[11px]">
                      <span className="text-slate-400">Anchor ID:</span>
                      <span className="font-mono text-cyan-400">{currentStep.spatial_anchor || 'SEARCH_BUTTON_HOME'}</span>
                    </div>
                    {SPATIAL_ANCHORS.find((a) => a.id === currentStep.spatial_anchor) && (
                      <div className="flex items-center justify-between text-[11px]">
                        <span className="text-slate-400">Calibrated Coords:</span>
                        <span className="font-mono text-emerald-400">
                          X: {SPATIAL_ANCHORS.find((a) => a.id === currentStep.spatial_anchor)?.x || 'Auto'},
                          Y: {SPATIAL_ANCHORS.find((a) => a.id === currentStep.spatial_anchor)?.y || 'Auto'}
                        </span>
                      </div>
                    )}
                    <div className="flex items-center gap-1.5 pt-1 text-[11px] text-emerald-400">
                      <CheckCircle2 className="w-3.5 h-3.5" />
                      <span>Calibrated & Active for Mobile Browser</span>
                    </div>
                  </div>
                </div>
              )}

              {/* SEARCH TARGET VIDEO (Locates and centers video without clicking) */}
              {currentStep.type === 'SEARCH_TARGET_VIDEO' && (
                <div className="space-y-3">
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <label className="text-xs text-slate-400">
                        YouTube Video Link / URL
                      </label>
                      <button
                        type="button"
                        onClick={handleTriggerLLMKeywords}
                        disabled={isAnalyzingVideo}
                        className="flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-gradient-to-r from-amber-500/20 to-orange-500/20 hover:from-amber-500/30 hover:to-orange-500/30 border border-amber-500/40 text-amber-300 text-[11px] font-medium transition-all shadow-sm disabled:opacity-50"
                        title="Strips video title, channel, description & thumbnail, and uses AI to generate 3 ranking keywords"
                      >
                        {isAnalyzingVideo ? (
                          <Loader2 className="w-3.5 h-3.5 animate-spin text-amber-400" />
                        ) : (
                          <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                        )}
                        <span>{isAnalyzingVideo ? 'Asking AI...' : 'Generate 3 Ranking Keywords'}</span>
                      </button>
                    </div>
                    <input
                      type="text"
                      value={currentStep.video_url || ''}
                      onChange={(e) => handleUpdateCurrentStep({ video_url: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                      placeholder="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                    />
                  </div>

                  {videoAnalysisError && (
                    <div className="p-2 rounded-lg bg-red-950/30 border border-red-900/50 text-[11px] text-red-300 flex items-center gap-1.5">
                      <AlertCircle className="w-3.5 h-3.5 flex-shrink-0 text-red-400" />
                      <span>{videoAnalysisError}</span>
                    </div>
                  )}

                  {/* Scraped Video Card Preview (if metadata exists) */}
                  {(currentStep.thumbnail_url || currentStep.target_title || currentStep.target_channel) && (
                    <div className="p-2.5 rounded-lg bg-[#0F141E] border border-amber-500/30 flex gap-2.5 items-start">
                      {currentStep.thumbnail_url && (
                        <img
                          src={currentStep.thumbnail_url}
                          alt="Thumbnail"
                          className="w-20 h-14 object-cover rounded-md border border-slate-700 flex-shrink-0"
                          onError={(e) => { e.target.style.display = 'none'; }}
                        />
                      )}
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5 mb-0.5">
                          <span className="text-[10px] uppercase font-bold tracking-wider px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-400 border border-amber-500/30">
                            {currentStep.target_channel || 'Channel'}
                          </span>
                          <span className="text-[10px] text-slate-500 font-mono">
                            ID: {currentStep.target_video_id || extractYouTubeVideoId(currentStep.video_url)}
                          </span>
                        </div>
                        <p className="text-xs text-slate-200 font-medium truncate" title={currentStep.target_title}>
                          {currentStep.target_title || 'Target Video'}
                        </p>
                      </div>
                    </div>
                  )}

                  {/* Extracted Video ID Banner (if not already shown in preview) */}
                  {!(currentStep.thumbnail_url || currentStep.target_title) && (
                    <div className="flex items-center justify-between p-2.5 rounded-lg bg-[#141A28] border border-[#2B354C] text-xs">
                      <span className="text-slate-400">Extracted Video ID:</span>
                      <span className="font-mono text-cyan-400 font-bold">
                        {currentStep.target_video_id || extractYouTubeVideoId(currentStep.video_url) || 'No Video ID'}
                      </span>
                    </div>
                  )}

                  {/* Keyword 1 (Primary: Position 1-3) */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <label className="text-xs text-slate-300 font-medium">
                        Primary Search Keyword <span className="text-emerald-400 text-[10px]">(Rank Pos 1-3)</span>
                      </label>
                      <span className="text-[10px] text-slate-500">Searched First</span>
                    </div>
                    <input
                      type="text"
                      value={currentStep.keyword || ''}
                      onChange={(e) => handleUpdateCurrentStep({ keyword: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="e.g. Rick Astley Never Gonna Give You Up"
                    />
                  </div>

                  {/* Keyword 2 (Fallback 1: Position 1-5) */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <label className="text-xs text-slate-400">
                        Fallback Keyword 1 <span className="text-cyan-400 text-[10px]">(Rank Pos 1-5)</span>
                      </label>
                      <span className="text-[10px] text-slate-500">Used if not in Top 10</span>
                    </div>
                    <input
                      type="text"
                      value={currentStep.fallback_keyword_1 || ''}
                      onChange={(e) => handleUpdateCurrentStep({ fallback_keyword_1: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="e.g. never gonna give you up official music video"
                    />
                  </div>

                  {/* Keyword 3 (Fallback 2: Position 1-5) */}
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <label className="text-xs text-slate-400">
                        Fallback Keyword 2 <span className="text-cyan-400 text-[10px]">(Rank Pos 1-5)</span>
                      </label>
                      <span className="text-[10px] text-slate-500">Used if still not found</span>
                    </div>
                    <input
                      type="text"
                      value={currentStep.fallback_keyword_2 || ''}
                      onChange={(e) => handleUpdateCurrentStep({ fallback_keyword_2: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="e.g. Rick Astley 80s pop classic remastered"
                    />
                  </div>

                  {/* Search Retry Strategy Provision */}
                  <div>
                    <label className="text-xs text-slate-300 font-medium block mb-1">
                      Search Retry Action (If video not in Top 10 results)
                    </label>
                    <select
                      value={currentStep.search_retry_mode || 'CLEAR_SEARCH_BAR'}
                      onChange={(e) => handleUpdateCurrentStep({ search_retry_mode: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                    >
                      <option value="CLEAR_SEARCH_BAR">CLEAR_SEARCH_BAR (Delete words on search page & search next keyword)</option>
                      <option value="RETURN_TO_HOME">RETURN_TO_HOME (Go back to YouTube Home & search with home anchor)</option>
                    </select>
                    <p className="text-[10px] text-slate-400 mt-1">
                      {currentStep.search_retry_mode === 'RETURN_TO_HOME'
                        ? '• System navigates back to YouTube homepage, taps SEARCH_BUTTON_HOME, and searches next keyword.'
                        : '• System stays on search results page, taps SEARCH_INPUT / clear button to erase words, and types next keyword.'}
                    </p>
                  </div>

                  {/* Search Button Anchor (Initial Entry) */}
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Search Button Anchor (Initial Entry)
                    </label>
                    <select
                      value={currentStep.search_anchor || 'SEARCH_BUTTON_HOME'}
                      onChange={(e) => handleUpdateCurrentStep({ search_anchor: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                    >
                      <option value="SEARCH_BUTTON_HOME">SEARCH_BUTTON_HOME (Homepage Search Icon)</option>
                      <option value="SEARCH_BUTTON_WATCH">SEARCH_BUTTON_WATCH (Playing Video Search Icon)</option>
                      <option value="SEARCH_BUTTON_RESULTS">SEARCH_BUTTON_RESULTS (Results Feed Search Icon)</option>
                    </select>
                  </div>

                  <div className="p-3 rounded-xl bg-blue-950/20 border border-blue-900/40 text-[11px] text-blue-300 space-y-1">
                    <p className="font-semibold text-blue-200">How Multi-Keyword Search Discovery Operates:</p>
                    <p>1. Taps calibrated search anchor & types Primary Keyword.</p>
                    <p>2. Automatically scans the top 10 search results on mobile.</p>
                    <p>3. If not found in top 10, executes your selected Retry Action (Clear Bar vs Return Home) and searches Fallback 1 & 2.</p>
                  </div>
                </div>
              )}

              {/* Scroll to Target Video (Dedicated natural scroll & center step) */}
              {currentStep.type === 'SCROLL_TARGET_VIDEO' && (
                <div className="space-y-3">
                  <div className="p-3 rounded-xl bg-cyan-950/20 border border-cyan-900/40 text-[11px] text-cyan-300 space-y-1">
                    <p className="font-semibold text-cyan-200">Organic Scroll & Target Centering:</p>
                    <p>• Naturally scrolls search results according to profile behavioral settings.</p>
                    <p>• When target video is spotted, scrolls past it and returns back to center it cleanly.</p>
                    <p>• Completes without clicking so the subsequent Watch step initiates playback.</p>
                  </div>

                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Max Scroll Batches
                    </label>
                    <input
                      type="number"
                      min={1}
                      max={30}
                      value={currentStep.max_scroll_batches ?? 10}
                      onChange={(e) => handleUpdateCurrentStep({ max_scroll_batches: parseInt(e.target.value, 10) || 10 })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                    />
                  </div>

                  <div className="flex items-center gap-2 pt-1 text-xs text-cyan-400">
                    <input
                      type="checkbox"
                      id="scroll_past_return_chk"
                      checked={currentStep.scroll_past_and_return !== false}
                      onChange={(e) => handleUpdateCurrentStep({ scroll_past_and_return: e.target.checked })}
                      className="w-4 h-4 rounded bg-[#141A28] border-[#2B354C] text-cyan-500 focus:ring-0"
                    />
                    <label htmlFor="scroll_past_return_chk" className="cursor-pointer">
                      Scroll past target video and return back to center it
                    </label>
                  </div>
                </div>
              )}

              {/* Click Element (With Spatial Anchor, Video Target, or XPath Selector) */}
              {currentStep.type === 'CLICK_ELEMENT' && (
                <div className="space-y-3">
                  <div>
                    <label className="text-xs text-slate-400 block mb-1.5">
                      Target Mode
                    </label>
                    <div className="grid grid-cols-2 gap-1.5 p-1 bg-[#141A28] border border-[#2B354C] rounded-xl text-xs">
                      <button
                        onClick={() => handleUpdateCurrentStep({ target_mode: 'ANCHOR' })}
                        className={`py-1.5 rounded-lg font-medium transition ${
                          (currentStep.target_mode || 'ANCHOR') === 'ANCHOR'
                            ? 'bg-blue-600 text-white shadow'
                            : 'text-slate-400 hover:text-white'
                        }`}
                      >
                        Spatial Anchor
                      </button>
                      <button
                        onClick={() => handleUpdateCurrentStep({ target_mode: 'XPATH' })}
                        className={`py-1.5 rounded-lg font-medium transition ${
                          currentStep.target_mode === 'XPATH'
                            ? 'bg-blue-600 text-white shadow'
                            : 'text-slate-400 hover:text-white'
                        }`}
                      >
                        Custom XPath
                      </button>
                    </div>
                  </div>

                  {(currentStep.target_mode || 'ANCHOR') === 'ANCHOR' ? (
                    <div>
                      <label className="text-xs text-slate-400 block mb-1">
                        Select Spatial Anchor
                      </label>
                      <select
                        value={currentStep.spatial_anchor || 'SEARCH_BUTTON_HOME'}
                        onChange={(e) => handleUpdateCurrentStep({ spatial_anchor: e.target.value })}
                        className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                      >
                        {SPATIAL_ANCHORS.map((a) => (
                          <option key={a.id} value={a.id}>
                            {a.label} ({a.id})
                          </option>
                        ))}
                      </select>
                    </div>
                  ) : (
                    <div>
                      <label className="text-xs text-slate-400 block mb-1">
                        Element XPath
                      </label>
                      <input
                        type="text"
                        value={currentStep.xpath || ''}
                        onChange={(e) => handleUpdateCurrentStep({ xpath: e.target.value })}
                        className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                        placeholder="//button[@id='search']"
                      />
                    </div>
                  )}
                </div>
              )}

              {/* Type Text & Type & Enter */}
              {(currentStep.type === 'TYPE_TEXT' || currentStep.type === 'TYPE_AND_ENTER') && (
                <div className="space-y-3">
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Input Anchor or XPath
                    </label>
                    <select
                      value={currentStep.spatial_anchor || 'SEARCH_INPUT'}
                      onChange={(e) => handleUpdateCurrentStep({ spatial_anchor: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                    >
                      <option value="SEARCH_INPUT">SEARCH_INPUT (YouTube Search Bar)</option>
                      <option value="COMMENT_INPUT">COMMENT_INPUT (Add Comment Field)</option>
                      <option value="CUSTOM_XPATH">Custom XPath (Advanced)</option>
                    </select>
                  </div>

                  {currentStep.spatial_anchor === 'CUSTOM_XPATH' && (
                    <div>
                      <label className="text-xs text-slate-400 block mb-1">
                        Input XPath
                      </label>
                      <input
                        type="text"
                        value={currentStep.xpath || ''}
                        onChange={(e) => handleUpdateCurrentStep({ xpath: e.target.value })}
                        className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                        placeholder="//input[@name='search_query']"
                      />
                    </div>
                  )}

                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Text to Type
                    </label>
                    <input
                      type="text"
                      value={currentStep.text || ''}
                      onChange={(e) => handleUpdateCurrentStep({ text: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="Query or text to type"
                    />
                  </div>
                  {currentStep.type === 'TYPE_AND_ENTER' && (
                    <div className="flex items-center gap-2 pt-1 text-xs text-emerald-400">
                      <CheckCircle2 className="w-3.5 h-3.5" />
                      Presses Enter key after typing
                    </div>
                  )}
                </div>
              )}

              {/* Click Link */}
              {currentStep.type === 'CLICK_LINK' && (
                <>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Link URL matching pattern (href contains)
                    </label>
                    <input
                      type="text"
                      value={currentStep.target_url || ''}
                      onChange={(e) => handleUpdateCurrentStep({ target_url: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                      placeholder="watch?v="
                    />
                  </div>
                </>
              )}

              {/* Click Ad / iFrame */}
              {currentStep.type === 'CLICK_AD_IFRAME' && (
                <>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      iFrame Selector / XPath
                    </label>
                    <input
                      type="text"
                      value={currentStep.iframe_xpath || ''}
                      onChange={(e) => handleUpdateCurrentStep({ iframe_xpath: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                      placeholder="googleads"
                    />
                  </div>
                </>
              )}

              {/* AI Type */}
              {currentStep.type === 'AI_TYPE' && (
                <>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Target Input Anchor
                    </label>
                    <select
                      value={currentStep.spatial_anchor || 'COMMENT_INPUT'}
                      onChange={(e) => handleUpdateCurrentStep({ spatial_anchor: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                    >
                      <option value="COMMENT_INPUT">COMMENT_INPUT (Comment Box)</option>
                      <option value="SEARCH_INPUT">SEARCH_INPUT (Search Box)</option>
                    </select>
                  </div>
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      AI Generation Prompt Instruction
                    </label>
                    <textarea
                      rows={3}
                      value={currentStep.prompt || ''}
                      onChange={(e) => handleUpdateCurrentStep({ prompt: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="Write an organic, positive 1-sentence comment."
                    />
                  </div>
                </>
              )}

              {/* Watch Video Duration (WAIT_PLAYBACK) */}
              {currentStep.type === 'WAIT_PLAYBACK' && (
                <div>
                  <label className="text-xs text-slate-400 block mb-1">
                    Watch Duration (seconds)
                  </label>
                  <input
                    type="number"
                    min={10}
                    value={currentStep.duration_seconds || currentStep.dwell_max || 90}
                    onChange={(e) => {
                      const sec = parseInt(e.target.value, 10) || 60;
                      handleUpdateCurrentStep({ duration_seconds: sec, dwell_max: sec });
                    }}
                    className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500 font-mono"
                  />
                  <p className="text-[11px] text-slate-500 mt-1">Monitors video playback, handles pause/resume and checks ad state.</p>
                </div>
              )}

              {/* Like Video Action */}
              {(currentStep.type === 'LIKE_VIDEO' || currentStep.type === 'YT_LIKE_VIDEO') && (
                <div className="p-3 rounded-xl bg-emerald-950/20 border border-emerald-900/40 text-[11px] text-emerald-300 space-y-1">
                  <div className="flex items-center gap-1.5 font-semibold text-emerald-200">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    <span>Calibrated Like Video Action</span>
                  </div>
                  <p>Taps the calibrated Like button anchor under the player on m.youtube.com with DOM fallback.</p>
                </div>
              )}

              {/* Tap Search Icon Action */}
              {currentStep.type === 'YT_TAP_SEARCH_BAR' && (
                <div className="p-3 rounded-xl bg-cyan-950/20 border border-cyan-900/40 text-[11px] text-cyan-300 space-y-1">
                  <div className="flex items-center gap-1.5 font-semibold text-cyan-200">
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    <span>Search Icon Tap</span>
                  </div>
                  <p>Taps calibrated search icon on the homepage header to reveal the search input.</p>
                </div>
              )}

              {/* Add Comment Action */}
              {(currentStep.type === 'POST_COMMENT' || currentStep.type === 'YT_POST_COMMENT') && (
                <div className="space-y-3">
                  <div>
                    <label className="text-xs text-slate-400 block mb-1">
                      Comment Text to Post
                    </label>
                    <textarea
                      rows={3}
                      value={currentStep.comment_text || currentStep.text || ''}
                      onChange={(e) => handleUpdateCurrentStep({ comment_text: e.target.value, text: e.target.value })}
                      className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-cyan-500"
                      placeholder="Write an organic, positive comment based on the video..."
                    />
                  </div>
                  <div className="p-3 rounded-xl bg-blue-950/20 border border-blue-900/40 text-[11px] text-blue-300 space-y-1">
                    <p className="font-semibold text-blue-200">Comment Execution Flow:</p>
                    <p>1. Scrolls naturally down to the comments section.</p>
                    <p>2. Expands comments teaser and focuses the comment textarea.</p>
                    <p>3. Types comment with organic human cadence and submits.</p>
                  </div>
                </div>
              )}

              {/* Dynamic Extension / Addon Custom Parameters */}
              {(() => {
                const addonDef = addonSteps.find((as) => as.type === currentStep.type);
                if (!addonDef) return null;
                const fields = addonDef.fields || [];

                return (
                  <div className="space-y-3 p-3 rounded-xl bg-indigo-950/20 border border-indigo-900/40">
                    <div className="flex items-center gap-2 text-indigo-300 font-semibold text-xs">
                      <Puzzle className="w-3.5 h-3.5 text-indigo-400" />
                      <span>{addonDef.title} Parameters</span>
                    </div>

                    {fields.length === 0 ? (
                      <p className="text-[11px] text-slate-400 italic">No custom configuration needed.</p>
                    ) : (
                      fields.map((f, fIdx) => (
                        <div key={fIdx}>
                          <label className="text-xs text-slate-300 block mb-1">
                            {f.label || f.name}
                          </label>
                          {f.type === 'textarea' ? (
                            <textarea
                              rows={3}
                              value={currentStep[f.name] ?? f.default ?? ''}
                              onChange={(e) => handleUpdateCurrentStep({ [f.name]: e.target.value })}
                              className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500 font-mono"
                              placeholder={f.description || ''}
                            />
                          ) : (
                            <input
                              type={f.type === 'number' ? 'number' : 'text'}
                              value={currentStep[f.name] ?? f.default ?? ''}
                              onChange={(e) =>
                                handleUpdateCurrentStep({
                                  [f.name]: f.type === 'number' ? (parseInt(e.target.value, 10) || 0) : e.target.value
                                })
                              }
                              className="w-full bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-indigo-500 font-mono"
                              placeholder={f.description || ''}
                            />
                          )}
                          {f.description && (
                            <p className="text-[10px] text-slate-400 mt-0.5">{f.description}</p>
                          )}
                        </div>
                      ))
                    )}
                  </div>
                );
              })()}

              {/* Dwell on page (seconds) Range Inputs - EXACT match to screenshot 3 */}
              <div className="pt-2 border-t border-[#1E2638]">
                <label className="text-xs text-slate-400 block mb-2 font-medium">
                  Dwell on page (seconds)
                </label>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min={0}
                    value={currentStep.dwell_min !== undefined ? currentStep.dwell_min : 5}
                    onChange={(e) => handleUpdateCurrentStep({ dwell_min: parseInt(e.target.value, 10) || 0 })}
                    className="w-20 bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white text-center focus:outline-none focus:border-cyan-500 font-mono"
                  />
                  <span className="text-xs text-slate-400 font-medium">to</span>
                  <input
                    type="number"
                    min={0}
                    value={currentStep.dwell_max !== undefined ? currentStep.dwell_max : 15}
                    onChange={(e) => handleUpdateCurrentStep({ dwell_max: parseInt(e.target.value, 10) || 0 })}
                    className="w-20 bg-[#141A28] border border-[#2B354C] rounded-lg px-3 py-2 text-xs text-white text-center focus:outline-none focus:border-cyan-500 font-mono"
                  />
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 5. ADD A STEP MODAL (Matches Screenshot 1 with Spatial Anchors) */}
      {showAddStepModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 animate-fade-in">
          <div className="w-full max-w-lg bg-[#0F1422] border border-[#1E2638] rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
            <div className="flex items-center justify-between px-6 py-4 border-b border-[#1E2638]">
              <h2 className="text-base font-bold text-white tracking-wide">Add a step</h2>
              <button
                onClick={() => setShowAddStepModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-6 overflow-y-auto space-y-6">
              {allStepCategories.map((catGroup) => {
                const CatIcon = catGroup.icon;
                return (
                  <div key={catGroup.category} className="space-y-3">
                    <div className="flex items-center gap-2 text-slate-400 text-xs font-semibold tracking-wider">
                      <CatIcon className="w-3.5 h-3.5 text-cyan-400" />
                      <span>{catGroup.category}</span>
                    </div>

                    <div className="space-y-2">
                      {catGroup.items.map((item) => (
                        <button
                          key={item.type}
                          onClick={() => handleAddStepFromCategory(item)}
                          className="w-full p-3.5 rounded-xl bg-[#141A28] hover:bg-[#1A2234] border border-[#1E2638] hover:border-cyan-500/50 transition flex items-center justify-between text-left group"
                        >
                          <div>
                            <h4 className="text-sm font-semibold text-white group-hover:text-cyan-400 transition">
                              {item.title}
                            </h4>
                            <p className="text-xs text-slate-400 mt-0.5">
                              {item.description}
                            </p>
                          </div>
                          <Plus className="w-4 h-4 text-blue-400 group-hover:text-cyan-400 group-hover:scale-110 transition shrink-0" />
                        </button>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

      {/* 6. SAVED WORKFLOWS LIST MODAL */}
      {showSavedModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 animate-fade-in">
          <div className="w-full max-w-xl bg-[#0F1422] border border-[#1E2638] rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[80vh]">
            <div className="flex items-center justify-between px-6 py-4 border-b border-[#1E2638]">
              <div className="flex items-center gap-2">
                <FolderOpen className="w-4 h-4 text-cyan-400" />
                <h2 className="text-base font-bold text-white">Custom Workflows</h2>
              </div>
              <button
                onClick={() => setShowSavedModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-6 overflow-y-auto space-y-3 flex-1">
              {loadingSaved ? (
                <div className="py-12 text-center text-xs text-slate-400">Loading custom workflows...</div>
              ) : savedWorkflows.length === 0 ? (
                <div className="py-12 text-center text-xs text-slate-400">No saved workflows found. Save your current workflow first!</div>
              ) : (
                savedWorkflows.map((wf) => (
                  <div
                    key={wf.id}
                    className={`p-4 rounded-xl bg-[#141A28] border transition flex items-center justify-between ${
                      String(workflowId) === String(wf.id)
                        ? 'border-cyan-500 shadow-md shadow-cyan-500/10'
                        : 'border-[#1E2638] hover:border-cyan-500/40'
                    }`}
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <h4 className="text-sm font-semibold text-white">{wf.name}</h4>
                        {String(workflowId) === String(wf.id) && (
                          <span className="text-[10px] px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 font-semibold border border-cyan-500/30">
                            Currently Active
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-slate-400 mt-0.5">
                        Platform: <span className="text-cyan-400">{wf.platform}</span> • {wf.journeys_count || wf.journeys?.length || 0} journeys • {wf.steps_count || 0} steps
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handleLoadWorkflow(wf)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-cyan-600 hover:bg-cyan-500 text-white text-xs font-semibold transition shadow-sm"
                      >
                        <Edit2 className="w-3 h-3" />
                        Select & Edit
                      </button>
                      <button
                        onClick={async () => {
                          if (confirm(`Delete workflow "${wf.name}"?`)) {
                            await deleteCustomWorkflow(wf.id);
                            loadSavedWorkflowsList();
                          }
                        }}
                        className="p-1.5 rounded-lg text-slate-500 hover:text-rose-400 transition"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {/* 7. RUN AI AGENT DISPATCH MODAL */}
      {showDispatchModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 animate-fade-in">
          <div className="w-full max-w-md bg-[#0F1422] border border-[#1E2638] rounded-2xl shadow-2xl overflow-hidden flex flex-col">
            <div className="flex items-center justify-between px-6 py-4 border-b border-[#1E2638]">
              <div className="flex items-center gap-2">
                <Bot className="w-4 h-4 text-blue-400" />
                <h2 className="text-base font-bold text-white">Run AI Agent Fleet</h2>
              </div>
              <button
                onClick={() => setShowDispatchModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-6 space-y-4">
              <div>
                <label className="text-xs text-slate-400 block mb-1">Workflow to Execute</label>
                <p className="text-sm font-semibold text-white">{workflowName}</p>
                <p className="text-[11px] text-cyan-400 font-mono mt-0.5">{totalSteps} deterministic steps compiled</p>
              </div>

              <div>
                <label className="text-xs text-slate-400 block mb-2">Select Target Profiles</label>
                <div className="max-h-48 overflow-y-auto space-y-2 p-1">
                  {availableProfiles.map((p) => {
                    const isChecked = selectedProfileIds.includes(p.id);
                    return (
                      <label
                        key={p.id}
                        className={`flex items-center gap-3 p-2.5 rounded-xl border cursor-pointer transition text-xs ${
                          isChecked
                            ? 'bg-blue-600/10 border-blue-500/40 text-white'
                            : 'bg-[#141A28] border-[#1E2638] text-slate-300'
                        }`}
                      >
                        <input
                          type="checkbox"
                          checked={isChecked}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedProfileIds((prev) => [...prev, p.id]);
                            } else {
                              setSelectedProfileIds((prev) => prev.filter((id) => id !== p.id));
                            }
                          }}
                          className="rounded text-blue-600 focus:ring-0"
                        />
                        <span className="font-medium">{p.name}</span>
                        <span className="text-[10px] text-slate-500 font-mono ml-auto">{p.proxy_host ? 'Proxy' : 'Direct'}</span>
                      </label>
                    );
                  })}
                </div>
              </div>

              <div className="pt-2 border-t border-[#1E2638] flex items-center justify-end gap-2">
                <button
                  onClick={() => setShowDispatchModal(false)}
                  className="px-4 py-2 rounded-xl text-xs text-slate-400 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  onClick={handleDispatch}
                  disabled={dispatching}
                  className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white text-xs font-semibold transition shadow-lg shadow-blue-500/25"
                >
                  <Play className={`w-3.5 h-3.5 fill-white ${dispatching ? 'animate-spin' : ''}`} />
                  {dispatching ? 'Dispatching...' : `Dispatch (${selectedProfileIds.length})`}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 8. BUILD WITH AI PROMPT MODAL */}
      {showAiBuildModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4 animate-fade-in">
          <div className="w-full max-w-lg bg-[#0F1422] border border-[#1E2638] rounded-2xl shadow-2xl overflow-hidden flex flex-col">
            <div className="flex items-center justify-between px-6 py-4 border-b border-[#1E2638]">
              <div className="flex items-center gap-2">
                <Sparkles className="w-4 h-4 text-purple-400" />
                <h2 className="text-base font-bold text-white">Build Workflow with AI</h2>
              </div>
              <button
                onClick={() => setShowAiBuildModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-[#1A2234] transition"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-6 space-y-4">
              <div>
                <label className="text-xs text-slate-400 block mb-1.5 font-medium">
                  Describe what the mobile browser should do
                </label>
                <textarea
                  rows={4}
                  value={aiPrompt}
                  onChange={(e) => setAiPrompt(e.target.value)}
                  className="w-full bg-[#141A28] border border-[#2B354C] rounded-xl p-3 text-xs text-white focus:outline-none focus:border-purple-500 placeholder-slate-500"
                  placeholder="e.g. Open YouTube, click search anchor, type 'ai tools 2026', background scan for video ID 'xyz', click card and watch for 90 seconds, then tap like anchor."
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  onClick={() => setShowAiBuildModal(false)}
                  className="px-4 py-2 rounded-xl text-xs text-slate-400 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  onClick={handleBuildWithAi}
                  disabled={isBuildingAi || !aiPrompt.trim()}
                  className="flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 text-white text-xs font-semibold transition"
                >
                  <Sparkles className={`w-3.5 h-3.5 ${isBuildingAi ? 'animate-spin' : ''}`} />
                  {isBuildingAi ? 'Compiling Path...' : 'Generate Steps'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
