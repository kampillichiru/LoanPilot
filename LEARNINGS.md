# LEARNINGS

Write 5 lines after each POC. This becomes your interview and blog material.

Template:
- **What I built:**
- **What I learned:**
- **What surprised me / what broke:**
- **Design decision I made and why:**
- **What I'd do differently in production:**

---

## POC 1: Talk to a local LLM from Java
- **What I built:** Spring Boot 4.1 + Spring AI 2.0 + Ollama (`qwen2.5:7b`) with `GET/POST /api/ask`. The system prompt is loaded from a versioned file (`prompts/banking-assistant-v1.st`), temperature and system prompt can be overridden per request, and every response returns model, prompt version, temperature, prompt/completion tokens and latency. A unit test with a fake `ChatModel` checks what Spring AI sends, with no Ollama needed.
- **What I learned:** Temperature reshapes token probabilities; it does not make output deterministic. The model still samples, so the same question at 0.2 gave two differently worded answers (172 prompt tokens both times, 79 vs 72 completion tokens). The system prompt is just more tokens in the context: it steers the model, it doesn't enforce anything.
- **What surprised me / what broke:** Asked "What is FOIR?", the model said "Fixed Operational Interest Rate" twice, confidently. That's wrong: it is Fixed Obligation to Income Ratio (total EMIs / net monthly income). My system prompt literally says "If you are not sure, say so. Do not invent...", and it invented anyway. Latency was 164 s on the first call (model loading into memory) and 47 s on the second (~1.5 tokens/s, likely CPU-only inference).
- **Design decision I made and why:** Prompts live in versioned files and the version is returned with every answer, so any output can be traced back to the exact prompt that produced it (audit rule 3). I also cut Spring AI's retries to 2 and mapped Ollama failures to clear 503/502 errors, so a missing model or a stopped Ollama fails fast instead of hanging for minutes.
- **What I'd do differently in production:** Never trust the model's own knowledge for domain facts: ground answers in our policy documents (RAG, POC 6) and keep all calculations in code (rule 1). Add an input hash and a persistent audit log for every call, run on a GPU or a hosted model with a latency SLA, and use evals to catch confident wrong answers like the FOIR one before users see them.
