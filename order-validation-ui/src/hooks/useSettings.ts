import { useEffect, useState } from "react";
import {
  type DateRange,
  getDateRange,
  getWorkflowId,
  setDateRange as persistDateRange,
  setWorkflowId as persistWorkflowId,
} from "@/lib/settings";

interface Settings {
  workflowId: string | null;
  dateRange: DateRange;
  setWorkflowId: (id: string | null) => void;
  setDateRange: (r: DateRange) => void;
}

export function useSettings(): Settings {
  const [workflowId, setWorkflowIdState] = useState<string | null>(() => getWorkflowId());
  const [dateRange, setDateRangeState] = useState<DateRange>(() => getDateRange());

  useEffect(() => {
    const onChange = () => {
      setWorkflowIdState(getWorkflowId());
      setDateRangeState(getDateRange());
    };
    window.addEventListener("ov:settings-changed", onChange);
    return () => window.removeEventListener("ov:settings-changed", onChange);
  }, []);

  return {
    workflowId,
    dateRange,
    setWorkflowId: (id) => {
      persistWorkflowId(id);
      setWorkflowIdState(id);
    },
    setDateRange: (r) => {
      persistDateRange(r);
      setDateRangeState(r);
    },
  };
}
