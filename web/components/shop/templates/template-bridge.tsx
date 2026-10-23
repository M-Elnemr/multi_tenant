"use client";

import { useEffect } from "react";
import { isTemplate } from "./ids";

/**
 * "?tpl=bloom" on any shop address previews a template without saving it, and the choice is remembered for this browser tab only, so the owner can
 * click around the whole shop in the new look. Visitors never see this unless they add the parameter themselves.
 */
export function TemplateBridge() {
  useEffect(() => {
    let tpl: string | null = null;
    try {
      const q = new URLSearchParams(window.location.search).get("tpl");
      if (isTemplate(q)) { sessionStorage.setItem("shop-template-preview", q); tpl = q; }
      else tpl = sessionStorage.getItem("shop-template-preview");
    } catch { /* storage blocked: no preview */ }
    if (isTemplate(tpl)) document.querySelector(".shop")?.setAttribute("data-template", tpl);
  });
  return null;
}
