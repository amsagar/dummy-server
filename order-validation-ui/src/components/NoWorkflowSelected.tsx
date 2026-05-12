import { Link } from "react-router-dom";
import { Settings as SettingsIcon } from "lucide-react";

export function NoWorkflowSelected() {
  return (
    <div className="flex flex-col items-center justify-center text-center py-20 text-muted-foreground">
      <div className="size-12 rounded-full bg-muted flex items-center justify-center mb-4">
        <SettingsIcon className="size-5" />
      </div>
      <div className="text-base font-medium text-foreground">No workflow selected</div>
      <div className="text-sm mt-1 max-w-sm">
        Pick the order-validation workflow whose runs should feed this dashboard.
      </div>
      <Link
        to="/settings"
        className="mt-4 inline-flex items-center justify-center h-9 px-4 rounded-md border border-border bg-muted text-foreground text-sm font-medium hover:bg-accent transition-colors"
      >
        Go to Settings
      </Link>
    </div>
  );
}
