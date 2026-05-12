import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatNumber } from "@/lib/utils";

interface Props {
  total: number;
  limit: number;
  offset: number;
  onChange: (offset: number) => void;
  unit?: string;
}

export function Pagination({ total, limit, offset, onChange, unit = "results" }: Props) {
  const page = Math.floor(offset / limit) + 1;
  const totalPages = Math.max(1, Math.ceil(total / limit));
  const start = total === 0 ? 0 : offset + 1;
  const end = Math.min(offset + limit, total);
  return (
    <div className="flex items-center justify-between gap-3 px-5 py-3 border-t border-border">
      <div className="text-xs text-muted-foreground">
        {total === 0
          ? `No ${unit}`
          : `${formatNumber(start)}–${formatNumber(end)} of ${formatNumber(total)} ${unit}`}
      </div>
      <div className="flex items-center gap-1.5">
        <Button
          size="sm"
          variant="outline"
          disabled={offset <= 0}
          onClick={() => onChange(Math.max(0, offset - limit))}
        >
          <ChevronLeft className="size-3.5" />
          Prev
        </Button>
        <span className="text-xs text-muted-foreground px-2">
          Page {page} of {totalPages}
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={end >= total}
          onClick={() => onChange(offset + limit)}
        >
          Next
          <ChevronRight className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}
