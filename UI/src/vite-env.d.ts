/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BPMN_RENDERER?: "legacy" | "rich";
}

declare module "*.svg" {
  const src: string;
  export default src;
}
