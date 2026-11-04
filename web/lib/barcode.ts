/** Barcodes drawn in the browser (no library, works offline): EAN-13 for 13-digit codes with a good check digit, Code 128 (set B) for anything else. */

const L = ["0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"];
const G = ["0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111"];
const R = ["1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100"];
const PARITY = ["LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"];

export function isEan13(code: string): boolean {
  if (!/^\d{13}$/.test(code)) return false;
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(code[i]) * (i % 2 === 0 ? 1 : 3);
  return (10 - (sum % 10)) % 10 === Number(code[12]);
}

/** The modules (1 = bar) of an EAN-13. */
function ean13Bits(code: string): string {
  const p = PARITY[Number(code[0])];
  let out = "101";
  for (let i = 1; i <= 6; i++) out += (p[i - 1] === "L" ? L : G)[Number(code[i])];
  out += "01010";
  for (let i = 7; i <= 12; i++) out += R[Number(code[i])];
  return out + "101";
}

const C128 = ["212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
  "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
  "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
  "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
  "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
  "114131", "311141", "411131", "211412", "211214", "211232"];
const STOP = "2331112";

/** The modules of a Code 128 set B (printable ASCII). */
function code128Bits(text: string): string {
  const vals = [...text].map((ch) => Math.min(94, Math.max(0, ch.charCodeAt(0) - 32)));
  let check = 104;
  vals.forEach((v, i) => { check += v * (i + 1); });
  const seq = [104, ...vals, check % 103];
  let out = "";
  for (const v of seq) { const w = C128[v]; for (let i = 0; i < w.length; i++) out += (i % 2 === 0 ? "1" : "0").repeat(Number(w[i])); }
  for (let i = 0; i < STOP.length; i++) out += (i % 2 === 0 ? "1" : "0").repeat(Number(STOP[i]));
  return out;
}

/** An SVG of the barcode (bars only; print the number under it as text). Scales to its box: set width/height with CSS. */
export function barcodeSvg(code: string): string {
  const bits = isEan13(code) ? ean13Bits(code) : code128Bits(code);
  const quiet = 10, w = bits.length + quiet * 2;
  let rects = "";
  for (let i = 0; i < bits.length; ) {
    if (bits[i] !== "1") { i++; continue; }
    let j = i;
    while (bits[j] === "1") j++;
    rects += `<rect x="${i + quiet}" y="0" width="${j - i}" height="50"/>`;
    i = j;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} 50" preserveAspectRatio="none" shape-rendering="crispEdges" fill="#000">${rects}</svg>`;
}
