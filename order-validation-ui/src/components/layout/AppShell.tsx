import { Outlet } from "react-router-dom";
import { Sidebar } from "./Sidebar";

// `h-screen` (not min-h-screen) is load-bearing: it fixes the outer flex to
// the viewport height so the inner <main className="flex-1 overflow-auto">
// can actually scroll inside its own column. `min-h-0` on the column lets
// children shrink below their intrinsic content height, which is what makes
// the overflow-auto effective. Without these, long pages (e.g. a run detail
// with 30+ foreach activity rows) push the whole document down and the
// sidebar / topbar scroll out of view.
export function AppShell() {
  return (
    <div className="flex h-screen overflow-hidden">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        <Outlet />
      </div>
    </div>
  );
}
