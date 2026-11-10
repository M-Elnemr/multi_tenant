/**
 * The ten built-in pictures a shop can give its AI assistant (three men, two girls, three women, two robots), drawn here as small flat illustrations so there is nothing to upload or host.
 * The default is Nada, a woman ("w1"). The same ids are stored in the shop's assistant settings (`agentAvatar`) and checked by the backend (ChatService.AGENT_AVATARS).
 */
export const AGENT_AVATARS = ["w1", "w2", "w3", "g1", "g2", "m1", "m2", "m3", "r1", "r2"] as const;
export type AgentAvatarId = (typeof AGENT_AVATARS)[number];
export const DEFAULT_AGENT_AVATAR: AgentAvatarId = "w1";

export function isAvatarId(v: unknown): v is AgentAvatarId {
  return typeof v === "string" && (AGENT_AVATARS as readonly string[]).includes(v);
}

const EYES = (y = 27.5) => (
  <>
    <circle cx="27.5" cy={y} r="1.5" fill="#2b2220" />
    <circle cx="36.5" cy={y} r="1.5" fill="#2b2220" />
  </>
);
const SMILE = (color = "#8a3f33") => <path d="M28 33.2c2.4 2.5 5.6 2.5 8 0" stroke={color} strokeWidth="1.5" fill="none" strokeLinecap="round" />;
const BODY = (cloth: string) => <path d="M7 64c1.5-14 12-20 25-20s23.500 6 25 20z" fill={cloth} />;
const NECK = (skin: string) => <rect x="27.500" y="38" width="9" height="9" rx="4" fill={skin} opacity="0.92" />;
const HEAD = (skin: string) => <ellipse cx="32" cy="27.500" rx="11.500" ry="13" fill={skin} />;
const CHEEKS = <><circle cx="24.500" cy="31.500" r="2.200" fill="#ff8d8d" opacity="0.28" /><circle cx="39.500" cy="31.500" r="2.200" fill="#ff8d8d" opacity="0.28" /></>;
const GLASSES = (
  <g fill="none" stroke="#2b2220" strokeWidth="1.300">
    <circle cx="27.500" cy="27.500" r="4.300" /><circle cx="36.500" cy="27.500" r="4.300" /><path d="M31.800 27.200h.4" />
  </g>
);

type Spec = { bg: string; draw: () => React.ReactNode };

