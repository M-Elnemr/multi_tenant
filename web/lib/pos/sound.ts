/** A short beep for a good scan and a low buzz for an unknown code, made by the browser (no sound files, works offline). */
let ctx: AudioContext | null = null;

function tone(freq: number, ms: number, type: OscillatorType, volume: number) {
  try {
    const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AC) return;
    ctx = ctx ?? new AC();
    if (ctx.state === "suspended") void ctx.resume();
    const o = ctx.createOscillator(), g = ctx.createGain();
    o.type = type; o.frequency.value = freq;
    g.gain.setValueAtTime(volume, ctx.currentTime);
    g.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + ms / 1000);
    o.connect(g); g.connect(ctx.destination);
    o.start(); o.stop(ctx.currentTime + ms / 1000);
  } catch { /* no audio on this device */ }
}

export const beepOk = () => tone(1320, 90, "square", 0.06);
export const beepBad = () => tone(180, 260, "sawtooth", 0.08);
