# Artifexa — Lovable Landing Page Prompt

---

## OVERVIEW

Build a single-page, scroll-driven landing page for **Artifexa** — an IntelliJ IDEA plugin that analyses Git branches, visualises code changes as an interactive component graph, and uses AI to review architectural quality and task completion. This page is an internal pitch to colleagues at a software company. The tone is compelling, clever, and a little playful — engineering culture, not enterprise sales.

---

## BRAND & NAME

The plugin is called **Artifexa**.

The name fuses *Artefact* + *Artifact* + *Artifex* (Latin: craftsman, master). The brand idea is **craftsmanship in code** — the idea that great software is made by skilled artisans who care deeply about the quality of their work. This identity must be woven visibly and meaningfully through the page: in the wordmark treatment, in copy, in visual metaphors.

**Visual identity direction:**
- Dark, rich background — think deep charcoal (`#0F0F14`) or near-black ink
- Gold/amber accent (`#C9922A` or `#E2A84B`) as the primary highlight colour — referencing master craftsman tools, illuminated manuscripts, gilded detail
- A secondary cool accent: electric teal (`#2DCFB3`) for graph/data moments
- Wordmark font: something with weight and character — a geometric serif or high-contrast display typeface. Suggestions: **Playfair Display**, **Cormorant Garamond Bold**, **DM Serif Display**, or **Fraunces**. The word "Artifexa" should feel carved, not printed.
- Body / UI font: **IBM Plex Mono** or **JetBrains Mono** — this is a developer tool; monospace body copy feels intentional and on-brand
- Motion: premium, purposeful. Staggered fade-ins on scroll. The component graph section should have animated SVG lines drawing themselves in. Subtle parallax on background elements.

---

## PAGE STRUCTURE

### 1. HERO SECTION

**Full viewport height.** Dark background with a very subtle animated noise/grain texture overlay (low opacity, for depth). A faint geometric grid or circuit-board pattern underneath at ~3% opacity.

**Left side (60%):**
- Small badge above the headline reading: `✦ INTELLIJ PLUGIN  ·  CURRENTLY IN DEVELOPMENT` in monospace, amber colour, small caps
- Main headline (large, display font):
  ```
  Your code tells
  a story.
  Artifexa reads it.
  ```
  The word **"Artifexa"** should be visually distinct — amber colour, slightly larger, maybe with a subtle glow or underline flourish
- Subheadline (monospace, muted white, ~18px):
  ```
  Visualise every change on your branch.
  Understand the architecture. Know if you're on track.
  ```
- Two CTA buttons:
  - Primary: `See How It Works  →` (amber, filled, slightly rounded)
  - Secondary: `View the Architecture  →` (ghost/outline, teal)
- Below CTAs, three small social proof chips in a row:
  ```
  ⬡ Git-native    ⬡ AI-powered    ⬡ Zero raw code sent to AI
  ```

**Right side (40%):**
- An animated mock of the component graph: SVG circles (nodes) connected by animated lines (edges) that draw in progressively. Nodes pulse softly. Some nodes glow amber (changed), some teal (affected), one red (deleted). This is the visual centrepiece. It should feel alive.

---

### 2. "THE PROBLEM" SECTION

**Headline:** `Code reviews are flying blind.`

Three-column layout, each column a sharp, honest problem statement. Stark design — white text on slightly lighter dark cards with a thin amber left border.

| Column 1 | Column 2 | Column 3 |
|----------|----------|----------|
| **You review the diff, not the architecture** | **You can't tell if the task is half-done** | **Layer violations hide in plain sight** |
| A PR shows you lines changed. It doesn't show you what broke the dependency boundary, what component was forgotten, or what ripple effects are coming. | Someone hands you a 40-file PR with no context. Is this the whole feature? A third of it? You have no idea. | That service that now imports a controller class. That domain entity with a Spring annotation it shouldn't have. Nobody notices until it's too late. |

Below the three columns, a thin amber horizontal rule and a single centred line in large display font:

> *"There's a better way to see your work."*

---

### 3. WHAT IS ARTIFEXA — FEATURE WALKTHROUGH

**Headline:** `Meet the craftsman's eye.`

This section uses a **sticky scroll / step-reveal** layout. As the user scrolls, a persistent right-side panel (mock IDE screenshot or animated graph) updates to reflect each step. Left side has the steps listed vertically.

