# LearnSphere acceptance tests

Eight end-to-end tests define "LearnSphere works". Each one is a TestFX test in `app/src/test/java/edu/learnsphere/app/acceptance/` named after its id (for example `T03AskWithCitationsTest`), and runs with `./gradlew :app:test` on JavaFX's headless platform.

Every test starts from an empty app home in a temporary folder, uses the real services over SQLite and Lucene, and the AI fakes from `edu.learnsphere.ai.fake` (`FakeAi.create().capabilities()`), so results are deterministic and no model files are needed. Fixture PDFs live in `app/src/test/resources/acceptance/`: `cells.pdf` (12 pages with a text layer), `scanned.pdf` (3 pages of images only) and `locked.pdf` (password-protected).

Expected text is quoted exactly where the wording matters; everywhere else the test checks state (screen, counts, stored rows).

## T01: First run creates a profile

**Steps**

1. Start the app with an empty app home.
2. On the welcome step, continue.
3. Create a profile named "Ada Lovelace", pick an avatar colour, set the password "correct horse".
4. Wait on the assistant step until it shows that the study assistant is ready, then finish.

**Expected**

- The app opens on Onboarding with no sidebar.
- After step 3, `profiles.json` holds one profile with a password hash and `app-settings.json` names it as the last profile.
- Step 4 shows "Getting your study assistant ready", then "Your study assistant is ready".
- Finishing lands on Home with the sidebar, the profile chip reading "Ada Lovelace" and the Home empty state.
- Restarting the app asks for the password; "wrong" shows "That password isn't right. Try again."; "correct horse" opens Home.

## T02: Importing PDFs into a course

**Steps**

1. In the Studio, create the course "Cell Biology" with the module "Cells".
2. Import `cells.pdf` into the module.
3. Import `scanned.pdf` into the module.
4. Import `locked.pdf`.
5. Open the scanned-page review for `scanned.pdf`, edit the text of page 2 and approve all pages.

**Expected**

- `cells.pdf` shows a live status pill ("Reading your PDF… page 3 of 12" style progress) and ends as ready; its passages are searchable.
- `scanned.pdf` stops at "needs review", and its pages are not used to answer questions until step 5.
- `locked.pdf` fails with "This PDF is password-protected. Remove the password and import it again."
- After step 5, `scanned.pdf` becomes ready and page 2 holds the edited text.
- Importing `cells.pdf` a second time reuses the stored file (one file under `files/`).

## T03: Asking a question gives cited answers

**Steps**

1. With the course from T02, enrol and open Ask.
2. Ask a question answered on page 4 of `cells.pdf`.
3. Click the first citation chip.
4. Ask a question about something the PDFs do not cover.
5. Ask another question and cancel it while the answer streams.

**Expected**

- The answer streams in, then is replaced by the final text with at least one citation chip such as "p. 4".
- Step 3 opens the source viewer on page 4 with the cited passage highlighted.
- Step 4 shows the insufficient-evidence card ("Your material doesn't cover this. Try rephrasing, or add a document that does.") and no answer is requested from the study assistant.
- Step 5 stops the answer and stores the message as cancelled.
- The thread appears under recent questions on Home.

## T04: Generating and reviewing questions

**Steps**

1. In the Studio, extract concepts for the module "Cells".
2. Generate 6 questions (multiple choice, true/false, flashcard) for the module.
3. In the review queue, approve two drafts, edit and approve one, reject one and regenerate one.
4. Publish the course with the note "First version".

**Expected**

- Concept extraction and generation show progress lines and finish without leaving the screen.
- Every draft in the review queue appears beside its source passage and page.
- After step 3 the course has 3 approved items, 1 rejected, and a replacement draft for the regenerated one.
- Step 4 creates version 1 containing exactly the approved items; the course card shows version 1.

## T05: A study session schedules reviews

**Steps**

1. Enrol in the course and start Study next from Home.
2. Answer a multiple-choice item correctly with "Certain".
3. Answer one wrongly.
4. Rate a flashcard "Good".
5. Answer a short-answer item, then dispute its grade.
6. Finish the session.

**Expected**

- The session is full screen with a progress bar and no sidebar.
- After each answer the explanation and "From your material" link appear, with the next review in plain words ("You'll see this again in 4 days").
- The wrong answer is rated Again and comes back within the session.
- The short answer is labelled as graded by the study assistant; disputing it records the attempt as Good.
- The summary shows accuracy and time; Home shows the reviews done today and the updated due count.
- New items introduced today never exceed the new-items-per-day setting.

## T06: Progress across courses

**Steps**

1. Study two enrolled courses, leave a third after studying it, and archive a fourth.
2. Open Progress.
3. Pick one course in the course picker.

**Expected**

- The overview sums progress, accuracy, reviews and study time over the two enrolled courses only; the left and archived courses do not appear in it, in Study next or on Home.
- The 14-day forecast has 14 columns starting today; mastery bars list the weakest concept first.
- Picking a course shows its module completion and its own forecast.

## T07: Sharing a course

**Steps**

1. Export the published course from T04 as a protected package with the code "orchid".
2. In a second profile, import the package with the code "lily", then with "orchid".
3. Study one item, then import an updated package (version 2, one item removed, one added).
4. In the first profile, import the package of its own course.

**Expected**

- Step 2 first shows "That code isn't right. Check it with the course author.", then the import runs as a background job and adds the course to the library.
- After step 3 the course keeps the learner's progress on unchanged items, retires the removed one and adds the new one.
- Step 4 is refused before importing, because the course was made in this profile.

## T08: Profiles survive restarts, backups and restores

**Steps**

1. Start importing `cells.pdf` and quit the app while it is being read.
2. Start the app again.
3. Back up the profile to a zip file.
4. Restore the zip.
5. Delete the restored profile.

**Expected**

- After step 2 the import continues where it stopped and ends as ready.
- Step 4 adds a second profile with the same name, courses, PDFs and progress; the search index is rebuilt in the background.
- An open profile cannot be deleted ("This profile is open. Close it and try again."); after switching away, step 5 removes the profile and its folder.
