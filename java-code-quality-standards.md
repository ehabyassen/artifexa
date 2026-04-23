# Java Code Quality Standards

A checklist for reviewing changes in Java projects. Reviewers should use this as a reference when approving pull requests, and authors should self-review against it before requesting review.

---

## 1. Code Structure & Design

### Single Responsibility
- Each class has one clear purpose; methods do one thing well.
- Classes longer than ~300 lines or methods longer than ~40 lines should be justified or refactored.
- Avoid "utility god classes" — split helpers by domain (e.g. `StringUtils`, `DateUtils`) rather than a single `Utils`.

### Package Organization
- Group by feature/domain (e.g. `com.company.billing`, `com.company.auth`) rather than by layer alone.
- Keep package-private visibility as the default; only expose what consumers need.
- No circular dependencies between packages.

### SOLID Principles
- **Open/Closed:** Prefer composition and strategy patterns over modifying existing stable classes.
- **Dependency Inversion:** Depend on interfaces, not concrete implementations, for anything that crosses a module boundary.
- **Interface Segregation:** Avoid "fat" interfaces; split by client need.

### Design Patterns
- Use established patterns (Builder, Factory, Strategy, Observer) where they clarify intent — not decoratively.
- Prefer immutability: favor `record` types, `final` fields, and unmodifiable collections for value objects.

---

## 2. Naming & Readability

- **Classes:** `PascalCase`, nouns (`InvoiceProcessor`, not `ProcessInvoice`).
- **Methods:** `camelCase`, verbs (`calculateTotal`, `findByEmail`).
- **Constants:** `UPPER_SNAKE_CASE`, declared `static final`.
- **Booleans:** Prefixed with `is`, `has`, `can`, `should` (`isActive`, `hasPermission`).
- Avoid abbreviations unless universally understood (`HTTP` yes, `invcProc` no).
- No Hungarian notation or type prefixes (`strName`, `iCount`).
- Variable names match scope — short names (`i`, `e`) are acceptable for short loops and lambdas; longer scopes demand descriptive names.

---

## 3. Error Handling

- Never catch `Exception` or `Throwable` unless at a top-level boundary (web handler, scheduled job, main thread).
- Never swallow exceptions silently. If a `catch` block is intentionally empty, add a comment explaining why.
- Throw specific exception types; prefer domain exceptions (`OrderNotFoundException`) over generic `RuntimeException`.
- Always preserve the cause when rethrowing: `throw new FooException("context", e)`.
- Prefer unchecked exceptions for programming errors and unrecoverable conditions; reserve checked exceptions for recoverable ones where the caller must act.
- Validate inputs at public API boundaries using `Objects.requireNonNull` or a validation library, not deep inside call stacks.

---

## 4. Null Safety

- Return `Optional<T>` from methods that may legitimately have no result; never return `null` from collections (return empty collections instead).
- Do not use `Optional` as a method parameter or a field.
- Annotate nullable types with `@Nullable` / `@NonNull` (JSpecify, JetBrains, or similar) consistently across the codebase.
- Use `Objects.requireNonNullElse` or `Optional.orElse` rather than ternaries on null checks.

---

## 5. Concurrency

- Prefer higher-level constructs (`ExecutorService`, `CompletableFuture`, virtual threads, `StructuredTaskScope`) over raw `Thread`.
- Document thread-safety assumptions on every shared class (`@ThreadSafe`, `@NotThreadSafe`, or Javadoc).
- Shared mutable state requires either synchronization, `java.util.concurrent` primitives, or immutability — pick one and apply it consistently.
- Never call `Thread.sleep` in production code paths; use scheduled executors.
- Close all `ExecutorService` instances; do not leak thread pools.
- Beware of `ThreadLocal` leaks in container environments — always `remove()` in `finally`.

---

## 6. Resource Management

- Use try-with-resources for anything implementing `AutoCloseable` (streams, connections, readers).
- Close resources in reverse order of acquisition when not using try-with-resources.
- Do not hold database connections, file handles, or HTTP clients longer than necessary.
- Connection pools and HTTP clients should be singletons, not created per request.

---

## 7. Collections & Streams

- Choose the right collection: `List` for order, `Set` for uniqueness, `Map` for lookup. Prefer interface types in declarations.
- Use `List.of()`, `Set.of()`, `Map.of()` for immutable constants.
- Streams are for data transformation pipelines — avoid side effects inside `map`/`filter`. Use a classic loop if logic is imperative.
- Don't use streams for single-element lookups; `findFirst()` on an in-memory list of 3 items is overkill.
- Avoid nested streams deeper than two levels; extract methods.
- Prefer `.toList()` (Java 16+) over `.collect(Collectors.toList())`.

---

## 8. Testing

### Coverage & Scope
- New business logic must have unit tests. Line coverage is a weak signal — focus on branch coverage and meaningful assertions.
- Every bug fix includes a regression test that fails before the fix and passes after.