**Step 1 — Reads Your Branch**
> Artifexa detects your branch point automatically, walks every commit since it diverged, and builds a complete picture of what changed — down to the method level. Not just files. *Methods.*
> 
> Icon: a git branch SVG

**Step 2 — Draws the Map**
> Every changed class becomes a node. Every relationship — calls, inheritance, injection — becomes an edge. You see your architecture as it actually is right now, not as you hope it is.
>
> Icon: animated graph nodes connecting

**Step 3 — Spots What's Wrong**
> Layer violations. Missing test coverage. High coupling. Artifexa flags architectural problems before they become technical debt. Colour-coded. Linked directly to the source.
>
> Icon: a warning/inspection icon

**Step 4 — Judges Your Progress**
> Paste your Jira ticket. Describe the task. Artifexa's AI tells you how much is done, what's missing, and whether the implementation is heading in the right direction.
>
> Icon: a progress/completion icon with percentage

---

### 4. THE COMPONENT GRAPH — VISUAL SHOWCASE

Full-width dark section. This is the **"wow" moment**.

**Headline:** `Your branch, visualised.`

A large, interactive-looking mock graph (static SVG or CSS animation is fine). The graph has:
- ~12–15 nodes of varying sizes
- Colour-coded: amber nodes = directly changed, teal = affected neighbour, red outline = layer violation, green = test file
- Edge types visually distinct (solid, dashed, dotted)
- Floating tooltip on one node showing: `UserService  ·  SERVICE LAYER  ·  +47 / -12 lines  ·  3 callers`

Below the graph, three small legend items in a row showing the node/edge colour key.

Below that, a single line: `Every node is clickable. Opens straight to the source.`

---

### 5. AI REVIEW PANEL — MOCKUP

Split layout. Left = description. Right = a styled mock of the AI review output panel.

**Left side headline:** `The senior architect who never sleeps.`

Copy:
> Artifexa's AI review doesn't just check rules. It understands context. Give it your task description and it tells you: what's architecturally sound, what needs a rethink, and honestly — how much of the work is actually done.
>
> It's the code review you wish you were getting.

**Right side — mock review panel** (styled like an IDE panel, dark bg, monospace font):

```
  ARTIFEXA REVIEW  ─────────────────────────────

  ✦ Architecture Assessment
    UserService has grown into a God Object.
    Consider splitting order-handling into
    OrderProcessingService.          [View →]

  ⚠ Best Practices
    HIGH  Missing null-check on userId param
          in UserController.getUser()   [View →]
    MED   Method length exceeds 40 lines
          in InvoiceBuilder.generate()  [View →]

  ◎ Task Completion
    ████████████░░░  73% complete

    ✓ User authentication flow updated
    ✓ Service layer refactored
    ? Email notification module not touched
    ? Integration tests not updated

  ─────────────────────────────────────────────
```

This mock should look real. Use a card with slight inner shadow, monospace font, and the amber/teal colour scheme for statuses.

---

### 6. HOW IT WORKS — TECHNICAL TRUST SECTION

Aimed at sceptical engineers. Clean, minimal.

**Headline:** `Built for how engineers actually work.`

Four feature chips in a 2×2 grid, each with an icon, title, and two-sentence description:

- **🔒 Your code stays yours** — Artifexa sends only structured summaries to the AI: component names, metrics, relationships. Raw source code never leaves your machine.
- **⚡ Fast by design** — Graph construction for 200 changed files in under 10 seconds. Background threads only — the IDE stays responsive.
- **🔌 Plugs into IntelliJ** — Native Tool Window. Installs from JetBrains Marketplace. Works with any Java or Kotlin project.
- **🧠 Bring your own AI** — OpenAI, Anthropic Claude, or a self-hosted endpoint. You choose the model. You own the key.

---

### 7. THE CRAFTSMANSHIP SECTION

**Full-width, visually rich section.** This is the brand soul of the page.

Dark background with a faint illuminated-manuscript-style decorative border motif (SVG, very subtle, gold at ~15% opacity).

**Large centred headline in display font:**

> *"Artifex"* — Latin. *noun.* A master craftsman. One who makes with skill and intention.

Smaller text below:

> Great software isn't just correct. It has structure. Integrity. Craft. Artifexa is named for the engineers who care about *how* things are built, not just *whether* they work.
>
> It's a tool made by craftsmen, for craftsmen.

