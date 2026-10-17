package com.loanpilot.bureau;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import static org.springframework.util.StringUtils.hasText;

/**
 * POC 4: the tools this MCP server publishes. Any @McpTool method on a Spring bean is published automatically:
 * clients see it in tools/list (name, description, JSON schema) and run it with tools/call.
 *
 * Design choices:
 * - Lookup by applicant id or name, never by PAN (rule 2). In POC 5 an LLM fills these arguments, so a PAN
 *   parameter would force the LLM to see and repeat the PAN. The PAN stays inside the bureau.
 * - Returns CreditReport, not BureauRecord: no PAN, no name, no verdict (rules 1 and 2).
 * - Missing data is a status, not an error: NOT_FOUND vs NO_CREDIT_HISTORY are different facts for a lender.
 * - readOnly/idempotent hints tell clients this tool changes nothing, so it's safe to call or retry.
 * - POC 5: a calling SERVICE can pass "applicantId" and "requestId" in the MCP request's _meta. _meta is set by
 *   the client's code, not by its LLM, so a meta applicantId wins over the arguments the LLM filled in (same
 *   idea as ToolContext in POC 3). requestId lets you follow one question across both services' logs.
 *   Caveat: with no auth, any client can set _meta. Production would only trust it from authenticated services.
 *
 * Logging: INFO shows the lookup source, outcome and the caller's requestId, never the name or id values.
 */
@Component
public class CreditBureauTools {

    private static final Logger log = LoggerFactory.getLogger(CreditBureauTools.class);

    private final BureauRepository bureau;

    public CreditBureauTools(BureauRepository bureau) {
        this.bureau = bureau;
    }

    @McpTool(name = "getCreditReport",
            title = "Get credit report",
            description = """
                    Returns an applicant's credit report from the credit bureau (synthetic data): CIBIL score \
                    (300-900), active accounts, total existing EMI per month in rupees, accounts 90+ days past due \
                    in the last 24 months, and credit enquiries in the last 6 months. Use it for any question about \
                    an applicant's credit score, credit history, existing loans or creditworthiness. Give \
                    applicantId if known, otherwise applicantName exactly as written in the question. Never guess \
                    or invent a name or id: if the question names no applicant, leave both empty (the calling \
                    system may already know who it is). The report contains facts only: it does not say \
                    whether a loan should be approved. If status is NOT_FOUND or NO_CREDIT_HISTORY, say so; never \
                    estimate a score.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false,
                    openWorldHint = false))
    public CreditReport getCreditReport(
            @McpToolParam(required = false, description = "The bank's applicant id, e.g. APP-001. Preferred when known")
            String applicantId,
            @McpToolParam(required = false, description = "Applicant's first or full name, e.g. Priya or Priya Sharma")
            String applicantName,
            McpMeta meta) {   // not part of the tool's JSON schema: clients' LLMs never see or fill it
        String trustedId = metaString(meta, "applicantId");

        // Most trusted first: an id set by the calling service's code, then an id the LLM passed, then a name.
        Optional<BureauRecord> found;
        String source;
        if (hasText(trustedId)) {
            found = bureau.findByApplicantId(trustedId);
            source = "meta id";
        } else if (hasText(applicantId)) {
            found = bureau.findByApplicantId(applicantId);
            source = "id";
        } else if (hasText(applicantName)) {
            found = bureau.findByName(applicantName);
            source = "name";
        } else {
            found = Optional.empty();
            source = "nothing";
        }

        CreditReport report = found.map(CreditReport::of).orElseGet(CreditReport::notFound);
        log.info("[request {}] getCreditReport by {}: {}", metaString(meta, "requestId"), source, report.status());
        return report;
    }

    private static String metaString(McpMeta meta, String key) {
        return meta != null && meta.get(key) instanceof String value ? value : null;
    }
}
