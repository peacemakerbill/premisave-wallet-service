package com.premisave.wallet.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The look shared by every email the wallet service sends: the header, the accent line, the footer and the pieces
 * used inside the body (status label, heading, detail table, note). It matches the developer service emails.
 *
 * Pages are tables with inline styles because mail programs ignore most other CSS, and they load no images or
 * scripts. The page frame is templates/email/layout.html; the transaction emails keep their body in a file next to it.
 *
 * Values are escaped when they are put in. Only a placeholder whose name ends in Html is inserted as it is, and only
 * code in this service fills those. Placeholders are replaced in one pass, so a value that itself contains {{x}} is
 * never expanded.
 */
public final class EmailLayout {

    /** The colour of a status label or note. */
    public enum Tone {
        INFO("#e6efff", "#1d4fd8", "#1d6bff", "#f3f6fb"),
        SUCCESS("#dcf5e4", "#176036", "#1f9d55", "#eefaf2"),
        WARNING("#fff1d6", "#8a5200", "#e08a00", "#fff7e8"),
        DANGER("#fde4e4", "#9b1c1c", "#d64545", "#fff0f0");

        private final String pillBackground;
        private final String pillText;
        private final String bar;
        private final String noteBackground;

        Tone(String pillBackground, String pillText, String bar, String noteBackground) {
            this.pillBackground = pillBackground;
            this.pillText = pillText;
            this.bar = bar;
            this.noteBackground = noteBackground;
        }
    }

    private static final String FONT = "'Segoe UI',Roboto,'Helvetica Neue',Helvetica,Arial,sans-serif";
    private static final String PATH = "templates/email/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private EmailLayout() {}

    // The page

    /** Puts a finished body inside the page frame. product is the small label under the name in the header. */
    public static String wrap(String product, String title, String preheader, String footnote, String contentHtml) {
        Map<String, String> v = new HashMap<>();
        v.put("product", product);
        v.put("title", title);
        v.put("preheader", preheader);
        v.put("footnote", footnote);
        v.put("year", String.valueOf(Year.now(ZoneOffset.UTC).getValue()));
        v.put("contentHtml", contentHtml);
        return fill(template("layout"), v);
    }

    /** One pass over the template. Unknown placeholders become empty text. */
    public static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            String text = value == null ? "" : key.endsWith("Html") ? value : esc(value);
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String template(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            String path = PATH + n + ".html";
            try (InputStream in = EmailLayout.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Email template missing from the build: " + path);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the email template " + path, e);
            }
        });
    }

    // Pieces of a body. Each one escapes what it is given.

    public static String pill(Tone tone, String text) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 18px 0;\"><tr>"
                + "<td style=\"background-color:" + tone.pillBackground + ";color:" + tone.pillText + ";border-radius:999px;"
                + "padding:5px 12px;font-family:" + FONT + ";font-size:11px;font-weight:700;letter-spacing:1.2px;"
                + "text-transform:uppercase;\">" + esc(text) + "</td></tr></table>";
    }

    public static String heading(String text) {
        return "<h1 class=\"h1 txt\" style=\"margin:0 0 20px 0;font-family:" + FONT + ";font-size:28px;line-height:1.25;"
                + "font-weight:700;letter-spacing:-0.3px;color:#0b1f3a;\">" + esc(text) + "</h1>";
    }

    public static String paragraph(String text) {
        return "<p class=\"txt\" style=\"margin:0 0 20px 0;\">" + esc(text) + "</p>";
    }

    public static String small(String text) {
        return "<p class=\"muted\" style=\"margin:0;font-size:13px;line-height:1.6;color:#6b7686;\">" + esc(text) + "</p>";
    }

    public static String rule() {
        return "<div class=\"rule\" style=\"border-top:1px solid #dfe4ec;margin:8px 0 20px 0;line-height:1px;font-size:1px;\">&nbsp;</div>";
    }

    /** A box with a coloured bar on the left. title is shown in bold; lines, when given, are listed under it. */
    public static String note(Tone tone, String title, List<String> lines) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 24px 0;\"><tr>")
                .append("<td class=\"box txt\" style=\"background-color:").append(tone.noteBackground).append(";border-left:4px solid ")
                .append(tone.bar).append(";border-radius:6px;padding:14px 16px;font-family:").append(FONT)
                .append(";font-size:14px;line-height:1.6;color:#1b2430;\">");
        if (title != null && !title.isBlank()) sb.append("<strong>").append(esc(title)).append("</strong>");
        for (String line : lines) {
            sb.append("<div style=\"margin-top:6px;\">").append(esc(line)).append("</div>");
        }
        return sb.append("</td></tr></table>").toString();
    }

    /** One label and value row of a detail table. Returns nothing when the value is blank. */
    public static String detailRow(String label, String value, boolean last) {
        if (value == null || value.isBlank()) return "";
        String border = last ? "" : "border-bottom:1px solid #dfe4ec;";
        return "<tr><td class=\"muted rule\" width=\"150\" valign=\"top\" style=\"padding:12px 16px;" + border + "font-family:" + FONT
                + ";font-size:12px;letter-spacing:1px;text-transform:uppercase;color:#6b7686;\">" + esc(label) + "</td>"
                + "<td class=\"txt rule\" valign=\"top\" style=\"padding:12px 16px;" + border + "font-family:" + FONT
                + ";font-size:15px;font-weight:600;color:#1b2430;word-break:break-word;\">" + esc(value) + "</td></tr>";
    }

    /** The rounded box around detail rows. rowsHtml is the rows built with detailRow. */
    public static String detailTable(String rowsHtml) {
        return "<table role=\"presentation\" class=\"box\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"margin:0 0 24px 0;background-color:#f6f8fb;border:1px solid #dfe4ec;border-radius:10px;\">"
                + rowsHtml + "</table>";
    }

    /**
     * A finished detail table from label and value pairs, skipping any pair whose value is blank. The last row that is
     * shown carries no bottom line. Returns an empty string when nothing is left to show.
     */
    public static String details(String... labelValuePairs) {
        List<String[]> shown = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            String value = labelValuePairs[i + 1];
            if (value != null && !value.isBlank()) shown.add(new String[] {labelValuePairs[i], value});
        }
        if (shown.isEmpty()) return "";
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            rows.append(detailRow(shown.get(i)[0], shown.get(i)[1], i == shown.size() - 1));
        }
        return detailTable(rows.toString());
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
