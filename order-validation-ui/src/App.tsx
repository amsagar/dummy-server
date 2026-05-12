import { Route, Routes } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { DashboardPage } from "@/pages/DashboardPage";
import { OrderQueuePage } from "@/pages/OrderQueuePage";
import { LegSequencePage } from "@/pages/LegSequencePage";
import { ServiceabilityPage } from "@/pages/ServiceabilityPage";
import { ContainerAvailabilityPage } from "@/pages/ContainerAvailabilityPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { RunDetailPage } from "@/pages/RunDetailPage";
import { DecisionTablesPage } from "@/pages/DecisionTablesPage";
import { DecisionTableEditorPage } from "@/pages/DecisionTableEditorPage";

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<DashboardPage />} />
        <Route path="/queue" element={<OrderQueuePage />} />
        <Route path="/runs/:instId" element={<RunDetailPage />} />
        <Route path="/leg-sequence" element={<LegSequencePage />} />
        <Route path="/serviceability" element={<ServiceabilityPage />} />
        <Route path="/container-availability" element={<ContainerAvailabilityPage />} />
        <Route path="/decision-tables" element={<DecisionTablesPage />} />
        <Route path="/decision-tables/:name" element={<DecisionTableEditorPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Route>
    </Routes>
  );
}
