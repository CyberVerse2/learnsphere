# LearnSphere visual design

## Character

LearnSphere should feel like a calm, well-lit study: warm paper, crisp ink, one confident colour, and nothing competing with the material. It is an editorial study companion, not a dashboard product. Every screen answers "what should I do next?" first, and decoration only ever serves orientation or delight in a learning moment.

Principles:

1. **The material is the hero.** Questions, answers, passages and cards get the largest, most readable type. Chrome stays quiet.
2. **One accent, used with intent.** Lapis blue marks the primary action and the learner's progress. Everything else is warm neutrals.
3. **Generous space, firm alignment.** An 8 px grid, wide margins, a clear left edge. Density only in the Studio, where creators work through lists.
4. **Feedback feels human.** Correct answers, streaks and finished sessions get small, warm moments (a ring filling, a soft check, a line of encouragement), never confetti noise.
5. **Honest about the AI.** Anything written by AI is labelled in plain words ("AI draft", "Read by AI from a scanned page"). Answers always show where they came from.

## Typography

| Role | Font | Size / weight | Use |
|---|---|---|---|
| Display | Newsreader | 34 / Medium, 1.15 line height | Screen titles, the question in a study session |
| Title | Newsreader | 24 / Medium | Card titles, course names |
| Heading | Inter | 15 / SemiBold, +0.2 letter spacing for overlines | Section headings |
| Body | Inter | 14 / Regular, 1.5 line height | Interface text |
| Reading | Newsreader | 17 / Regular, 1.55 line height | Answers, passages, flashcard faces |
| Caption | Inter | 12 / Medium | Metadata, labels, status pills |
| Numeral | Newsreader | 40 / Medium, tabular | Big stats (due today, streak, mastery) |

Fonts ship inside the app as static TTF instances under the SIL Open Font License (Inter 4.1 from its release, Newsreader instanced from the Google Fonts variable font: Regular and Italic at optical size 16, Medium at 28) and load at startup, so text never depends on what the computer has installed. The files live in `app/src/main/resources/edu/learnsphere/app/theme/fonts/`:

| File | Face |
|---|---|
| `Inter-Regular.ttf`, `Inter-Medium.ttf`, `Inter-SemiBold.ttf`, `Inter-Bold.ttf` | Inter 400, 500, 600, 700 |
| `Newsreader-Regular.ttf`, `Newsreader-Medium.ttf`, `Newsreader-Italic.ttf` | Newsreader 400, 500, 400 italic |
| `OFL-Inter.txt`, `OFL-Newsreader.txt` | the licences, shipped with the fonts |

`Typography` loads each file and maps it to the CSS classes of its role. A missing file is logged as a warning naming it, and that role uses an installed fallback (Segoe UI or the system sans; PT Serif, Georgia or Cambria). With every file present, `./gradlew :app:screenshots` logs `bundled=true` for all seven faces.

## Colour

Colours are defined once as looked-up colours on `.root` in `app/src/main/resources/edu/learnsphere/app/theme/learnsphere.css` and overridden by `.root.dark`. Components reference only these names.

| Token | Light | Dark | Use |
|---|---|---|---|
| `-ls-bg` | `#F6F3EE` | `#141311` | Window background ("paper") |
| `-ls-surface` | `#FFFFFF` | `#1D1B19` | Cards, panels |
| `-ls-surface-sunken` | `#EFEBE4` | `#100F0E` | Sidebar, wells, inputs |
| `-ls-border` | `#E4DED4` | `#2E2B27` | Hairlines |
| `-ls-text` | `#1E1C19` | `#EEEAE3` | Primary text |
| `-ls-text-muted` | `#6E685E` | `#A59F95` | Secondary text |
| `-ls-text-faint` | `#9C958A` | `#6F6A62` | Placeholders, disabled |
| `-ls-accent` | `#2F55D4` | `#7E97FF` | Primary actions, progress, focus |
| `-ls-accent-soft` | `#E6EBFB` | `#1F2645` | Selected rows, accent backgrounds |
| `-ls-success` | `#2E7D5B` | `#5DBB8E` | Correct, ready |
| `-ls-warning` | `#B26B12` | `#E0A458` | Needs review, due soon |
| `-ls-danger` | `#B3413A` | `#E5796F` | Wrong, failed |
| `-ls-highlight` | `#FFE9A8` | `#5A4A1C` | Cited passage highlight on a PDF page |

