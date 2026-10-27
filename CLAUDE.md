# LearnSphere

Offline desktop study app (Java 25, JavaFX, SQLite, Lucene, llama.cpp). Read `docs/ARCHITECTURE.md` for the module structure, data model, services and pipelines, and `docs/DESIGN.md` for the visual design before changing either area.

## Commands

- Build and test everything: `./gradlew build`
- One module's tests: `./gradlew :core:test` (or `:pdf`, `:search`, `:ai`, `:app`)
- Real-model tests: `./gradlew :ai:integrationTest` (needs `runtime/`)
- Run the app: `./gradlew :app:run`; with demo data and fake AI: `./gradlew :app:runDemo`
- Render every screen to PNG: `./gradlew :app:screenshots` → `app/build/screenshots/`
- Model evaluation: `./gradlew :testbench:run` → `testbench/build/report.md`

## Conventions

- `core` depends on no other module. New capabilities that need PDF, search or AI code get an interface in `edu.learnsphere.core.spi` and an implementation in the owning module.
- All dependency versions live in `gradle/libs.versions.toml`.
- Records for data, constructor injection, no DI framework, no Lombok. Services are thread-safe and blocking; UI code calls them off the FX thread and applies results with `Fx.run`.
- IDs are UUID strings, times are epoch millis, time comes from an injected `Clock`.
- Every user-facing string follows the in-app copy rules: written for a first-time user, no implementation words (model, server, index, embedding, chunk, file paths, codes). Errors reach the UI as a `UserMessage`, never as exception text.
- UI is built in code (no FXML). Colours, fonts and spacing come only from the theme stylesheet tokens in `docs/DESIGN.md`.
- Tests use temp directories (`learnsphere.home`), real SQLite and Lucene, and the AI fakes in `edu.learnsphere.ai.fake`. Never depend on `runtime/` outside `integrationTest` and the testbench.
- `runtime/` (llama.cpp builds and model files) is git-ignored and never committed.