Below this, the **Artifexa wordmark** displayed large — amber, display font — with a thin decorative rule above and below it. This is the most prominent brand moment on the page.

---

### 8. CLOSING CTA SECTION

Clean, centred.

**Headline:** `Ready to see your architecture clearly?`

Subtext: `Artifexa is in development. Be among the first to try it.`

Email input + button: `[ your@email.com                ] [ Get Early Access ]`

Below that in small muted monospace: `No spam. No fluff. Just a ping when it's ready.`

Three small trust chips below: `✦ IntelliJ Native   ✦ Java & Kotlin   ✦ AI-powered review`

---

### 9. FOOTER

Minimal. Dark. Three columns:

- Left: **Artifexa** wordmark (small), tagline: *"Code with craft."*
- Centre: Links — `Overview · Features · Architecture · Early Access`
- Right: `Built with ♦ for engineers who care.`

Thin top border in amber at 20% opacity.

---

## ANIMATION & INTERACTION REQUIREMENTS

- **Page load:** Staggered fade-up on hero text (wordmark first, then headline words one by one, then subheadline, then CTAs)
- **Hero graph:** SVG lines draw in progressively over 2 seconds after page load. Nodes fade in with a soft pulse.
- **Scroll reveals:** Each section's headline and content fades up with a slight Y-translate on first scroll into view. Use Intersection Observer.
- **Step walkthrough (Section 3):** Right-side panel content cross-fades as the user scrolls through each step.
- **AI panel mock (Section 5):** Progress bar animates to 73% when scrolled into view. Task completion items appear one by one with a short stagger.
- **Wordmark in Section 7:** Subtle shimmer/glint animation on the gold letters, like light catching engraved metal. CSS only if possible.
- **Hover states:** CTA buttons have a soft glow effect on hover. Graph nodes in Section 4 scale up 1.05× on hover with a tooltip appearing.
- **Custom cursor:** On desktop, replace default cursor with a small amber diamond `◆` that trails slightly. Subtle, not distracting.

---

## COPY TONE GUIDE

- **Voice:** Smart, direct, a little dry. Like a senior engineer who also happens to be a great writer.
- **Not:** Corporate, salesy, hyperbolic, full of buzzwords.
- **Do:** Use short punchy sentences. Break rules occasionally for effect. Use technical language confidently but never alienatingly.
- **The name "Artifexa"** should appear at least 6–8 times on the page, always in a way that feels natural and purposeful. Never just "the plugin."

---

## TECHNICAL REQUIREMENTS

- **Framework:** React with Tailwind CSS (or plain HTML/CSS/JS if simpler for Lovable)
- **Fonts:** Load from Google Fonts — Fraunces (display/wordmark), IBM Plex Mono (body/code/UI elements)
- **Icons:** Lucide React or inline SVGs
- **No external component libraries** except what Lovable provides natively
- **Responsive:** Must work on desktop (primary) and tablet. Mobile is secondary but should not break.
- **Performance:** Animations should use `will-change`, `transform`, and `opacity` only — no layout-thrashing properties.
- **Accessibility:** All text must pass WCAG AA contrast against its background. Animations must respect `prefers-reduced-motion`.

---

## COLOUR TOKENS (use as CSS variables)

```css
--color-bg:           #0F0F14;
--color-surface:      #16161E;
--color-surface-2:    #1E1E2A;
--color-border:       #2A2A3A;
--color-text:         #E8E8F0;
--color-text-muted:   #7A7A9A;
--color-amber:        #E2A84B;
--color-amber-dim:    #C9922A;
--color-amber-glow:   rgba(226, 168, 75, 0.15);
--color-teal:         #2DCFB3;
--color-teal-dim:     #1FA899;
--color-red:          #EF5B5B;
--color-white:        #FFFFFF;
```

---

## WHAT SUCCESS LOOKS LIKE

A colleague opens this page and within 10 seconds they understand: *"This is a plugin that shows me my code changes as a visual graph and uses AI to review them."* Within 60 seconds they think: *"This is beautifully made and I want to try it."* Within 2 minutes they feel: *"The people building this care deeply about quality — which is exactly the right vibe for a tool about code quality."*

The craftsmanship of the landing page should mirror the craftsmanship of the product itself. Every detail matters.

---

*End of prompt. Feed this entire document to Lovable as the build instruction.*
