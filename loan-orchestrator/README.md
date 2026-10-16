# loan-orchestrator: POC 1: Talk to a local LLM from Java

## What's here

| File | Purpose |
|------|---------|
| `ask/AskController.java` | REST endpoint `GET/POST /api/ask` |
| `ask/AskService.java` | Calls the LLM through Spring AI `ChatClient`; returns answer + model, tokens, latency |
| `resources/prompts/banking-assistant-v1.st` | **Versioned system prompt**: the model's instructions |
| `resources/application.properties` | Ollama URL, model, default temperature |
| `ask/LlmErrorHandler.java` | Clear 503 when Ollama isn't running |
| `test/.../AskServiceTest.java` | Unit test with a fake model (no Ollama needed) |
| `requests.http` | Ready-made requests and experiments |

## Request flow

```
curl / browser ──▶ AskController ──▶ AskService ──▶ ChatClient (Spring AI)
                                                       │  system prompt + user question
                                                       │  + options (temperature)
                                                       ▼
                                                 Ollama :11434  (qwen2.5:7b)
                                                       │
   JSON { answer, model, promptVersion, tokens, latencyMs } ◀──┘
```

## Run it

1. Install Ollama: https://ollama.com/download, then in a new terminal:
   ```
   ollama pull qwen2.5:7b
   ollama run qwen2.5:7b "What is FOIR?"
   ```
2. Run the tests (no Ollama needed):
   ```
   mvnw.cmd test
   ```
3. Start the app:
   ```
   mvnw.cmd spring-boot:run
   ```
   (or run `LoanOrchestratorApplication` from IntelliJ)
4. Try it:
   ```
   curl "http://localhost:8080/api/ask?q=What%20is%20FOIR"
   ```
   Or open `requests.http` in IntelliJ and run the experiments.

## Experiments (this is where the learning is)

1. **Temperature:** run request #3 three times, then #4 three times. What changes?
2. **System prompt:** compare #5 and #6. What does the system prompt actually do?
3. **Maths:** check #2 by hand (answer should be ₹16,607). Is the LLM reliable at calculations?
   → this is why POC 3 adds a Java `calculateEmi` **tool**.
4. **Tokens & latency:** note `promptTokens`, `completionTokens`, `latencyMs`. How does a longer
   system prompt change them?
5. **Prompt versioning:** copy the prompt to `banking-assistant-v2.st`, change it, switch
   `loanpilot.prompt.system` / `loanpilot.prompt.version`, and compare answers.

Write what you found in `../LEARNINGS.md`.
