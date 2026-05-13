import * as React from "react";
import { useState } from "react";
import {
  BrowserRouter as Router,
  Routes,
  Route,
  Navigate,
  NavLink,
} from "react-router-dom";
import {
  LayoutDashboard,
  BarChart3,
  Users,
  TrendingUp,
  FileText,
  Bot,
  ChevronLeft,
  ChevronRight,
  PieChart,
  Settings,
} from "lucide-react";
import { Toaster } from "sonner";
import { cn } from "@/lib/utils";

import ExecutiveDashboardPage from "./pages/ExecutiveDashboardPage";
import CategoryAnalyticsPage from "./pages/CategoryAnalyticsPage";
import VendorPerformancePage from "./pages/VendorPerformancePage";
import SavingsOpportunitiesPage from "./pages/SavingsOpportunitiesPage";
import ContractsCompliancePage from "./pages/ContractsCompliancePage";
import ChatPage from "./pages/ChatPage";
import ConfigPage from "./pages/ConfigPage";

const NAV_ITEMS = [
  { icon: LayoutDashboard, label: "Executive Dashboard", path: "/dashboard" },
  { icon: PieChart,        label: "Category Analytics",  path: "/categories" },
  { icon: Users,           label: "Vendor Performance",  path: "/vendors" },
  { icon: TrendingUp,      label: "Savings Opportunities", path: "/savings" },
  { icon: FileText,        label: "Contracts & Compliance", path: "/contracts" },
  { icon: Bot,             label: "AI Assistant",        path: "/chat" },
  { icon: Settings,        label: "Settings",            path: "/config" },
];

function Sidebar() {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div
      className={cn(
        "flex-shrink-0 flex flex-col h-screen sticky top-0 transition-all duration-300 z-50",
        collapsed ? "w-[64px]" : "w-[220px]"
      )}
      style={{ background: "#0A1628", color: "white" }}
    >
      {/* Logo */}
      <div className="flex items-center gap-3 px-4 py-4 border-b border-white/10">
        <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0"
          style={{ background: "#005CB9" }}>
          <PieChart size={16} className="text-white" />
        </div>
        {!collapsed && (
          <div className="min-w-0">
            <div className="text-xs font-semibold text-white leading-tight truncate">Cost Optimization</div>
            <div className="text-[10px] text-white/50 truncate">Portal</div>
          </div>
        )}
      </div>

      {/* Nav */}
      <nav className="flex-1 py-3 overflow-y-auto">
        {NAV_ITEMS.map(({ icon: Icon, label, path }) => (
          <NavLink
            key={path}
            to={path}
            className={({ isActive }) =>
              cn(
                "flex items-center gap-3 px-4 py-2.5 mx-2 rounded-lg text-sm transition-colors",
                isActive
                  ? "bg-[#005CB9] text-white font-medium"
                  : "text-white/60 hover:text-white hover:bg-white/10"
              )
            }
            title={collapsed ? label : undefined}
          >
            <Icon size={18} className="flex-shrink-0" />
            {!collapsed && <span className="truncate">{label}</span>}
          </NavLink>
        ))}
      </nav>

      {/* Collapse toggle */}
      <button
        onClick={() => setCollapsed(c => !c)}
        className="flex items-center justify-center h-10 border-t border-white/10 text-white/40 hover:text-white transition-colors"
      >
        {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
      </button>
    </div>
  );
}

export default function App() {
  return (
    <Router>
      <div className="flex h-screen overflow-hidden bg-[#f5f5f5]">
        <Sidebar />
        <div className="flex-1 overflow-y-auto">
          <Routes>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<ExecutiveDashboardPage />} />
            <Route path="/categories" element={<CategoryAnalyticsPage />} />
            <Route path="/vendors" element={<VendorPerformancePage />} />
            <Route path="/savings" element={<SavingsOpportunitiesPage />} />
            <Route path="/contracts" element={<ContractsCompliancePage />} />
            <Route path="/chat" element={<ChatPage />} />
            <Route path="/config" element={<ConfigPage />} />
          </Routes>
        </div>
      </div>
      <Toaster position="top-right" />
    </Router>
  );
}
