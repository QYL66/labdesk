// Modified for LabDesk.
import * as React from "react";
import { ArrowUpRight, BookOpen, Brain, CalendarDays, Lightbulb, Send, ShieldCheck, Square } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

type PromptPreset = {
  title: string;
  description: string;
  prompt: string;
  icon: React.ComponentType<{ className?: string }>;
};

const KNOWLEDGE_PRESETS: PromptPreset[] = [
  {
    title: "设备操作规程",
    description: "核对显微镜归位步骤",
    prompt: "使用教学显微镜结束后，归位需要贴什么颜色的复位卡？请附出处。",
    icon: BookOpen
  },
  {
    title: "实验室安全须知",
    description: "查看遇到设备异常时的处理流程",
    prompt: "实验过程中发现设备故障时应如何处理？请附出处。",
    icon: ShieldCheck
  },
  {
    title: "设备预约规则",
    description: "查阅开放时间和时段长度",
    prompt: "设备预约可从几点开始？每次可以预约多久？请附出处。",
    icon: CalendarDays
  }
];

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  const applyPreset = React.useCallback(
    (prompt: string) => {
      if (isStreaming) return;
      setValue(prompt);
      focusInput();
    },
    [isStreaming, focusInput]
  );

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <section className="lab-knowledge" aria-labelledby="lab-knowledge-title">
      <div className="lab-knowledge-inner">
        <header className="lab-workbench-hero">
          <div className="lab-hero-copy">
            <h1 id="lab-knowledge-title" className="lab-title">实验室资料问答</h1>
            <p className="lab-lede">查询设备规程、安全须知和预约规则，点开答案中的引用查看原文。</p>
          </div>
          <div className="lab-hero-photo" aria-hidden="true" />
        </header>

        <div className="lab-knowledge-query">
          <div className={cn("lab-query-box", isFocused && "is-focused")}>
            <div className="relative">
              <textarea
                ref={textareaRef}
                value={value}
                onChange={(event) => setValue(event.target.value)}
                placeholder={deepThinkingEnabled ? "输入需要深度分析的问题..." : "输入你的问题..."}
                className="max-h-40 min-h-[52px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 text-[15px] text-[var(--lab-ink)] placeholder:text-[var(--lab-muted)] focus:outline-none sm:text-base"
                rows={1}
                onFocus={() => setIsFocused(true)}
                onBlur={() => setIsFocused(false)}
                onCompositionStart={() => {
                  isComposingRef.current = true;
                }}
                onCompositionEnd={() => {
                  isComposingRef.current = false;
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    const nativeEvent = event.nativeEvent as KeyboardEvent;
                    if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                      return;
                    }
                    event.preventDefault();
                    handleSubmit();
                  }
                }}
                aria-label="发送消息"
              />
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
                disabled={isStreaming}
                aria-pressed={deepThinkingEnabled}
                className={cn(
                  "rounded-full border px-3 py-1.5 text-xs font-medium transition-all",
                  deepThinkingEnabled
                    ? "border-[var(--lab-line)] bg-[var(--lab-wash)] text-[var(--lab-accent)]"
                    : "border-transparent bg-[#F5F5F5] text-[#6B7280] hover:bg-[#EEEEEE]",
                  isStreaming && "cursor-not-allowed opacity-60"
                )}
              >
                <span className="inline-flex items-center gap-2">
                  <Brain className={cn("h-3.5 w-3.5", deepThinkingEnabled && "text-[var(--lab-accent)]")} />
                  深度思考
                  {deepThinkingEnabled ? (
                    <span className="h-2 w-2 rounded-full bg-[var(--lab-accent)] motion-safe:animate-pulse" />
                  ) : null}
                </span>
              </button>
              <button
                type="button"
                onClick={handleSubmit}
                disabled={!hasContent && !isStreaming}
                aria-label={isStreaming ? "停止生成" : "发送消息"}
                className={cn(
                  "ml-auto inline-flex items-center justify-center rounded-full p-2.5 transition-all duration-200",
                  isStreaming
                    ? "bg-[#FEE2E2] text-[#EF4444] hover:bg-[#FECACA]"
                    : hasContent
                      ? "bg-[var(--lab-accent)] text-white hover:bg-[var(--lab-ink)]"
                      : "cursor-not-allowed bg-[#F5F5F5] text-[#CCCCCC]"
                )}
              >
                {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
              </button>
            </div>
          </div>
          {deepThinkingEnabled ? (
            <p className="mt-3 text-xs text-[var(--lab-accent)]">
              <span className="inline-flex items-center gap-1.5">
                <Lightbulb className="h-3.5 w-3.5" />
                深度思考模式已开启，AI将进行更深入的分析推理
              </span>
            </p>
          ) : null}
          <p className="mt-3 text-center text-xs text-[#94A3B8]">
            <kbd className="rounded bg-white/80 px-1.5 py-0.5 text-[#6B7280] shadow-sm">
              Enter
            </kbd>{" "}
            发送
            <span className="px-1.5">·</span>
            <kbd className="rounded bg-white/80 px-1.5 py-0.5 text-[#6B7280] shadow-sm">
              Shift + Enter
            </kbd>{" "}
            换行
            {isStreaming ? <span className="ml-2 animate-pulse-soft">生成中...</span> : null}
          </p>
        </div>

        <section className="lab-example" aria-label="常用问法">
          <h2>常用问法</h2>
          <div className="lab-example-list">
            {KNOWLEDGE_PRESETS.map((preset) => {
              const Icon = preset.icon;
              return (
                <button
                  key={preset.title}
                  type="button"
                  onClick={() => applyPreset(preset.prompt)}
                  disabled={isStreaming}
                  className="lab-example-button lab-example-with-icon"
                >
                  <Icon className="h-[18px] w-[18px]" aria-hidden="true" />
                  <span><strong>{preset.title}</strong><small>{preset.description}</small></span>
                  <ArrowUpRight size={16} aria-hidden="true" />
                </button>
              );
            })}
          </div>
        </section>
      </div>
    </section>
  );
}
