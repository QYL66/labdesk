// Modified for LabDesk.
import * as React from "react";
import { BookOpen, FlaskConical, Menu } from "lucide-react";
import { Link } from "react-router-dom";

import { AgentRawLog } from "@/components/agent/AgentRawLog";
import { AgentSidebar } from "@/components/agent/AgentSidebar";
import { getAgentMeta } from "@/services/agentService";
import type { AgentEngineMeta } from "@/types/agent";

export type AgentMetaState =
  | { status: "probing" }
  | { status: "online"; meta: AgentEngineMeta }
  | { status: "offline"; message: string };

// 进页拉一次 /agent/v1/meta 点亮徽标与框架信息块
function useAgentMeta(): AgentMetaState {
  const [state, setState] = React.useState<AgentMetaState>({ status: "probing" });

  React.useEffect(() => {
    let alive = true;
    getAgentMeta()
      .then((meta) => {
        if (alive) setState({ status: "online", meta });
      })
      .catch((error) => {
        if (alive) {
          setState({ status: "offline", message: (error as Error).message || "连接失败" });
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  return state;
}

interface AgentHeaderProps {
  meta: AgentMetaState;
  rawOpen: boolean;
  onToggleRaw: () => void;
  railOpen: boolean;
  onToggleRail: () => void;
}

function AgentHeader({ meta, rawOpen, onToggleRaw, railOpen, onToggleRail }: AgentHeaderProps) {
  const badgeName =
    meta.status === "online" ? meta.meta.framework : meta.status === "probing" ? "探测中" : "离线";

  return (
    <header className="agent-header">
      <div className="agent-brand">
        <button
          type="button"
          className="agent-head-btn lab-rail-toggle"
          aria-label="会话侧栏"
          aria-expanded={railOpen}
          aria-controls="lab-workbench-sidebar"
          onClick={onToggleRail}
        >
          <Menu size={20} aria-hidden="true" />
        </button>
        <FlaskConical size={18} className="lab-brand-icon" aria-hidden="true" />
        <span className="agent-wordmark">LabDesk</span>
        <span className="agent-brand-sep">/</span>
        <span className="agent-brand-tag">实验室助手</span>
      </div>

      <div className="agent-header-center">
        <span className="agent-badge">
          <span className="agent-dot" data-status={meta.status} aria-hidden="true" />
          <span className="agent-badge-name">{badgeName}</span>
        </span>
        {meta.status === "online" ? (
          <span className="agent-badge-model">{meta.meta.model || "未配模型"}</span>
        ) : null}
        {meta.status === "offline" ? (
          <span className="agent-badge-err" title={meta.message}>
            连接失败
          </span>
        ) : null}
      </div>

      <div className="agent-header-right">
        <Link to="/knowledge" className="agent-head-btn lab-knowledge-link">
          <BookOpen size={15} aria-hidden="true" /> 资料问答
        </Link>
        <button
          type="button"
          className="agent-head-btn"
          data-on={rawOpen}
          onClick={onToggleRaw}
          aria-pressed={rawOpen}
        >
          <span className="agent-btn-glyph">{"{ }"}</span> 原始帧
        </button>
      </div>
    </header>
  );
}

interface AgentLayoutProps {
  children: React.ReactNode;
}

export function AgentLayout({ children }: AgentLayoutProps) {
  const [rawOpen, setRawOpen] = React.useState(false);
  const [railOpen, setRailOpen] = React.useState(false);
  const meta = useAgentMeta();

  return (
    <div className="agent-app" data-rail-open={railOpen}>
      <AgentHeader meta={meta} rawOpen={rawOpen} onToggleRaw={() => setRawOpen((v) => !v)}
        railOpen={railOpen} onToggleRail={() => setRailOpen((open) => !open)} />
      <div className="agent-body">
        <AgentSidebar onNavigate={() => setRailOpen(false)} />
        <main className="agent-main">{children}</main>
        {rawOpen ? <AgentRawLog onClose={() => setRawOpen(false)} /> : null}
      </div>
    </div>
  );
}
