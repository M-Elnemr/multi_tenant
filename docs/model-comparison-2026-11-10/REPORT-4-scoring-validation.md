# Scoring validation of the reasoning-on benchmark (and the run before it)

Date: 2026-11-11. No new model calls: every number below is recomputed from the raw per-conversation files in this folder with one script, `score.py`
(`python3 -I score.py rerun-reasoning-on-trial1.json.gz rerun-reasoning-on-trial2.json.gz`). Each line of its output shows its denominator.

## What was inconsistent

1. **Denominators were the same, the scripts were not.** In every cell (model x prompt) the judged set is 74 conversations per trial, 148 over two trials, and the rubric holds
   177 lines per trial (354 pooled). So no model got an easier or smaller set. The disagreement between earlier tables came from the scripts:
   - `tables.py` pooled all 80 conversations, including the order scenarios' judge lines (those are scored by code; the judge's lines for them are mostly noise);
   - `final.py` and `bench2.py` pooled the 74 judged conversations;
   - `stats.py` and `compare_low.py` averaged each conversation's own passed/of (a "mean of fractions", which is lower, 1 to 2 points, than the pooled figure because conversations carry 1 to 5 lines).
   `score.py` now prints pooled, mean and all-conversation figures side by side, so the choice is visible. The report's headline stays the pooled figure over judged conversations.
2. **Judge failures were scored as 0 out of N.** In the reasoning-on run 9 of the 888 judged conversation-cells (148 per cell, 6 cells) (2 to 4 per model) had no usable judge answer; the judge's JSON was cut off
   (141 of 142 judge failures in the files say "Unexpected end-of-input"; the judge call used the 1,000-token limit meant for customers' replies). They fell unevenly: DeepSeek full 3, GLM lean 2,
   Qwen full 3, Qwen lean 1, GLM full 0, DeepSeek lean 0. The old figures therefore understated those configurations by up to 2.8 points in one cell.
3. **Incomplete judge answers** (fewer rubric lines than the rubric) were counted as failed lines, silently (3 cases).

## Corrected figures (graded judged conversations only, judge failures left out)

| Run | Model | As reported before | Corrected | Judge failures left out | Graded checks |
|---|---|---|---|---|---|
| reasoning on | GLM | 59.9% | 60.3% | 2 | 703 |
| reasoning on | DeepSeek | 62.4% | 63.1% | 3 | 700 |
| reasoning on | Qwen | 57.3% | 57.9% | 4 | 701 |
| production settings (REPORT-2) | GLM | 59.5% | 60.2% | 3 | 699 |
| production settings (REPORT-2) | DeepSeek | 60.9% | 61.9% | 4 | 696 |
| production settings (REPORT-2) | Qwen | 58.3% | 59.7% | 5 | 694 |

(Per model, both prompts together, two trials. Per cell figures are in the `score.py` output.)

**What this changes.** The order of the models does not change in either run (DeepSeek, then GLM, then Qwen). Every figure moves up by 0.4 to 1.4 points; the gaps move by at most 0.8 points
(reasoning-on DeepSeek minus Qwen: +5.1 before, +5.2 after). The conclusions of REPORT-3 and REPORT-2 stand: DeepSeek ahead of Qwen by about five points with a borderline interval, GLM in between,
trial-to-trial noise of about 4 points per configuration. Nothing here shows a real difference between full and lean prompts beyond that noise.

## What was fixed in the code

- The judge now answers with a 3,000-token limit (`AiEvalService.JUDGE_MAX_OUTPUT`), is asked again once when its answer is cut off or lists too few rubric lines, and a conversation it still cannot grade is
  marked `judgeFailed` and left out of the percentages (the summary reports `ungraded` separately). A still-incomplete answer is marked `judgeShort`. Tests: `AiEvalJudgeIntegrationTest`, `AiEvalSummaryTest`.
- These changes apply to future runs. The files in this folder were produced before them, which is why `score.py` detects a judge failure from the note text.
