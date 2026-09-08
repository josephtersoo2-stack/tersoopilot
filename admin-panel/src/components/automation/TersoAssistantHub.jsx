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
  Sparkles,
  Clock,
  X,
} from 'lucide-react';

const API_BASE = 'http://localhost:8000/api/automation/assistant';

export default function TersoAssistantHub() {
  const [sessions, setSessions] = useState([]);
  const [activeSession, setActiveSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingSessions, setLoadingSessions] = useState(true);
  const [expandedToolCalls, setExpandedToolCalls] = useState({});
  const messagesEndRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    loadSessions();
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  useEffect(() => {
    if (activeSession && inputRef.current) {
      inputRef.current.focus();
    }
  }, [activeSession]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const loadSessions = async () => {
    setLoadingSessions(true);
    try {
      const res = await axios.get(`${API_BASE}/`);
      setSessions(res.data || []);
    } catch (e) {
      console.error('Failed to load sessions:', e);
    } finally {
      setLoadingSessions(false);
    }
  };

  const createSession = async () => {
    try {
      const res = await axios.post(`${API_BASE}/new-session/`, {
        title: `Chat ${new Date().toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`,
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
      console.error('Failed to load session:', e);
    }
  };

  const deleteSession = async (sessionId, e) => {
    e.stopPropagation();
    try {
      await axios.delete(`${API_BASE}/${sessionId}/`);
      setSessions((prev) => prev.filter((s) => s.id !== sessionId));
      if (activeSession?.id === sessionId) {
        setActiveSession(null);
        setMessages([]);
      }
    } catch (e) {
      console.error('Failed to delete session:', e);
    }
  };

  const sendMessage = async () => {
    if (!input.trim() || !activeSession || sending) return;
    const userText = input.trim();
    setInput('');
    setSending(true);

    // Optimistically add user message
    const tempUserMsg = {
      id: `temp-${Date.now()}`,
      role: 'user',
      content: userText,
      created_at: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, tempUserMsg]);

    try {
      const res = await axios.post(`${API_BASE}/${activeSession.id}/chat/`, {
        message: userText,
      });
      setMessages(res.data.messages || []);
      // Refresh session list to update timestamps
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

  const roleConfig = {
    user: {
      icon: User,
      label: 'You',
      bg: 'bg-blue-500/10',
      border: 'border-blue-500/20',
      iconColor: 'text-blue-400',
      textColor: 'text-neutral-100',
    },
    assistant: {
      icon: Bot,
      label: 'TersoAssistant',
      bg: 'bg-emerald-500/8',
      border: 'border-emerald-500/15',
      iconColor: 'text-emerald-400',
      textColor: 'text-neutral-200',
    },
    tool: {
      icon: Wrench,
      label: 'Tool Result',
      bg: 'bg-amber-500/8',
      border: 'border-amber-500/15',
      iconColor: 'text-amber-400',
      textColor: 'text-neutral-300',
    },
    system: {
      icon: Sparkles,
      label: 'System',
      bg: 'bg-purple-500/8',
      border: 'border-purple-500/15',
      iconColor: 'text-purple-400',
      textColor: 'text-neutral-300',
    },
  };

  const renderMessage = (msg) => {
    const cfg = roleConfig[msg.role] || roleConfig.assistant;
    const Icon = cfg.icon;
    const parsedToolCalls = formatToolCalls(msg.tool_calls);
    const isToolResult = msg.role === 'tool';
    const isExpanded = expandedToolCalls[msg.id];

    let displayContent = msg.content;
    if (isToolResult) {
      try {
        const parsed = JSON.parse(msg.content);
        displayContent = JSON.stringify(parsed, null, 2);
      } catch (_) {
        // Keep as-is
      }
    }

    return (
      <div key={msg.id} className={`group flex gap-3 px-4 py-3 rounded-xl ${cfg.bg} border ${cfg.border} transition-all hover:border-opacity-40`}>
        {/* Avatar */}
        <div className={`w-7 h-7 rounded-lg ${cfg.bg} border ${cfg.border} flex items-center justify-center shrink-0 mt-0.5`}>
          <Icon className={`w-3.5 h-3.5 ${cfg.iconColor}`} />
        </div>

        {/* Body */}
        <div className="flex-1 min-w-0 space-y-1.5">
          {/* Header Row */}
          <div className="flex items-center gap-2">
            <span className={`text-[11px] font-semibold ${cfg.iconColor}`}>{cfg.label}</span>
            {msg.tool_call_id && (
              <span className="text-[9px] font-mono bg-neutral-800 text-neutral-400 px-1.5 py-0.5 rounded">
                call: {msg.tool_call_id.slice(0, 12)}…
              </span>
            )}
            <span className="text-[9px] text-neutral-500 ml-auto">
              {new Date(msg.created_at).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
            </span>
          </div>

          {/* Tool Calls Badge (for assistant messages that requested tools) */}
          {parsedToolCalls && parsedToolCalls.length > 0 && (
            <div className="space-y-1">
              {parsedToolCalls.map((tc, idx) => (
                <button
                  key={idx}
                  onClick={() => toggleToolCall(`${msg.id}-${idx}`)}
                  className="flex items-center gap-2 text-[10px] font-mono bg-indigo-500/10 text-indigo-300 border border-indigo-500/20 px-2 py-1 rounded-lg hover:bg-indigo-500/15 transition cursor-pointer w-full text-left"
                >
                  <Wrench className="w-3 h-3 text-indigo-400" />
                  <span className="font-semibold">{tc.name}</span>
                  <span className="text-neutral-400">({Object.keys(tc.args).length} args)</span>
                  <ChevronDown className={`w-3 h-3 ml-auto transition-transform ${expandedToolCalls[`${msg.id}-${idx}`] ? 'rotate-180' : ''}`} />
                </button>
              ))}
              {parsedToolCalls.map((tc, idx) =>
                expandedToolCalls[`${msg.id}-${idx}`] ? (
                  <pre key={`args-${idx}`} className="text-[10px] font-mono bg-[#0D111A] text-neutral-300 p-2.5 rounded-lg border border-[#1E2638] overflow-x-auto max-h-32 whitespace-pre-wrap">
                    {JSON.stringify(tc.args, null, 2)}
                  </pre>
                ) : null
              )}
            </div>
          )}

          {/* Content */}
          {displayContent && (
            isToolResult ? (
              <div>
                <button
                  onClick={() => toggleToolCall(msg.id)}
                  className="text-[10px] text-amber-400 hover:text-amber-300 flex items-center gap-1 cursor-pointer mb-1"
                >
                  <ChevronDown className={`w-3 h-3 transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
                  {isExpanded ? 'Collapse result' : 'Show tool result'}
                </button>
                {isExpanded && (
                  <pre className="text-[10px] font-mono bg-[#0D111A] text-amber-200/80 p-2.5 rounded-lg border border-amber-500/10 overflow-x-auto max-h-48 whitespace-pre-wrap">
                    {displayContent}
                  </pre>
                )}
              </div>
            ) : (
              <div className={`text-[12px] leading-relaxed ${cfg.textColor} whitespace-pre-wrap break-words`}>
                {displayContent}
              </div>
            )
          )}
        </div>
      </div>
    );
  };

  // ─── Empty State: No Session Selected ────────────────────────
  if (!activeSession) {
    return (
      <div className="space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-bold text-white flex items-center gap-2">
              <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-violet-600 to-fuchsia-500 flex items-center justify-center shadow-lg shadow-violet-500/20">
                <Bot className="w-4.5 h-4.5 text-white" />
              </div>
              TersoAssistant
            </h2>
            <p className="text-xs text-neutral-400 mt-1">
              Conversational fleet copilot with tool-calling AI engine
            </p>
          </div>
          <button
            onClick={createSession}
            className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-violet-600 to-fuchsia-600 hover:from-violet-500 hover:to-fuchsia-500 text-white text-xs font-semibold rounded-xl shadow-lg shadow-violet-600/25 transition cursor-pointer active:scale-[0.98]"
          >
            <Plus className="w-3.5 h-3.5" />
            New Conversation
          </button>
        </div>

        {/* Sessions List */}
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl overflow-hidden">
          <div className="px-5 py-3 border-b border-[#1E2638] flex items-center justify-between">
            <span className="text-xs font-semibold text-neutral-300">Conversation History</span>
            <span className="text-[10px] text-neutral-500 font-mono">{sessions.length} sessions</span>
          </div>

          {loadingSessions ? (
            <div className="flex items-center justify-center py-12 text-neutral-400">
              <Loader2 className="w-5 h-5 animate-spin mr-2" />
              <span className="text-xs">Loading sessions…</span>
            </div>
          ) : sessions.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 text-neutral-400">
              <MessageCircle className="w-10 h-10 text-neutral-600 mb-3" />
              <p className="text-sm font-medium text-neutral-300 mb-1">No conversations yet</p>
              <p className="text-xs text-neutral-500 mb-4">Start a new conversation to manage your fleet with natural language</p>
              <button
                onClick={createSession}
                className="flex items-center gap-2 px-4 py-2 bg-violet-600/20 text-violet-300 border border-violet-500/30 text-xs font-semibold rounded-xl hover:bg-violet-600/30 transition cursor-pointer"
              >
                <Plus className="w-3.5 h-3.5" />
                Start First Conversation
              </button>
            </div>
          ) : (
            <div className="divide-y divide-[#1E2638]">
              {sessions.map((s) => (
                <button
                  key={s.id}
                  onClick={() => selectSession(s)}
                  className="w-full flex items-center justify-between px-5 py-3.5 hover:bg-[#1A1F2E] transition cursor-pointer group text-left"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="w-8 h-8 rounded-lg bg-violet-500/10 border border-violet-500/20 flex items-center justify-center shrink-0">
                      <MessageCircle className="w-3.5 h-3.5 text-violet-400" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-xs font-medium text-neutral-200 truncate">{s.title}</p>
                      <p className="text-[10px] text-neutral-500 flex items-center gap-1 mt-0.5">
                        <Clock className="w-2.5 h-2.5" />
                        {new Date(s.updated_at).toLocaleString()}
                        {s.messages && (
                          <span className="ml-1 text-neutral-500">· {s.messages.length} msgs</span>
                        )}
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={(e) => deleteSession(s.id, e)}
                    className="opacity-0 group-hover:opacity-100 p-1.5 hover:bg-red-500/20 rounded-lg transition cursor-pointer"
                    title="Delete session"
                  >
                    <Trash2 className="w-3.5 h-3.5 text-red-400" />
                  </button>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Quick Actions Hint */}
        <div className="bg-[#111520] border border-[#1E2638] rounded-2xl p-5">
          <p className="text-[11px] font-semibold text-neutral-300 mb-3">💡 Try asking TersoAssistant</p>
          <div className="grid grid-cols-2 gap-2">
            {[
              'What is the fleet status?',
              'List all mature profiles',
              'Create a Gaming niche with seed keywords',
              'Abort job #4 — profiles are stalled',
              'Dispatch a warming campaign for fresh profiles',
              'Show telemetry for the latest job',
            ].map((hint, i) => (
              <div
                key={i}
                className="text-[10px] text-neutral-400 bg-[#0D111A] border border-[#1E2638] rounded-lg px-3 py-2 font-mono"
              >
                "{hint}"
              </div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // ─── Active Chat View ────────────────────────────────────────
  return (
    <div className="flex flex-col h-[calc(100vh-120px)]">
      {/* Chat Header */}
      <div className="flex items-center justify-between px-1 pb-4 border-b border-[#1E2638] shrink-0">
        <div className="flex items-center gap-3">
          <button
            onClick={() => { setActiveSession(null); setMessages([]); loadSessions(); }}
            className="text-neutral-400 hover:text-white transition cursor-pointer p-1"
            title="Back to sessions"
          >
            <X className="w-4 h-4" />
          </button>
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-violet-600 to-fuchsia-500 flex items-center justify-center shadow-lg shadow-violet-500/20">
            <Bot className="w-4 h-4 text-white" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-white">{activeSession.title}</h3>
            <p className="text-[10px] text-neutral-500 font-mono">
              Session {String(activeSession.id).slice(0, 8)}… · {messages.length} messages
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={createSession}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-[#1A1F2E] hover:bg-[#222838] text-neutral-300 text-[10px] font-medium rounded-lg border border-[#1E2638] transition cursor-pointer"
          >
            <Plus className="w-3 h-3" />
            New Chat
          </button>
        </div>
      </div>

      {/* Messages Area */}
      <div className="flex-1 overflow-y-auto py-4 space-y-3 px-1 scrollbar-thin scrollbar-thumb-neutral-700">
        {messages.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-neutral-400">
            <div className="w-16 h-16 rounded-2xl bg-gradient-to-tr from-violet-600/20 to-fuchsia-500/20 border border-violet-500/20 flex items-center justify-center mb-4">
              <Bot className="w-8 h-8 text-violet-400" />
            </div>
            <p className="text-sm font-medium text-neutral-300 mb-1">TersoAssistant is ready</p>
            <p className="text-xs text-neutral-500 text-center max-w-sm">
              Ask about fleet status, dispatch campaigns, create niches, abort jobs, or inspect telemetry — all through natural language.
            </p>
          </div>
        ) : (
          messages.map(renderMessage)
        )}

        {/* Typing indicator */}
        {sending && (
          <div className="flex items-center gap-3 px-4 py-3 rounded-xl bg-emerald-500/5 border border-emerald-500/10">
            <div className="w-7 h-7 rounded-lg bg-emerald-500/10 border border-emerald-500/20 flex items-center justify-center">
              <Loader2 className="w-3.5 h-3.5 text-emerald-400 animate-spin" />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-[11px] text-emerald-400 font-medium">TersoAssistant is thinking</span>
              <div className="flex gap-0.5">
                <span className="w-1 h-1 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '0ms' }} />
                <span className="w-1 h-1 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '150ms' }} />
                <span className="w-1 h-1 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '300ms' }} />
              </div>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input Bar */}
      <div className="shrink-0 pt-3 border-t border-[#1E2638]">
        <div className="flex items-end gap-2 bg-[#111520] border border-[#1E2638] rounded-2xl p-2 focus-within:border-violet-500/40 transition">
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask TersoAssistant anything about your fleet…"
            rows={1}
            className="flex-1 bg-transparent text-sm text-neutral-100 placeholder-neutral-500 resize-none outline-none px-2 py-1.5 max-h-32 scrollbar-thin"
            style={{ minHeight: '36px' }}
            disabled={sending}
          />
          <button
            onClick={sendMessage}
            disabled={!input.trim() || sending}
            className={`p-2.5 rounded-xl transition cursor-pointer shrink-0 ${
              input.trim() && !sending
                ? 'bg-gradient-to-r from-violet-600 to-fuchsia-600 text-white shadow-lg shadow-violet-600/25 hover:from-violet-500 hover:to-fuchsia-500 active:scale-[0.95]'
                : 'bg-[#1A1F2E] text-neutral-500 cursor-not-allowed'
            }`}
          >
            {sending ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Send className="w-4 h-4" />
            )}
          </button>
        </div>
        <p className="text-[9px] text-neutral-500 text-center mt-1.5">
          TersoAssistant can execute fleet tools autonomously. Press Enter to send, Shift+Enter for newline.
        </p>
      </div>
    </div>
  );
}
