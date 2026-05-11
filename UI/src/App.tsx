/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import * as React from "react";
import { useState } from "react";
import {
  BrowserRouter as Router,
  Routes,
  Route,
  Navigate,
  NavLink,
  useLocation,
  Outlet
} from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  Bot,
  Cpu,
  Layers,
  Wrench,
  FolderTree,
  Server,
  Workflow,
  History,
  BarChart3,
  CheckCircle2,
  LogOut,
  UserCircle2,
  Table2,
  Key,
} from "lucide-react";
import { Toaster } from "@/components/ui/sonner";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

import ChatPage from "./pages/ChatPage";
import ModelsPage from "./pages/ModelsPage";
import EmbeddingModelsPage from "./pages/EmbeddingModelsPage";
import ToolsPage from "./pages/ToolsPage";
import ToolDomainPage from "./pages/ToolDomainPage";
import SkillsPage from "./pages/SkillsPage";
import McpRegistryPage from "./pages/McpRegistryPage";
import McpServerToolsPage from "./pages/McpServerToolsPage";
import DecisionTablesPage from "./pages/DecisionTablesPage";
import DecisionTableEditorPage from "./pages/DecisionTableEditorPage";
import WorkflowsPage from "./pages/WorkflowsPage";
import ApiKeysPage from "./pages/ApiKeysPage";
import WorkflowDesignerPage from "./pages/WorkflowDesignerPage";
import WorkflowRunsPage from "./pages/WorkflowRunsPage";
import WorkflowRunDetailPage from "./pages/WorkflowRunDetailPage";
import WorkflowApprovalsPage from "./pages/WorkflowApprovalsPage";
import ExecutionsPage from "./pages/ExecutionsPage";
import InsightsPage from "./pages/InsightsPage";
import WorkflowVersionDiffPage from "./pages/WorkflowVersionDiffPage";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import { clearAuthToken, getAuthUser, isAuthenticated } from "./services/api";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function Sidebar() {
  const navItems = [
    { icon: Bot, name: "AI Chat", path: "/chat" },
    { icon: Cpu, name: "Models", path: "/models" },
    { icon: Layers, name: "Embedding Models", path: "/embedding-models" },
    { icon: Wrench, name: "Tools", path: "/tools" },
    { icon: Workflow, name: "Workflows", path: "/workflows" },
    { icon: Key, name: "API Keys", path: "/api-keys" },
    { icon: History, name: "Executions", path: "/executions" },
    { icon: BarChart3, name: "Insights", path: "/insights" },
    { icon: CheckCircle2, name: "Approvals", path: "/workflows/approvals" },
    { icon: Table2, name: "Decision Tables", path: "/decision-tables" },
    { icon: FolderTree, name: "Skills", path: "/skills" },
    { icon: Server, name: "MCP Registry", path: "/mcp-registry" },
  ];

  const [isCollapsed, setIsCollapsed] = useState(false);
  const authUser = React.useMemo(() => getAuthUser(), []);

  const onLogout = () => {
    clearAuthToken();
    window.location.href = "/login";
  };

  return (
    <div
      className={cn(
        "flex-shrink-0 flex flex-col h-screen sticky top-0 transition-all duration-300 z-50",
        isCollapsed ? "w-[68px]" : "w-[210px]"
      )}
      style={{ background: "#0A1628", color: "white" }}
    >
      <div className="px-4 py-3 flex items-center gap-3 border-b border-white/10 h-[60px]">
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              onClick={() => setIsCollapsed(v => !v)}
              className="flex items-center justify-center rounded shrink-0 transition-opacity hover:opacity-80 overflow-hidden bg-white/5 p-0"
              style={{ width: 36, height: 32 }}
            >
              <img src="/favicon.png" alt="PODS" className="h-full w-full object-cover" />
            </button>
          </TooltipTrigger>
          <TooltipContent side="right" className="font-medium">
            {isCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          </TooltipContent>
        </Tooltip>
        {!isCollapsed && (
          <div>
            <div className="font-bold text-white text-sm leading-tight">PODS</div>
            <div className="text-white/50 text-[10px] leading-tight">AI Agent</div>
          </div>
        )}
      </div>

      <nav className="flex-1 py-6 space-y-1 overflow-visible">
        {navItems.map((item) => (
          <div key={item.path} className="px-3">
            <NavLink
              to={item.path}
              className={({ isActive }) => cn(
                "flex items-center rounded-xl text-sm font-medium transition-all duration-200 group relative",
                isCollapsed ? "h-10 w-10 justify-center mx-auto" : "py-2.5 px-4 gap-3",
                isActive
                  ? "text-white bg-white/10"
                  : "text-white/40 hover:text-white hover:bg-white/5"
              )}
            >
              {({ isActive }) => (
                <>
                  <div className={cn(
                    "absolute left-0 top-2 bottom-2 w-1 bg-[#E31837] rounded-r-full transition-all duration-200 z-10 pointer-events-none",
                    isCollapsed ? "-left-[12px]" : "-left-4",
                    isActive ? "opacity-100 scale-y-100" : "opacity-0 scale-y-0"
                  )} />

                  <item.icon size={18} className={cn(
                    "shrink-0 transition-all",
                    isActive ? "text-white" : "group-hover:text-white"
                  )} />
                  {!isCollapsed && <span className="truncate">{item.name}</span>}

                  {isCollapsed && (
                    <div className="absolute left-full ml-3 px-2.5 py-1.5 bg-[#123262] text-white text-xs font-semibold rounded-md shadow-xl opacity-0 translate-x-[-10px] pointer-events-none group-hover:opacity-100 group-hover:translate-x-0 transition-all duration-200 z-[100] whitespace-nowrap">
                      {item.name}
                    </div>
                  )}
                </>
              )}
            </NavLink>
          </div>
        ))}
      </nav>

      <div className="border-t border-white/10 p-3">
        {isCollapsed ? (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                onClick={onLogout}
                className="mx-auto flex h-10 w-10 items-center justify-center rounded-xl bg-white/5 text-white/80 transition hover:bg-white/10 hover:text-white"
              >
                <UserCircle2 size={18} />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right" className="font-medium">
              {authUser?.email || "Signed in user"}
            </TooltipContent>
          </Tooltip>
        ) : (
          <div className="flex items-center justify-between gap-2 rounded-xl bg-white/5 px-2.5 py-2">
            <div className="flex min-w-0 items-center gap-2">
              <UserCircle2 size={18} className="shrink-0 text-white/80" />
              <div className="min-w-0">
                <div className="truncate text-[11px] text-white/90">
                  {authUser?.email || "Signed in user"}
                </div>
                <div className="text-[10px] text-white/50">User</div>
              </div>
            </div>
            <button
              onClick={onLogout}
              className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-white/70 transition hover:bg-white/10 hover:text-white"
              aria-label="Logout"
              title="Logout"
            >
              <LogOut size={14} />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function Header() {
  const location = useLocation();
  const pageTitle: Record<string, string> = {
    "/chat": "AI Chat",
    "/models": "Models",
    "/embedding-models": "Embedding Models",
    "/tools": "Tools",
    "/tools/": "Tools",
    "/workflows": "Workflows",
    "/api-keys": "API Keys",
    "/executions": "Executions",
    "/insights": "Insights",
    "/decision-tables": "Decision Tables",
    "/skills": "Skills",
    "/mcp-registry": "MCP Registry",
    "/mcp-registry/": "MCP Registry",
  };

  const title =
    (location.pathname.startsWith("/tools/") ? "Tools" : undefined) ||
    (location.pathname.startsWith("/workflows/") ? "Workflows" : undefined) ||
    (location.pathname.startsWith("/decision-tables/") ? "Decision Tables" : undefined) ||
    (location.pathname.startsWith("/mcp-registry/") ? "MCP Registry" : undefined) ||
    pageTitle[location.pathname] ||
    "PODS AI Agent";

  return (
    <header className="h-16 border-b bg-white flex items-center justify-between px-6 sticky top-0 z-10 shadow-sm">
      <div className="flex items-center gap-4">
        <h1 className="text-lg font-semibold text-[#123262]">{title}</h1>
      </div>
    </header>
  );
}

function Layout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const isChatRoute = location.pathname === "/chat";
  const isAuthRoute = location.pathname === "/login" || location.pathname === "/signup";

  if (isAuthRoute) {
    return <>{children}</>;
  }

  return (
    <div className="flex min-h-screen font-sans bg-[#f5f5f5]">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0">
        <Header />
        <main className={cn("flex-1 overflow-auto", isChatRoute ? "p-3 lg:p-4" : "p-6")}>
          {children}
        </main>
      </div>
    </div>
  );
}

function RequireAuth() {
  return isAuthenticated() ? <Outlet /> : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <Router>
          <Layout>
            <Routes>
              <Route path="/login" element={<LoginPage />} />
              <Route path="/signup" element={<SignupPage />} />
              <Route path="/" element={<Navigate to="/chat" replace />} />
              <Route element={<RequireAuth />}>
                <Route path="/chat" element={<ChatPage />} />
                <Route path="/models" element={<ModelsPage />} />
                <Route path="/embedding-models" element={<EmbeddingModelsPage />} />
                <Route path="/tools" element={<ToolsPage />} />
                <Route path="/tools/:domainId" element={<ToolDomainPage />} />
                <Route path="/workflows" element={<WorkflowsPage />} />
                <Route path="/api-keys" element={<ApiKeysPage />} />
                <Route path="/workflows/designer" element={<WorkflowDesignerPage />} />
                <Route path="/workflows/:id/designer" element={<WorkflowDesignerPage />} />
                <Route path="/workflows/:id/runs" element={<WorkflowRunsPage />} />
                <Route path="/workflows/runs/:runId" element={<WorkflowRunDetailPage />} />
                <Route path="/workflows/approvals" element={<WorkflowApprovalsPage />} />
                <Route path="/executions" element={<ExecutionsPage />} />
                <Route path="/insights" element={<InsightsPage />} />
                <Route path="/workflows/:id/diff" element={<WorkflowVersionDiffPage />} />
                <Route path="/decision-tables" element={<DecisionTablesPage />} />
                <Route path="/decision-tables/:name" element={<DecisionTableEditorPage />} />
                <Route path="/skills" element={<SkillsPage />} />
                <Route path="/mcp-registry" element={<McpRegistryPage />} />
                <Route path="/mcp-registry/:serverId/tools" element={<McpServerToolsPage />} />
              </Route>
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Routes>
          </Layout>
          <Toaster position="top-right" />
        </Router>
      </TooltipProvider>
    </QueryClientProvider>
  );
}
