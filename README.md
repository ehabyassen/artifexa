# Artifexa

**AI design and code review for your feature branch, inside IntelliJ IDEA.**

Artifexa looks at every commit on your branch since it split from the base branch. It builds a
component graph of the code you touched, then reviews the change against the task description, its
acceptance criteria and your team's coding standards. You get:

- **An architecture assessment**: layer violations (Controller → Service → Repository → Domain),
  coupling hotspots, and changed components without tests
- **Best-practice findings** with severity (error / warning / info), each tied to the component it affects
- **A line-by-line review** for each changed class, anchored to new-file line numbers
- **A task-completion score (%)**, with the parts of the task that are done and the parts still missing
- **Visualisations**: an interactive component graph, a class diagram and a dependency matrix

I built it to speed up code review on a large enterprise Java codebase. Reviewers start from a
structured report instead of a raw diff.

## How it works

```
Git branch ──► BranchPointDetector (merge-base) ──► BranchAnalyser (diff hunks per file)
                                                            │
PSI (Java) ──► ComponentGraphBuilder ──► LayerClassifier ───┤
                                                            ▼
                     LocalReviewEngine (rules, no network) + PromptBuilder (60k-char budget)
                                                            │
                                         LlmClient (SSE streaming, retry) ──► ReviewResult (JSON)
                                                            │
                                         ReviewPanel · GraphPanel · ClassDiagram · DependencyMatrix
```

- **Static analysis first.** The component graph and the layer, coupling and test-coverage checks run
  locally on IntelliJ's PSI. The LLM adds judgement on top of facts the plugin has already computed.
- **Bounded context.** `PromptBuilder` sends the graph summary, the changed classes' metadata
  (signatures, fields, annotations, imports) and the diff hunks per component. Whole files are never
  sent. Large graphs are compressed and truncated to fit a 60,000-character budget.
- **Structured output.** The model must return one JSON object matching `ReviewResult`, and line
  comments must cite line numbers that appear in the hunks. `AiReviewEngine` pulls the JSON out of
  the response and parses it tolerantly.
- **Resilient streaming.** `LlmClient` streams over SSE with Kotlin `channelFlow` and OkHttp. It
  retries with exponential backoff (3 attempts, starting at 500 ms).
- **Your standards, your rules.** Point it at a coding-standards document by URL. Its MUST / MUST NOT
  bullets become local checks, and the full text goes into the prompt.

## Providers

| Provider | Endpoint | Default model |
|---|---|---|
| OpenAI | `api.openai.com/v1/chat/completions` | `gpt-4o` |
| Anthropic | `api.anthropic.com/v1/messages` | `claude-sonnet-4-20250514` |
| GitHub Models | `models.github.ai/inference` | `openai/gpt-4o` |
| Custom | any OpenAI-compatible URL (e.g. a self-hosted or company gateway) | configurable |

API keys are kept in IntelliJ's **PasswordSafe** (the OS keychain), never in plain-text settings.

## Privacy

- **Local-only mode** runs the rule-based review only, with no network calls.
- With an LLM enabled, Artifexa sends **structured metadata plus the changed hunks**. Unchanged code
  and whole files stay on your machine.
- Use the **Custom** provider to keep traffic inside your company's approved LLM gateway.

## Install

Requirements: IntelliJ IDEA 2023.3 or later (Community or Ultimate) and JDK 17.

```bash
git clone https://github.com/ehabyassen/artifexa.git
cd artifexa
./gradlew buildPlugin
```

Then in IntelliJ, go to **Settings → Plugins → ⚙ → Install Plugin from Disk…** and choose
`build/distributions/artifexa-0.1.0.zip`.

To try it in a sandbox IDE instead: `./gradlew runIde`.

## Use

1. **Settings → Tools → Artifexa**: pick a provider, paste an API key, and set the target branch
   (default `main`). Optionally add a coding-standards URL.
2. Check out your feature branch and open the **Artifexa** tool window (right sidebar).
3. Paste the task description and acceptance criteria.
4. **VCS → Artifexa → Analyse Branch** builds the graph. **Run AI Review** produces the report.

## Development

```bash
./gradlew test        # unit tests (JUnit 4/5)
./gradlew runIde      # launch a sandbox IDE with the plugin
```

```
src/main/kotlin/com/artifexa/
├── actions/        # IDE actions: Analyse Branch, Run AI Review
├── ai/             # LlmClient, PromptBuilder, AiReviewEngine, LocalReviewEngine, StandardsFetcher
├── graph/          # ComponentGraphBuilder, LayerClassifier, nodes/edges
├── vcs/            # merge-base detection, per-file diff hunks
├── visualisation/  # graph, class diagram, dependency matrix
├── settings/       # persistent state + PasswordSafe credentials
└── ui/             # tool window, review panel, settings page
```

Stack: Kotlin 1.9, IntelliJ Platform Gradle Plugin 2.1, Git4Idea, OkHttp + SSE,
kotlinx-serialization, coroutines.

## Status

Version 0.1.0. It supports Java projects only. On the roadmap: Kotlin PSI support, inline editor
annotations for line comments, and an evaluation set of seeded-defect branches to measure review
precision across providers.

## Author

**Ehab Ahmed**, Senior Software Engineer: [LinkedIn](https://linkedin.com/in/ehabyassen) ·
[GitHub](https://github.com/ehabyassen)
