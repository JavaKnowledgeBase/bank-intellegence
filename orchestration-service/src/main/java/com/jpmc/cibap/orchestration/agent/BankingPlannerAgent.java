package com.jpmc.cibap.orchestration.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface BankingPlannerAgent {

    @SystemMessage(fromResource = "prompts/system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream chat(@V("userMessage") String userMessage);
}
