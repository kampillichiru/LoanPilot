package com.loanpilot.orchestrator.guard;

import com.loanpilot.orchestrator.audit.AuditLog;
import com.loanpilot.orchestrator.privacy.PiiMasker;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts the guard on every ChatClient in the app. Spring AI applies ChatClientCustomizer beans to each
 * ChatClient.Builder it hands out, so a service can't forget the guard, and a new service gets it automatically.
 * A cross-cutting concern lives in one place, not copied into each caller.
 */
@Configuration
class GuardConfig {

    @Bean
    LlmGuardAdvisor llmGuardAdvisor(PiiMasker masker, AuditLog auditLog,
                                    @Value("${spring.ai.ollama.chat.model}") String defaultModel) {
        return new LlmGuardAdvisor(masker, auditLog, defaultModel);
    }

    @Bean
    ChatClientCustomizer llmGuardOnEveryChatClient(LlmGuardAdvisor guard) {
        return builder -> builder.defaultAdvisors(guard);
    }
}
