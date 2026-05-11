// Dispatch a single PluginPropertyDescriptor to its field component. Shared
// between FormRenderer (top level) and CollectionField (nested rows).

import type { ReactNode } from "react";
import { BooleanField } from "./BooleanField";
import { CodeField } from "./CodeField";
import { CollectionField } from "./CollectionField";
import { JsonField } from "./JsonField";
import { NoticeField } from "./NoticeField";
import { NumberField } from "./NumberField";
import { OptionsField } from "./OptionsField";
import { StringField } from "./StringField";
import type { PluginPropertyDescriptor } from "@/types/workflow";

export interface RenderFieldArgs {
  prop: PluginPropertyDescriptor;
  value: unknown;
  onChange: (value: unknown) => void;
  /** Sibling form values, used by CodeField (language follows sibling) and
   *  could be used by other fields that need cross-field context. */
  siblingValues?: Record<string, unknown>;
}

export function renderFieldByType(args: RenderFieldArgs): ReactNode {
  const { prop } = args;
  switch (prop.type) {
    case "STRING":
    case "DATETIME": // until we ship a dedicated date picker
      return <StringField {...args} />;
    case "NUMBER":
      return <NumberField {...args} />;
    case "BOOLEAN":
      return <BooleanField {...args} />;
    case "OPTIONS":
      return <OptionsField {...args} />;
    case "MULTI_OPTIONS":
      // TODO(phase-7): dedicated multi-select. For now treat as JSON to avoid losing data.
      return <JsonField {...args} />;
    case "JSON":
      return <JsonField {...args} />;
    case "CODE":
      return <CodeField {...args} />;
    case "COLLECTION":
    case "FIXED_COLLECTION":
      return <CollectionField {...args} />;
    case "NOTICE":
      return <NoticeField prop={prop} />;
    case "CREDENTIALS":
      // TODO(phase-6): real credential picker.
      return <StringField {...args} />;
    default:
      return <StringField {...args} />;
  }
}
