import { NavLink } from "react-router-dom";
import {
  LayoutDashboard,
  ListChecks,
  MapPin,
  Boxes,
  Settings as SettingsIcon,
  Route as RouteIcon,
  Table2,
} from "lucide-react";
import { cn } from "@/lib/utils";

const navSections: { title: string; items: NavItem[] }[] = [
  {
    title: "Overview",
    items: [
      { to: "/", label: "Dashboard", icon: LayoutDashboard, end: true },
      { to: "/queue", label: "Order Queue", icon: ListChecks },
    ],
  },
  {
    title: "Validations",
    items: [
      { to: "/leg-sequence", label: "Leg Sequence", icon: RouteIcon },
      { to: "/serviceability", label: "Serviceability", icon: MapPin },
      { to: "/container-availability", label: "Container Avail.", icon: Boxes },
    ],
  },
  {
    title: "Config",
    items: [
      { to: "/decision-tables", label: "Decision Tables", icon: Table2 },
      { to: "/settings", label: "Settings", icon: SettingsIcon },
    ],
  },
];

interface NavItem {
  to: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  end?: boolean;
}

export function Sidebar() {
  return (
    <aside className="w-[220px] shrink-0 bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col">
      <div className="px-5 py-5">
        <div className="text-[15px] font-semibold leading-tight">PODS Validation</div>
        <div className="text-xs text-white/55 mt-0.5">Order Intelligence</div>
      </div>
      <nav className="flex-1 px-2 pb-4 space-y-5">
        {navSections.map((section) => (
          <div key={section.title}>
            <div className="px-3 mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-white/40">
              {section.title}
            </div>
            <div className="space-y-0.5">
              {section.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    cn(
                      "flex items-center gap-2.5 px-3 py-2 rounded-md text-sm transition-colors",
                      isActive
                        ? "bg-sidebar-accent text-white font-medium"
                        : "text-white/70 hover:text-white hover:bg-sidebar-accent/60",
                    )
                  }
                >
                  <item.icon className="size-4" />
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
    </aside>
  );
}