const SPECS: Record<AgentAvatarId, Spec> = {
  // women
  w1: {
    bg: "#fde2e4",
    draw: () => (
      <>
        <path d="M18.500 29c-2.500-13 4-21 13.500-21s16 8 13.500 21c0 8 2.500 14 4 19H14.500c1.500-5 4-11 4-19z" fill="#4a2c20" />
        {BODY("#e0597a")}{NECK("#e8b896")}{HEAD("#f1c9a8")}
        <path d="M20 25c2-8.500 8-11.500 12.500-11.500S43 16.500 44 25c-3.500-3.500-7.500-5.500-12-5.500S23.500 21.500 20 25z" fill="#4a2c20" />
        {EYES()}{CHEEKS}{SMILE()}
      </>
    ),
  },
  w2: {
    bg: "#d9f2ef",
    draw: () => (
      <>
        {BODY("#1f8f87")}
        <path d="M16.500 31c-1.500-13 6-21 15.500-21s17 8 15.500 21c-.3 7-2 11-4.500 15H21c-2.500-4-4.200-8-4.500-15z" fill="#2aa79e" />
        <ellipse cx="32" cy="28" rx="9.800" ry="11.800" fill="#e8b896" />
        <path d="M22.500 22c3-4 6-5.500 9.500-5.500S38.500 18 41.500 22c-3-1.500-6-2.200-9.500-2.200S25.500 20.500 22.500 22z" fill="#2aa79e" />
        {EYES(28)}{CHEEKS}{SMILE()}
      </>
    ),
  },
  w3: {
    bg: "#ece0fb",
    draw: () => (
      <>
        <circle cx="32" cy="9.500" r="6" fill="#a34a28" />
        {BODY("#7e57c2")}{NECK("#b9825c")}{HEAD("#c68e66")}
        <path d="M20.500 26c-.5-9 5-14 11.500-14s12 5 11.500 14c-2.500-4-6-6-11.500-6s-9 2-11.500 6z" fill="#a34a28" />
        {GLASSES}{SMILE()}
        <circle cx="24.500" cy="33" r="1.800" fill="#e58a8a" opacity="0.3" /><circle cx="39.500" cy="33" r="1.800" fill="#e58a8a" opacity="0.3" />
      </>
    ),
  },
  // girls
  g1: {
    bg: "#fff1cc",
    draw: () => (
      <>
        <circle cx="19" cy="33" r="5" fill="#6b3f26" /><circle cx="45" cy="33" r="5" fill="#6b3f26" />
        {BODY("#ff8fb1")}{NECK("#f0c4a2")}{HEAD("#f7d3b5")}
        <path d="M20.500 26c0-9 5-14 11.500-14s11.500 5 11.500 14c-3-3-6.500-4.800-11.500-4.800S23.500 23 20.500 26z" fill="#6b3f26" />
        <circle cx="19.500" cy="28" r="2.400" fill="#ff5c8a" /><circle cx="44.500" cy="28" r="2.400" fill="#ff5c8a" />
        {EYES(28.500)}{CHEEKS}
        <path d="M28.500 33.600c2 2 5 2 7 0" stroke="#8a3f33" strokeWidth="1.500" fill="none" strokeLinecap="round" />
      </>
    ),
  },
  g2: {
    bg: "#dff3d9",
    draw: () => (
      <>
        <path d="M19 36c-3.500-14 1.500-26 13-26s16.500 12 13 26c-1.500-5.500-3-9-3-9H22s-1.500 3.500-3 9z" fill="#1d1a1a" />
        {BODY("#ffc93c")}{NECK("#c9946d")}{HEAD("#d8a37b")}
        <path d="M20.500 25c1.500-8 6.500-11.500 11.500-11.500S42 17 43.500 25c-3.500-3-7-5-11.500-5s-8 2-11.500 5z" fill="#1d1a1a" />
        <path d="M40 15.500l6-3.500-1 7z" fill="#e5484d" /><path d="M40 15.500l-1 7 6-3.500z" fill="#e5484d" opacity="0.85" /><circle cx="40.500" cy="16" r="1.600" fill="#b3262b" />
        {EYES(28.500)}{CHEEKS}
        <path d="M28.500 33.600c2 2 5 2 7 0" stroke="#8a3f33" strokeWidth="1.500" fill="none" strokeLinecap="round" />
      </>
    ),
  },
  // men
  m1: {
    bg: "#d6e8ff",
    draw: () => (
      <>
        {BODY("#2f6fdb")}{NECK("#e2ae88")}{HEAD("#edc19e")}
        <path d="M20.500 25c-1.500-9.500 5-14.500 11.500-14.500s13 5 11.500 14.500c-1-4-3-7-6-8-3 1.500-8 1.500-10 0-3 1-5 4-7 8z" fill="#2a1e1a" />
        {EYES()}{SMILE()}
      </>
    ),
  },
  m2: {
    bg: "#e1f0d8",
    draw: () => (
      <>
        {BODY("#2e8b57")}{NECK("#c28a62")}{HEAD("#d3996f")}
        <path d="M20.500 26c-1-9.500 5-14.500 11.500-14.500S44.500 16.500 43.500 26c-1.500-4.500-4-7-7-8-3 1.500-7 1.500-10 0-2.500 1-5 3.500-6 8z" fill="#241a17" />
        <path d="M21.500 29c0 9 4 14 10.500 14s10.500-5 10.500-14c-2 4.500-5 6.500-10.500 6.500S23.500 33.500 21.500 29z" fill="#241a17" />
        {EYES(26.500)}
        <path d="M28.500 33.800c2 1.600 5 1.600 7 0" stroke="#f4d9c6" strokeWidth="1.500" fill="none" strokeLinecap="round" />
      </>
    ),
  },
  m3: {
    bg: "#ffe3d1",
    draw: () => (
      <>
        {BODY("#5b6770")}{NECK("#8d5a3b")}{HEAD("#9c6544")}
        <path d="M20 26c-2-10 4-15.500 12-15.500s14 5.500 12 15.500c-1.500-4-3.500-6.500-6-7.500-3.500 1.500-8.500 1.500-12 0-2.500 1-4.500 3.500-6 7.500z" fill="#15110f" />
        <path d="M21 17c1-3 4-5 7-5.200M43 17c-1-3-4-5-7-5.200" stroke="#15110f" strokeWidth="2.600" strokeLinecap="round" fill="none" />
        {GLASSES}
        <path d="M28 33.400c2.500 2.500 5.500 2.500 8 0" stroke="#4a1f18" strokeWidth="1.500" fill="none" strokeLinecap="round" />
      </>
    ),
  },
  // robots
  r1: {
    bg: "#d3f1fb",
    draw: () => (
      <>
        <rect x="14" y="43" width="36" height="21" rx="7" fill="#3b82c4" />
        <rect x="26" y="49" width="12" height="3" rx="1.500" fill="#bfe3ff" />
        <path d="M32 12V6.500" stroke="#3b82c4" strokeWidth="2" strokeLinecap="round" /><circle cx="32" cy="5.500" r="2.600" fill="#ff5c5c" />
        <rect x="12" y="22" width="4.500" height="10" rx="2.200" fill="#2d6aa8" /><rect x="47.500" y="22" width="4.500" height="10" rx="2.200" fill="#2d6aa8" />
        <rect x="16" y="12" width="32" height="28" rx="9" fill="#5aa7e8" />
        <rect x="21" y="19" width="22" height="13" rx="6" fill="#102a43" />
        <circle cx="27.500" cy="25.500" r="2.800" fill="#5ff2ff" /><circle cx="36.500" cy="25.500" r="2.800" fill="#5ff2ff" />
        <path d="M27.500 35.500h9" stroke="#e8f6ff" strokeWidth="2" strokeLinecap="round" />
      </>
    ),
  },
  r2: {
    bg: "#ffe9cf",
    draw: () => (
      <>
        <path d="M13 64c1-12 9-17 19-17s18 5 19 17z" fill="#e8772e" />
        <path d="M32 11V6" stroke="#c75b17" strokeWidth="2" strokeLinecap="round" /><circle cx="32" cy="5" r="2.400" fill="#ffd54a" />
        <circle cx="32" cy="28" r="15" fill="#f6a35a" />
        <path d="M17.500 27a14.500 14.500 0 0 1 29 0" stroke="#7a3d12" strokeWidth="2.600" fill="none" strokeLinecap="round" />
        <rect x="14" y="26" width="5" height="10" rx="2.500" fill="#7a3d12" /><rect x="45" y="26" width="5" height="10" rx="2.500" fill="#7a3d12" />
        <rect x="21" y="23" width="22" height="10" rx="5" fill="#2a1a10" />
        <circle cx="27.500" cy="28" r="2.300" fill="#ffd54a" /><circle cx="36.500" cy="28" r="2.300" fill="#ffd54a" />
        <path d="M28.500 38c2 1.500 5 1.500 7 0" stroke="#7a3d12" strokeWidth="1.800" fill="none" strokeLinecap="round" />
      </>
    ),
  },
};

/** The assistant's round picture; an unknown or missing id shows the default (Nada). */
export function AgentAvatar({ id, className = "h-8 w-8", title }: { id?: string | null; className?: string; title?: string }) {
  const spec = SPECS[isAvatarId(id) ? id : DEFAULT_AGENT_AVATAR];
  const clip = `av-${isAvatarId(id) ? id : DEFAULT_AGENT_AVATAR}`;
  return (
    <svg viewBox="0 0 64 64" className={`shrink-0 rounded-full ${className}`} role={title ? "img" : undefined} aria-label={title} aria-hidden={title ? undefined : true}>
      <defs><clipPath id={clip}><circle cx="32" cy="32" r="32" /></clipPath></defs>
      <g clipPath={`url(#${clip})`}>
        <rect width="64" height="64" fill={spec.bg} />
        {spec.draw()}
      </g>
    </svg>
  );
}