All text/background pairs meet WCAG AA (4.5:1 for body, 3:1 for large text and icons). The theme follows the system appearance by default; Settings can force light or dark.

## Course covers

Each course gets generated cover art from its `cover_hue`: a soft two-stop gradient field with a large sphere built from concentric orbit lines and one small "moon", drawn with JavaFX shapes (no images). Covers appear on course cards, the course header and exported packages' preview. They give every course an identity without asking creators for artwork.

## Layout

- **Window:** minimum 1100×720, default 1360×860. Left sidebar 232 px (`-ls-surface-sunken`), content area on `-ls-bg`, content column max 1120 px centred with 40 px side padding.
- **Sidebar:** app mark and name, primary navigation (Home, Library, Progress, Studio), a "Studying" list of recent courses with tiny covers, then Settings and the profile chip at the bottom. Active item: accent-soft pill with accent icon.
- **Cards:** 14 px radius, 1 px `-ls-border`, shadow `dropshadow(gaussian, rgba(30,28,25,0.06), 24, 0, 0, 8)`; hover lifts 2 px and deepens the shadow (150 ms).
- **Spacing scale:** 4, 8, 12, 16, 24, 32, 48, 64.
- **Radius scale:** 6 (inputs, pills), 10 (buttons), 14 (cards), 999 (chips, rings).
- **Buttons:** primary = accent fill, white text, 40 px tall; secondary = surface with border; quiet = text only. One primary per view.

## Motion

- Route change: content fades in and rises 8 px over 180 ms (ease-out).
- Progress rings and bars animate to their value over 600 ms.
- Flashcards flip with a horizontal scale-through (220 ms) and the back face fades in.
- Correct answer: the chosen option tints success and a check draws in; wrong: a single 6 px shake and the right option is revealed with its explanation.
- Respect a "Reduce motion" setting by replacing movement with fades.

## Key screens

1. **Home:** greeting with the date; a hero "Study next" card (count due, estimated minutes, one primary button); "Continue" course cards; a small week strip of reviews done; recent questions with their citations.
2. **Library:** course cards in a responsive grid with covers, progress ring and due count; segmented control Studying / Creating; Import course and New course actions; an inviting empty state.
3. **Course:** cover header with title, author, version; modules as a vertical path with completion; concept mastery chips; Ask and Study as the two main actions.
4. **Ask:** conversation on the left, source viewer on the right. Ask is a chat with the study assistant: every message gets a reply in reading type, with citation chips `p. 12` wherever it states something from the course; clicking one opens the PDF page with the passage highlighted. It greets, teaches, quizzes one question at a time in the chat, and says in its own words when the course doesn't cover something. When there is nothing to answer from (no PDF ready yet), a calm, distinct card says so and offers the course's topics as chips; a chip puts a question about its topic in the question box.
5. **Study session:** focus mode. The sidebar collapses, a slim progress bar sits at the top, the prompt is set large in Newsreader, options are big tappable rows with number shortcuts, confidence is three chips (Guessing, Fairly sure, Certain). After answering: explanation, a "From your material" source link, and the next review date in plain words ("You'll see this again in 4 days"). The session ends on a summary with accuracy, time and what improved.
6. **Progress:** mastery by concept (horizontal bars), reviews forecast for 14 days (columns), retention trend (line), streak. Charts are drawn on Canvas in the theme colours, with direct labels instead of legends.
7. **Studio:** a working surface: course details, modules list with drag handles (a course without modules offers Suggest modules, which shows the study assistant's suggestion for renaming or removing units before they are created), documents with live status pills and progress, a review queue showing each AI draft beside its source passage with Approve / Edit / Regenerate / Reject, scanned-page review (page image beside the text read from it), publish and share.
8. **Onboarding:** three calm steps: welcome, create your profile (name, optional password, avatar colour), and get the study assistant ready (shows readiness, explains in one line that everything stays on this computer).

## Copy

Interface text follows the in-app copy rules: written for someone opening the app for the first time; describes what they see and can do; never mentions models, servers, indexes, embeddings, chunks, files on disk, or error codes. Errors are one short sentence the user can act on. Examples:

- "Reading your PDF… page 14 of 120"
- "Your material doesn't cover this. Try rephrasing, or add a document that does."
- "These pages were read from a scanned image. Check the text before learners rely on it."
- "Getting your study assistant ready"
- "You're all caught up. New reviews unlock tomorrow."
