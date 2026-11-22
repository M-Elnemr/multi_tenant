import type { SVGProps } from "react";

const base = (p: SVGProps<SVGSVGElement>) => ({ viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true, ...p });

export const IArrow = (p: SVGProps<SVGSVGElement>) => <svg {...base(p)}><path d="M4 12h16M14 6l6 6-6 6" /></svg>;
