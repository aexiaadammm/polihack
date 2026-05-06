package org.example.service;

import org.springframework.stereotype.Service;

@Service
public class DemoFollowUpAgent {

    public String suggestNextStep(String analysisSummary) {
        if (analysisSummary == null || analysisSummary.isBlank()) {
            return "Ask the developer for missing workflow context before generating the orchestrator.";
        }

        return "Review the latest analysis, then decide whether the existing orchestrator needs regeneration.";
    }
}