### Test Quality
- Tests follow Arrange-Act-Assert (or Given-When-Then) and test one behavior each.
- Test names describe behavior: `shouldReturnEmptyListWhenUserHasNoOrders`, not `testGetOrders1`.
- No shared mutable state between tests; each test is independently runnable.
- Prefer real objects over mocks for simple value types; mock only at architectural boundaries (DB, HTTP, message queue).
- No `Thread.sleep` in tests — use awaitility or deterministic synchronization.
- Avoid asserting on exception messages unless the message is part of the contract.

### Integration Tests
- Use Testcontainers for databases and external services rather than H2 substitutes.
- Integration tests are clearly separated from unit tests (naming, source set, or tag).

---

## 9. Performance

- Don't optimize without measurement. Link to a profile or benchmark in the PR if the change is performance-motivated.
- Avoid `String` concatenation in loops; use `StringBuilder`.
- Don't autobox in hot paths (`Integer` vs `int` in tight loops matters).
- Lazy-initialize expensive resources; eagerly initialize cheap ones.
- Avoid reflection on hot paths.
- Be aware of N+1 query patterns in data access code.

---

## 10. Dependencies & Build

- New dependencies require justification in the PR. Prefer the standard library or existing dependencies.
- Pin dependency versions; no version ranges in production builds.
- Check for known CVEs (OWASP Dependency-Check, Snyk, or Dependabot) before merging.
- Keep `compileOnly`/`runtimeOnly`/`implementation` scopes correct in Gradle; don't leak implementation deps to consumers.
- Remove unused dependencies — they expand the attack surface and slow the build.

---

## 11. Security

- Never log secrets, tokens, passwords, PII, or full request/response bodies by default.
- Use parameterized queries or ORM parameter binding — never concatenate SQL.
- Validate and sanitize all external input; treat request bodies, headers, and query params as untrusted.
- Use `SecureRandom` for anything security-sensitive, not `Random`.
- Prefer standard crypto libraries (JCA, Bouncy Castle) over hand-rolled implementations.
- Do not disable TLS verification, even in development — use proper test certificates.
- Review access modifiers — avoid `public` for anything not intended as API.

---

## 12. Documentation

- Every public class and non-obvious public method has Javadoc covering purpose, parameters, return value, and thrown exceptions.
- Javadoc explains *why* and *when to use*, not just *what the code does*.
- Update Javadoc when signatures change — stale docs are worse than none.
- Complex algorithms include inline comments referencing the spec, paper, or ticket that motivated them.
- Package-level `package-info.java` describes the package's purpose and main entry points for non-trivial packages.

---

## 13. Logging

- Use SLF4J (or the project's chosen facade), never `System.out.println` or direct `Logger` implementations.
- Use parameterized logging: `log.info("user {} logged in", userId)`, not string concatenation.
- Log levels: `ERROR` for failures requiring action, `WARN` for recoverable issues, `INFO` for significant business events, `DEBUG` for developer diagnostics, `TRACE` for fine-grained tracing.
- Log exceptions with the throwable parameter: `log.error("failed to process", e)`, not `log.error("failed: " + e.getMessage())`.
- Include correlation IDs (request ID, trace ID) in MDC for distributed tracing.

---

## 14. Formatting & Style

- Follow the project's formatter configuration (Spotless, google-java-format, Checkstyle) — no manual formatting debates in review.
- Line length per project convention (typically 100 or 120).
- Imports organized and wildcard imports avoided unless configured otherwise.
- One top-level class per file; nested classes only when tightly coupled.
- Consistent use of `var` — typically preferred when the right-hand side makes the type obvious, avoided when it reduces readability.

---

## 15. Modern Java Features

Prefer modern idioms when the target JDK supports them:
- `record` for immutable data carriers.
- `sealed` interfaces/classes for closed type hierarchies with exhaustive pattern matching.
- Pattern matching in `switch` and `instanceof`.
- Text blocks (`"""`) for multi-line strings.
- Virtual threads for IO-bound concurrent workloads (JDK 21+).

---

## Pull Request Checklist

Before requesting review, the author confirms:

- [ ] Builds cleanly with no new warnings
- [ ] All tests pass locally, including integration tests
- [ ] New code has tests; bug fixes have regression tests
- [ ] Static analysis (SpotBugs, ErrorProne, SonarQube) passes with no new findings
- [ ] No `TODO` / `FIXME` without a linked ticket
- [ ] Public API changes are documented and versioned appropriately
- [ ] Migration notes included for breaking changes
- [ ] Security-sensitive changes flagged for additional review
- [ ] Performance-sensitive changes have benchmarks linked

---

## Review Etiquette

- Reviewers explain the *why* behind suggestions, not just the *what*.
- Distinguish **must-fix** (blocks merge) from **nit** (optional) and **question** (clarification needed).
- Prefer suggestions that include code — less ambiguity, faster iteration.
- Approve when the change is better than what's on `main`, not when it's perfect.
