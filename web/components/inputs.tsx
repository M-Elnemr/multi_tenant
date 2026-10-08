"use client";

import type { InputHTMLAttributes } from "react";
import { Input } from "@/components/ui";

/** ٠-٩ and ۰-۹ become 0-9, so a number typed on an Arabic keyboard is stored the same as an English one. */
export function latinDigits(s: string) {
  return s.replace(/[٠-٩]/g, (c) => String(c.charCodeAt(0) - 0x660)).replace(/[۰-۹]/g, (c) => String(c.charCodeAt(0) - 0x6f0));
}
/** Phone: English digits only (and a leading +). Spaces, dashes and Arabic letters are dropped as you type. */
export function cleanPhone(s: string) {
  const d = latinDigits(s).replace(/[^0-9+]/g, "");
  return (d.startsWith("+") ? "+" : "") + d.replace(/\+/g, "");
}
/** Email: English letters, digits and the usual symbols only. Anything else (Arabic letters, spaces) is dropped. */
export function cleanEmail(s: string) {
  return latinDigits(s).replace(/[^\x21-\x7E]/g, "");
}
/** Sign-in field: a phone or an email, never Arabic characters. */
export function cleanIdentifier(s: string) {
  const v = latinDigits(s).replace(/[^\x21-\x7E]/g, "");
  return v.includes("@") ? v : cleanPhone(v);
}

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, "onChange" | "value"> & { value: string; onValue: (v: string) => void };

export function PhoneInput({ value, onValue, ...rest }: Props) {
  return <Input {...rest} value={value} onChange={(e) => onValue(cleanPhone(e.target.value))} inputMode="tel" dir="ltr" lang="en" autoComplete={rest.autoComplete ?? "tel"} maxLength={16} />;
}

export function EmailInput({ value, onValue, ...rest }: Props) {
  return <Input {...rest} value={value} onChange={(e) => onValue(cleanEmail(e.target.value))} type="email" inputMode="email" dir="ltr" lang="en" autoCapitalize="none" autoCorrect="off" spellCheck={false} autoComplete={rest.autoComplete ?? "email"} maxLength={254} />;
}

export function IdentifierInput({ value, onValue, ...rest }: Props) {
  return <Input {...rest} value={value} onChange={(e) => onValue(cleanIdentifier(e.target.value))} inputMode="email" dir="ltr" lang="en" autoCapitalize="none" autoCorrect="off" spellCheck={false} autoComplete={rest.autoComplete ?? "username"} maxLength={254} />;
}
