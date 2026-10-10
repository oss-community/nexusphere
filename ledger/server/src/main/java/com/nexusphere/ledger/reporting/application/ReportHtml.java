package com.nexusphere.ledger.reporting.application;

import java.util.List;
import java.util.Map;

public final class ReportHtml {

    private static final String STYLE = """
            body{font:15px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#1b1f24}
            h1{font-size:1.6rem;margin-bottom:.2rem}h2{font-size:1.15rem;margin:1.6rem 0 .3rem}
            table{border-collapse:collapse;width:100%}td,th{border:1px solid #d0d7de;padding:.35rem .5rem;
            text-align:left;vertical-align:top}th{background:#f6f8fa;width:13rem}
            .notice{background:#fff8c5;border:1px solid #d4a72c;padding:.6rem .8rem}
            .SHOWN{color:#1a7f37}.PARTLY_SHOWN{color:#9a6700}.NOT_SHOWN{color:#cf222e}
            .status{font-weight:600}.ref{color:#57606a}code{font-size:.85em;word-break:break-all}
            @media print{body{margin:0}}""";

    private ReportHtml() {
    }

    @SuppressWarnings("unchecked")
    public static String render(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> subject = (Map<String, Object>) report.get("subject");
        String heading = subject == null ? text(report.get("title")) : text(subject.get("title"));
        out.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
                .append(escape(heading)).append("</title><style>").append(STYLE).append("</style></head><body>");
        out.append("<h1>").append(escape(heading)).append("</h1>");
        out.append("<p class=\"ref\">").append(escape(text(report.get("title")))).append(", created ")
                .append(escape(text(report.get("createdAt")))).append("</p>");
        if (subject != null && subject.get("description") != null) {
            out.append("<p>").append(escape(text(subject.get("description")))).append("</p>");
        }
        out.append("<p class=\"notice\">").append(escape(text(report.get("notice")))).append("</p>");
        Map<String, Object> scope = (Map<String, Object>) report.get("scope");
        Map<String, Object> evidence = (Map<String, Object>) report.get("evidence");
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) report.get("profiles");
        out.append("<h2>Evidence</h2><table>");
        row(out, "Agent", scope.get("agentId") == null ? "all agents" : text(scope.get("agentId")));
        row(out, "Principal", scope.get("principalId") == null ? "all principals" : text(scope.get("principalId")));
        row(out, "Sequences", scope.get("fromSequence") + " to " + scope.get("toSequence"));
        row(out, "Entries", text(evidence.get("entries")));
        row(out, "Checkpoint", evidence.get("checkpoint") + ", head " + evidence.get("headHash"));
        row(out, "Package verified", Boolean.TRUE.equals(evidence.get("valid")) ? "yes" : "no");
        row(out, "Compliance profiles", String.join(", ", profiles.stream()
                .map(p -> p.get("id") + " (" + text(p.get("digest")).substring(0, 12) + ")").toList()));
        row(out, "Signed by key", text(report.get("keyId")));
        out.append("</table>");
        for (Map<String, Object> section : (List<Map<String, Object>>) report.get("sections")) {
            String status = text(section.get("status"));
            out.append("<h2>").append(escape(text(section.get("requirement")))).append("</h2>");
            out.append("<p><span class=\"ref\">").append(escape(text(section.get("reference"))))
                    .append("</span> &middot; <span class=\"status ").append(escape(status)).append("\">")
                    .append(escape(status.replace('_', ' ').toLowerCase())).append("</span></p><ul>");
            for (Object finding : (List<Object>) section.get("findings")) {
                out.append("<li>").append(escape(text(finding))).append("</li>");
            }
            out.append("</ul>");
        }
        out.append("<h2>Checking this report</h2><p>The report is signed by the ledger and names the checkpoint and")
                .append(" entries of its evidence package. Check both with <code>nexusphere-ledger-verify report")
                .append(" --package package.json report.json</code>, or a whole dispute package with")
                .append(" <code>nexusphere-ledger-verify dispute dispute.zip</code>.</p>");
        out.append("<p class=\"ref\">Signature <code>").append(escape(text(report.get("signature"))))
                .append("</code></p></body></html>");
        return out.toString();
    }

    private static void row(StringBuilder out, String name, String value) {
        out.append("<tr><th>").append(escape(name)).append("</th><td>").append(escape(value)).append("</td></tr>");
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
