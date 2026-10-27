# Model testbench

The testbench measures how well LearnSphere's study assistant works on real material on this computer. It writes
its own course PDFs with known answers, runs them through the app's real import, search and AI pipeline (the `pdf`
module, the Lucene index and the llama.cpp servers of `LocalAi`), and writes a Markdown report with every measure
against its target.

```sh
./gradlew :testbench:run -Plearnsphere.runtime=/path/to/runtime
```

The report is written to `testbench/build/report.md`. A full run with the 4B chat model takes about 15 to 25 minutes
on an Apple M4 Pro.

Without a runtime folder the testbench still generates the material, checks the ground truth and writes a report
that says the AI evaluations were skipped, so the command never fails for lack of models.

## Options

| Property | Default | Meaning |
|---|---|---|
| `-Plearnsphere.runtime=<dir>` | `LEARNSPHERE_RUNTIME`, else `<repo>/runtime` | The runtime folder: `llama/<platform>/…/llama-server` and `models/{chat-4b,chat-9b,embed,rerank}`. |
| `-Plearnsphere.testbench.models=chat-4b,chat-9b` | every available chat model | Which chat models to evaluate, one full run each. |

## What it measures

| Area | Measures |
|---|---|
| Import | Time per page, passages per page, time spent reading scanned pages, describing figures and embedding; character and word accuracy of the scanned-page transcriptions. |
| Retrieval | Recall@1, recall@5, MRR and recall within the 24 candidates for keyword-only, vector-only, fused, and fused-plus-reranker ranking; a sweep of the evidence threshold. |
| Answers | Citation validity and precision, refusal rate on uncovered questions, false-refusal rate, key-term coverage, time to the first words and to the full answer. |
| Generation | Concepts extracted, items stored, valid JSON replies, type-rule compliance, near-duplicates, the support checker against labelled pairs, sample items. |
| Grading | Verdict accuracy of the short-answer grader against a teacher's verdicts, with the confusion matrix. |
| Resources | Load time of each server, peak resident size and physical footprint per server and per phase, llama.cpp buffer sizes, chat throughput. |

The targets and their reasons are in `report/Targets.java`; the report's last section defines every measure.

## Files

| Path | Contents |
|---|---|
| `build/report.md` | The report. |
| `build/testbench/material/` | The generated PDFs: `os-chapter.pdf` (20 pages), `research-methods.pdf` (10 pages) and `research-methods-scanned.pdf` (three of its pages as noisy, slightly rotated JPEG scans with no text layer). |
| `build/testbench/home-<model>/` | The profile the run used and the llama-server logs (`logs/llama-*.log`). Emptied at the start of each run. |
| `build/testbench/testbench.log` | The full run log. |

## How it works

- `material/` turns the authored chapters in `src/main/resources/edu/learnsphere/testbench/material/*.txt` into
  PDFs. The format is line based: `@header` and `@title` once, `@page` before each printed page, `#` and `##`
  headings, paragraphs separated by blank lines, `- ` bullets, `@table <caption>` followed by `| a | b |` rows and
  `@end`, `@figure <art> <caption>` for a drawn figure, and `%` comments (see `ChapterParser`). The evaluation set
  names pages, so moving text between pages means updating `evaluation-set.json`.
- `truth/EvaluationSet` loads `evaluation-set.json`: questions with their answering pages, reference answers, key
  terms and plausible wrong answers; uncovered questions; short-answer questions with graded student answers.
  Every run first checks that each question's `evidence` phrase is on one of its pages in the extracted text, so
  editing a chapter cannot silently break the ground truth. `EvaluationSetTest` runs the same check.
- `eval/ModelRun` starts the servers with one chat model, opens a fresh profile through `ProfileServices` with the
  real capabilities, and runs the evaluations in order: import, retrieval, answers, generation, grading.
- `probe/Recorder` wraps the capabilities in pass-through decorators that record each call (passages given to the
  answerer, raw answers, transcriptions, drafts, support verdicts) and pins the runtime to the evaluated model.
  The app's services run unchanged on top of them.
- `probe/ChatLog` reads per-request statistics (tokens, time to first token, invalid JSON, dropped drafts) from
  the `ai` module's log messages. It reads their arguments by position: if those log statements change, the
  statistics come out empty rather than failing the run, and the report shows them as zero.
- `probe/MemorySampler` samples the llama-server processes with `ps` and, on macOS, `footprint` (which includes
  Metal buffers in shared memory).

Every llama-server process a run starts is stopped when the run ends, also when an evaluation fails; the report
names any process that had to be stopped afterwards.

Do not rebuild the testbench while a run is in progress: the run loads classes from the build folder as it needs
them.

## Tests

```sh
./gradlew :testbench:test
```

The tests cover the scoring code (recall, MRR, character accuracy, citation markers, key terms, type rules,
threshold sweep, near-duplicates), the log and process-output parsers, the report writer, the generated material and
the ground truth. They need no runtime folder.
