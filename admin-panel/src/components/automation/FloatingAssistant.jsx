import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import {
  MessageCircle,
  Send,
  Plus,
  Trash2,
  Loader2,
  Bot,
  User,
  Wrench,
  ChevronDown,
  ChevronUp,
  Sparkles,
  Maximize2,
  Minimize2,
  X,
  CheckCircle2,
  AlertCircle,
  Eye,
  Layers,
  Zap,
  Play,
  Flame,
  FileCheck
} from 'lucide-react';

const API_BASE = 'http://localhost:8000/api/automation/assistant';

export default function FloatingAssistant({
  activeTab = 'PROFILES',
  profiles = [],
  selectedProfileIds = [],
  setSelectedProfileIds,
  runningJobsCount = 0,
  settings = {},
  isOpen,
  setIsOpen,
}) {
  // Positioning and Dragging State
  const [position, setPosition] = useState(() => {
    return {
      x: Math.max(20, (typeof window !== 'undefined' ? window.innerWidth : 1200) - 84),
      y: Math.max(20, (typeof window !== 'undefined' ? window.innerHeight : 800) - 84),
    };
  });
  const [isDragging, setIsDragging] = useState(false);
  const dragRef = useRef({ startX: 0, startY: 0, initialX: 0, initialY: 0, moved: false });

  // Assistant State
  const [isExpanded, setIsExpanded] = useState(false);
  const [showVisionInspector, setShowVisionInspector] = useState(false);
  const [includePageContext, setIncludePageContext] = useState(true);

  const [sessions, setSessions] = useState([]);
  const [activeSession, setActiveSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [expandedToolCalls, setExpandedToolCalls] = useState({});

  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);

  // Keep button within window on resize
  useEffect(() => {
    const handleResize = () => {
      setPosition((prev) => ({
        x: Math.min(prev.x, window.innerWidth - 76),
        y: Math.min(prev.y, window.innerHeight - 76),
      }));
    };
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Load sessions when assistant is opened or initialized
  useEffect(() => {
    loadSessions();
  }, []);

  useEffect(() => {
    if (isOpen && messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, isOpen]);

  useEffect(() => {
    if (isOpen && inputRef.current) {
      setTimeout(() => inputRef.current?.focus(), 150);
    }
  }, [isOpen]);

  const loadSessions = async () => {
    setLoadingSessions(true);
    try {
      const res = await axios.get(`${API_BASE}/`);
      const list = res.data || [];
      setSessions(list);
      if (list.length > 0 && !activeSession) {
        selectSession(list[0]);
      } else if (list.length === 0) {
        createSession();
      }
    } catch (e) {
      console.error('Failed to load assistant sessions:', e);
    } finally {
      setLoadingSessions(false);
    }
  };

  const createSession = async () => {
    try {
      const res = await axios.post(`${API_BASE}/new-session/`, {
        title: `Copilot ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`,
      });
      setSessions((prev) => [res.data, ...prev]);
      setActiveSession(res.data);
      setMessages([]);
    } catch (e) {
      console.error('Failed to create session:', e);
    }
  };

  const selectSession = async (session) => {
    try {
      const res = await axios.get(`${API_BASE}/${session.id}/`);
      setActiveSession(res.data);
      setMessages(res.data.messages || []);
    } catch (e) {
      console.error('Failed to load session details:', e);
    }
  };

  const deleteSession = async (sessionId, e) => {
    e.stopPropagation();
    try {
      await axios.delete(`${API_BASE}/${sessionId}/`);
      const updated = sessions.filter((s) => s.id !== sessionId);
      setSessions(updated);
      if (activeSession?.id === sessionId) {
        if (updated.length > 0) {
          selectSession(updated[0]);
        } else {
          setActiveSession(null);
          setMessages([]);
        }
      }
    } catch (e) {
      console.error('Failed to delete session:', e);
    }
  };

  // Compile Live Viewport Context
  const selectedProfiles = profiles.filter((p) => selectedProfileIds.includes(p.id));

  const getPageTitle = (tab) => {
    switch (tab) {
      case 'PROFILES':
        return 'Profiles & Personas Hub';
      case 'EXECUTION':
        return 'GhostPilot Execution Console';
      case 'NICHES':
        return 'Target Niches & Audiences';
      case 'AI_CONFIG':
        return 'AI Model & Prompt Studio';
      case 'HARDWARE':
        return 'Hardware Blueprint & Specs Engine';
      case 'SETTINGS':
        return 'Fleet Runtime Constraints';
      default:
        return `${tab} View`;
    }
  };

  const buildPageContext = () => {
    return {
      active_tab: activeTab,
      page_name: getPageTitle(activeTab),
      summary: `${profiles.length} total profiles loaded in fleet, ${selectedProfiles.length} currently selected on screen. ${runningJobsCount} active running DAG jobs. Active AI: ${settings.selected_ai_model || 'deepseek/deepseek-chat'}.`,
      selected_items: selectedProfiles.map((p) => ({
        id: p.id,
        name: p.name,
        type: 'profile_device',
        details: `${p.brand} ${p.model_name} (Code: ${p.model_code || 'N/A'}, Cookies: ${p.cookie_count || 0}, Proxy: ${p.proxy_type || 'DIRECT'})`,
      })),
      fleet_overview: {
        total_profiles: profiles.length,
        selected_count: selectedProfiles.length,
        running_jobs: runningJobsCount,
      },
    };
  };

  const sendMessage = async (overridePrompt = null) => {
    const textToSend = (overridePrompt || input).trim();
    if (!textToSend || sending) return;

    if (!activeSession) {
      await createSession();
    }

    setInput('');
    setSending(true);

    const tempMsg = {
      id: `temp-${Date.now()}`,
      role: 'user',
      content: textToSend,
      created_at: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, tempMsg]);

    try {
      const payload = {
        message: textToSend,
        page_context: includePageContext ? buildPageContext() : null,
      };

      const res = await axios.post(`${API_BASE}/${activeSession.id}/chat/`, payload);
      setMessages(res.data.messages || []);
      loadSessions();
    } catch (e) {
      const errMsg = {
        id: `err-${Date.now()}`,
        role: 'assistant',
        content: `⚠️ Error: ${e.response?.data?.error || e.message || 'Failed to reach TersoAssistant engine.'}`,
        created_at: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, errMsg]);
    } finally {
      setSending(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  // Dragging Handlers for the Floating Action Button
  const handlePointerDown = (e) => {
    dragRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      initialX: position.x,
      initialY: position.y,
      moved: false,
    };
    setIsDragging(true);

    const onPointerMove = (moveEv) => {
      const dx = moveEv.clientX - dragRef.current.startX;
      const dy = moveEv.clientY - dragRef.current.startY;
      if (Math.hypot(dx, dy) > 4) {
        dragRef.current.moved = true;
      }
      const newX = Math.min(Math.max(16, dragRef.current.initialX + dx), window.innerWidth - 76);
      const newY = Math.min(Math.max(16, dragRef.current.initialY + dy), window.innerHeight - 76);
      setPosition({ x: newX, y: newY });
    };

    const onPointerUp = () => {
      setIsDragging(false);
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);

      // If user did not drag, treat as click to toggle window
      if (!dragRef.current.moved) {
        setIsOpen((prev) => !prev);
      }
    };

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
  };

  const toggleToolCall = (msgId) => {
    setExpandedToolCalls((prev) => ({ ...prev, [msgId]: !prev[msgId] }));
  };

  const formatToolCalls = (toolCalls) => {
    if (!toolCalls || !Array.isArray(toolCalls)) return null;
    return toolCalls.map((tc) => {
      const fn = tc.function || {};
      let args = {};
      try {
        args = typeof fn.arguments === 'string' ? JSON.parse(fn.arguments) : fn.arguments || {};
      } catch (_) {
        args = fn.arguments;
      }
      return { name: fn.name, args, id: tc.id };
    });
  };

  return (
    <>
      {/* 1. Floating Draggable Action Button (FAB) */}
      <div
        style={{
          position: 'fixed',
          left: `${position.x}px`,
          top: `${position.y}px`,
          zIndex: 60,
          touchAction: 'none',
        }}
        onPointerDown={handlePointerDown}
        className={`select-none group cursor-grab active:cursor-grabbing transition-transform ${
          isDragging ? 'scale-110' : 'hover:scale-105'
        }`}
        title="TersoAssistant Copilot (Drag anywhere or click to open)"
      >
        <div className="relative">
          {/* Glowing Animated Ring */}
          <div className="absolute -inset-1.5 bg-gradient-to-r from-blue-600 via-indigo-500 to-purple-600 rounded-full blur-md opacity-70 group-hover:opacity-100 animate-pulse transition duration-500" />

          {/* Core Orb Button */}
          <button
            type="button"
            className="relative w-14 h-14 rounded-full bg-[#0D111A] border-2 border-blue-500/50 flex items-center justify-center text-white shadow-2xl overflow-hidden focus:outline-none"
          >
            <div className="absolute inset-0 bg-gradient-to-tr from-blue-600/30 via-indigo-600/20 to-purple-600/30" />
            <Sparkles className="w-6 h-6 text-blue-400 group-hover:rotate-12 transition-transform duration-300" />

            {/* Selected Count Indicator Badge */}
            {selectedProfileIds.length > 0 && (
              <span className="absolute -top-1 -right-1 bg-gradient-to-r from-blue-500 to-indigo-600 text-white text-[10px] font-mono font-bold w-5 h-5 rounded-full flex items-center justify-center border-2 border-[#0A0D14] shadow-md animate-bounce">
                {selectedProfileIds.length}
              </span>
            )}
          </button>
        </div>
      </div>

      {/* 2. Floating Context-Aware Assistant Window */}
      {isOpen && (
        <div
          className={`fixed z-50 transition-all duration-200 flex flex-col bg-[#0D111A]/95 backdrop-blur-xl border border-[#1E2638] rounded-2xl shadow-2xl overflow-hidden ${
            isExpanded
              ? 'w-[720px] h-[750px] max-w-[95vw] max-h-[92vh] right-6 bottom-6'
              : 'w-[450px] h-[620px] max-w-[95vw] max-h-[85vh] right-6 bottom-20'
          }`}
          style={{
            boxShadow: '0 25px 60px -15px rgba(0, 0, 0, 0.8), 0 0 40px -10px rgba(59, 130, 246, 0.25)',
          }}
        >
          {/* Header */}
          <div className="p-3.5 px-4 bg-[#111522] border-b border-[#1E2638] flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-blue-600 via-indigo-600 to-purple-600 flex items-center justify-center shadow-md">
                <Bot className="w-4 h-4 text-white" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-xs font-bold text-white tracking-wide">TersoAssistant Copilot</h3>
                  <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                </div>
                <p className="text-[10px] text-neutral-400 font-mono">
                  {settings.selected_ai_model ? settings.selected_ai_model.split('/')[1] || settings.selected_ai_model : 'Agentic Engine'}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-1">
              {/* Session Selector */}
              {sessions.length > 1 && (
                <select
                  value={activeSession?.id || ''}
                  onChange={(e) => {
                    const sel = sessions.find((s) => s.id === e.target.value);
                    if (sel) selectSession(sel);
                  }}
                  className="bg-[#181E2E] border border-[#1E2638] text-[10px] text-neutral-300 rounded-lg px-2 py-1 focus:outline-none max-w-[120px] truncate"
                >
                  {sessions.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.title}
                    </option>
                  ))}
                </select>
              )}

              {/* New Session Button */}
              <button
                onClick={createSession}
                title="New Chat Session"
                className="p-1.5 rounded-lg bg-[#181E2E] hover:bg-[#232A3E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer"
              >
                <Plus className="w-3.5 h-3.5" />
              </button>

              {/* Expand/Collapse Window */}
              <button
                onClick={() => setIsExpanded(!isExpanded)}
                title={isExpanded ? 'Normal Size' : 'Expand View'}
                className="p-1.5 rounded-lg bg-[#181E2E] hover:bg-[#232A3E] text-neutral-400 hover:text-white border border-[#1E2638] transition cursor-pointer"
              >
                {isExpanded ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
              </button>

              {/* Close Button */}
              <button
                onClick={() => setIsOpen(false)}
                title="Close Assistant"
                className="p-1.5 rounded-lg bg-[#181E2E] hover:bg-rose-500/20 text-neutral-400 hover:text-rose-400 border border-[#1E2638] transition cursor-pointer"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          {/* Live Page Context Banner (AI Vision Strip) */}
          <div className="bg-[#0B0E17] border-b border-[#1E2638] px-4 py-2 text-[11px]">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-blue-500" />
                <span className="text-neutral-400">Page:</span>
                <span className="font-semibold text-white truncate max-w-[170px]">{getPageTitle(activeTab)}</span>
                {selectedProfiles.length > 0 && (
                  <span className="bg-blue-500/20 text-blue-300 border border-blue-500/30 px-1.5 py-0.2 rounded text-[10px] font-mono font-bold">
                    {selectedProfiles.length} Selected
                  </span>
                )}
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => setShowVisionInspector(!showVisionInspector)}
                  className="flex items-center gap-1 text-[10px] text-neutral-400 hover:text-cyan-400 transition cursor-pointer"
                >
                  <Eye className="w-3 h-3" />
                  <span>{showVisionInspector ? 'Hide Vision' : 'AI Vision'}</span>
                </button>
              </div>
            </div>

            {/* Vision Inspector Drawer */}
            {showVisionInspector && (
              <div className="mt-2 p-2.5 rounded-xl bg-[#111522] border border-[#1E2638] space-y-1.5 text-[10px] font-mono">
                <div className="text-neutral-400 flex items-center justify-between">
                  <span>LIVE CONTEXT ACCESSIBLE BY AI:</span>
                  <span className="text-cyan-400">{includePageContext ? 'ACTIVE IN PROMPT' : 'MUTED'}</span>
                </div>
                <div className="text-neutral-300 max-h-28 overflow-y-auto space-y-1">
                  <div>Tab: {activeTab}</div>
                  <div>Loaded Profiles: {profiles.length}</div>
                  <div>Selected ({selectedProfiles.length}):</div>
                  {selectedProfiles.length === 0 ? (
                    <div className="text-neutral-500 italic">No files/profiles selected on this page. Check profiles in the list to target them!</div>
                  ) : (
                    selectedProfiles.map((p) => (
                      <div key={p.id} className="text-cyan-300 pl-2">
                        • {p.name} [{p.brand} {p.model_name}] (ID: {p.id.slice(0, 8)}...)
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}

            {/* Contextual Quick Action Chips */}
            <div className="flex items-center gap-1.5 mt-2 overflow-x-auto pb-0.5 no-scrollbar">
              {selectedProfiles.length > 0 ? (
                <>
                  <button
                    onClick={() => sendMessage(`Execute warming task on the ${selectedProfiles.length} selected profiles.`)}
                    disabled={sending}
                    className="shrink-0 bg-blue-600/20 hover:bg-blue-600/35 text-blue-300 border border-blue-500/30 text-[10px] px-2.5 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <Flame className="w-3 h-3 text-amber-400" />
                    <span>Warm Up Selected ({selectedProfiles.length})</span>
                  </button>

                  <button
                    onClick={() => sendMessage(`Dispatch YouTube video viewing task to the ${selectedProfiles.length} selected profiles.`)}
                    disabled={sending}
                    className="shrink-0 bg-purple-600/20 hover:bg-purple-600/35 text-purple-300 border border-purple-500/30 text-[10px] px-2.5 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <Play className="w-3 h-3 text-purple-400" />
                    <span>Run YouTube ({selectedProfiles.length})</span>
                  </button>

                  <button
                    onClick={() => sendMessage(`Show me detailed trust score and cookie metrics for the selected profiles.`)}
                    disabled={sending}
                    className="shrink-0 bg-cyan-600/20 hover:bg-cyan-600/35 text-cyan-300 border border-cyan-500/30 text-[10px] px-2.5 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <FileCheck className="w-3 h-3 text-cyan-400" />
                    <span>Audit Cookies</span>
                  </button>
                </>
              ) : (
                <>
                  <button
                    onClick={() => sendMessage('Give me a high-level fleet status summary.')}
                    disabled={sending}
                    className="shrink-0 bg-[#181E2E] hover:bg-[#232A3E] text-neutral-300 border border-[#1E2638] text-[10px] px-2 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <Zap className="w-3 h-3 text-blue-400" />
                    <span>Fleet Health</span>
                  </button>

                  <button
                    onClick={() => sendMessage('List all profiles that are mature or ready for campaigns.')}
                    disabled={sending}
                    className="shrink-0 bg-[#181E2E] hover:bg-[#232A3E] text-neutral-300 border border-[#1E2638] text-[10px] px-2 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <Layers className="w-3 h-3 text-emerald-400" />
                    <span>List Mature Profiles</span>
                  </button>

                  <button
                    onClick={() => sendMessage('Check if any active jobs are stalled or failing and abort them.')}
                    disabled={sending}
                    className="shrink-0 bg-rose-600/10 hover:bg-rose-600/20 text-rose-300 border border-rose-500/30 text-[10px] px-2 py-1 rounded-lg flex items-center gap-1 transition cursor-pointer"
                  >
                    <AlertCircle className="w-3 h-3 text-rose-400" />
                    <span>Halt Stuck Jobs</span>
                  </button>
                </>
              )}
            </div>
          </div>

          {/* Messages Feed */}
          <div className="flex-1 overflow-y-auto p-4 space-y-3">
            {messages.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center p-6 text-neutral-400 space-y-3">
                <div className="w-12 h-12 rounded-2xl bg-blue-500/10 border border-blue-500/20 flex items-center justify-center text-blue-400">
                  <Sparkles className="w-6 h-6" />
                </div>
                <div>
                  <p className="text-sm font-semibold text-white">How can I assist your fleet?</p>
                  <p className="text-xs text-neutral-500 mt-1 max-w-[280px]">
                    I am aware of what you are viewing on screen. Select profiles or files in the dashboard and tell me what task to execute!
                  </p>
                </div>
              </div>
            ) : (
              messages.map((msg) => {
                const isUser = msg.role === 'user';
                const isTool = msg.role === 'tool';
                const parsedToolCalls = formatToolCalls(msg.tool_calls);
                const isExpanded = expandedToolCalls[msg.id];

                return (
                  <div
                    key={msg.id}
                    className={`flex gap-2.5 ${isUser ? 'justify-end' : 'justify-start'}`}
                  >
                    {!isUser && (
                      <div
                        className={`w-6 h-6 rounded-lg flex items-center justify-center shrink-0 mt-0.5 ${
                          isTool ? 'bg-amber-500/15 text-amber-400 border border-amber-500/30' : 'bg-blue-600/20 text-blue-400 border border-blue-500/30'
                        }`}
                      >
                        {isTool ? <Wrench className="w-3 h-3" /> : <Bot className="w-3.5 h-3.5" />}
                      </div>
                    )}

                    <div
                      className={`max-w-[85%] rounded-2xl px-3.5 py-2.5 text-xs shadow-md space-y-1.5 ${
                        isUser
                          ? 'bg-blue-600 text-white rounded-tr-none'
                          : isTool
                          ? 'bg-[#141926] text-amber-200/90 border border-amber-500/20 font-mono rounded-tl-none'
                          : 'bg-[#141926] text-neutral-200 border border-[#1E2638] rounded-tl-none'
                      }`}
                    >
                      {/* Tool Call Invocation Block */}
                      {parsedToolCalls && parsedToolCalls.length > 0 && (
                        <div className="space-y-1.5 pb-1">
                          {parsedToolCalls.map((tc, i) => (
                            <div key={i} className="bg-[#0B0E17] border border-blue-500/30 rounded-xl p-2 text-[11px]">
                              <button
                                onClick={() => toggleToolCall(msg.id)}
                                className="w-full flex items-center justify-between text-blue-400 font-mono font-semibold"
                              >
                                <div className="flex items-center gap-1.5">
                                  <Wrench className="w-3 h-3 text-cyan-400" />
                                  <span>execute {tc.name}()</span>
                                </div>
                                {isExpanded ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                              </button>

                              {isExpanded && (
                                <pre className="mt-2 p-1.5 bg-[#070A10] rounded text-[10px] text-neutral-300 overflow-x-auto whitespace-pre-wrap">
                                  {JSON.stringify(tc.args, null, 2)}
                                </pre>
                              )}
                            </div>
                          ))}
                        </div>
                      )}

                      {/* Message Content */}
                      {msg.content && (
                        <div className="whitespace-pre-wrap leading-relaxed">
                          {msg.content}
                        </div>
                      )}
                    </div>

                    {isUser && (
                      <div className="w-6 h-6 rounded-lg bg-blue-600 text-white flex items-center justify-center shrink-0 mt-0.5 shadow-sm">
                        <User className="w-3.5 h-3.5" />
                      </div>
                    )}
                  </div>
                );
              })
            )}

            {sending && (
              <div className="flex items-center gap-2 text-neutral-400 text-xs pl-8">
                <Loader2 className="w-3.5 h-3.5 animate-spin text-blue-400" />
                <span className="font-mono text-[11px]">TersoAssistant is reasoning & executing tools...</span>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Footer Input */}
          <div className="p-3 bg-[#111522] border-t border-[#1E2638] space-y-2">
            <div className="flex items-center justify-between text-[10px] text-neutral-400 px-1">
              <label className="flex items-center gap-1.5 cursor-pointer hover:text-white transition">
                <input
                  type="checkbox"
                  checked={includePageContext}
                  onChange={(e) => setIncludePageContext(e.target.checked)}
                  className="rounded bg-[#0A0D14] border-[#1E2638] text-blue-600 accent-blue-600"
                />
                <span>Include page viewport & selected items</span>
              </label>

              {activeSession && (
                <button
                  onClick={(e) => deleteSession(activeSession.id, e)}
                  title="Clear conversation"
                  className="hover:text-rose-400 transition cursor-pointer flex items-center gap-1"
                >
                  <Trash2 className="w-3 h-3" />
                  <span>Clear</span>
                </button>
              )}
            </div>

            <div className="flex items-end gap-2 bg-[#0B0E17] border border-[#1E2638] focus-within:border-blue-500 rounded-xl p-2 transition">
              <textarea
                ref={inputRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={
                  selectedProfiles.length > 0
                    ? `Execute task on ${selectedProfiles.length} selected profiles...`
                    : `Ask assistant or execute actions on ${getPageTitle(activeTab)}...`
                }
                rows={1}
                className="flex-1 bg-transparent text-xs text-white placeholder:text-neutral-500 focus:outline-none resize-none max-h-24 py-1"
              />

              <button
                onClick={() => sendMessage()}
                disabled={!input.trim() || sending}
                className={`p-2 rounded-lg flex items-center justify-center transition cursor-pointer ${
                  input.trim() && !sending
                    ? 'bg-blue-600 hover:bg-blue-500 text-white shadow-md'
                    : 'bg-[#181E2E] text-neutral-500 cursor-not-allowed'
                }`}
              >
                {sending ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
