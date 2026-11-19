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
 * used inside the body (status icon, heading, detail table, note). It matches the auth service emails: indigo header
 * with the skyline art, amber accent, a rounded detail card, callouts, and a dark mode.
 *
 * Pages are tables with inline styles because mail programs ignore most other CSS, and they load no images or
 * scripts. The page frame is templates/email/layout.html; the transaction emails keep their body in a file next to it.
 *
 * Values are escaped when they are put in. Only a placeholder whose name ends in Html is inserted as it is, and only
 * code in this service fills those. Placeholders are replaced in one pass, so a value that itself contains {{x}} is
 * never expanded.
 */
public final class EmailLayout {

    /** The colour of a status icon or note. */
    public enum Tone {
        INFO("i", "#EAE7FF", "#4A3FDB", "info", "info-t", "#EEEBFF", "#D9D4FA", "#4A3FDB", "#2F2A80"),
        SUCCESS("&#10003;", "#E3F6EA", "#1F8A4C", "ok", "ok-t", "#EAF8EF", "#BFE6CD", "#1F9D55", "#14532D"),
        WARNING("!", "#FFF1CC", "#B7791F", "amber", "amber-t", "#FFF7E0", "#F4DC9B", "#F2B544", "#6B4A00"),
        DANGER("&#10005;", "#FDE3E7", "#D92D4A", "red", "red-t", "#FFECEF", "#F6C3CC", "#D92D4A", "#7A1428");

        private final String glyph;
        private final String ringBackground;
        private final String ringText;
        private final String noteClass;
        private final String noteTextClass;
        private final String noteBackground;
        private final String noteBorder;
        private final String bar;
        private final String noteText;

        Tone(String glyph, String ringBackground, String ringText, String noteClass, String noteTextClass,
             String noteBackground, String noteBorder, String bar, String noteText) {
            this.glyph = glyph;
            this.ringBackground = ringBackground;
            this.ringText = ringText;
            this.noteClass = noteClass;
            this.noteTextClass = noteTextClass;
            this.noteBackground = noteBackground;
            this.noteBorder = noteBorder;
            this.bar = bar;
            this.noteText = noteText;
        }
    }

    /** Lets a long unbroken value, such as a reference or an email address, wrap instead of pushing the page wider. */
    private static final String WRAP = "overflow-wrap:anywhere;word-break:break-word;";
    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Helvetica,Arial,sans-serif";
    /** Shown in the footer of every email. Set at startup from SUPPORT_EMAIL, the same setting the auth service reads. */
    private static volatile String supportEmail = "thepeacemakerske@gmail.com";
    private static final String PATH = "templates/email/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private EmailLayout() {}

    /** Called once at startup by EmailSupportConfig. A blank value keeps the default. */
    public static void setSupportEmail(String value) {
        if (value != null && !value.isBlank()) supportEmail = value.trim();
    }

    // The page

    /** Puts a finished body inside the page frame. product is the small label under the name in the header. */
    public static String wrap(String product, String title, String preheader, String footnote, String contentHtml) {
        Map<String, String> v = new HashMap<>();
        v.put("product", product);
        v.put("title", title);
        v.put("preheader", preheader);
        v.put("footnote", footnote);
        v.put("supportEmail", supportEmail);
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

    /**
     * The round icon at the top of a body. The status text is kept as the tooltip and for screen readers, since the
     * heading already says what happened.
     */
    public static String pill(Tone tone, String text) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 20px;\"><tr>"
                + "<td class=\"ring\" width=\"58\" height=\"58\" align=\"center\" valign=\"middle\" title=\"" + esc(text)
                + "\" style=\"width:58px;height:58px;background-color:" + tone.ringBackground + ";border-radius:18px;\">"
                + "<span class=\"ring-t\" style=\"font-family:" + FONT + ";font-size:28px;line-height:58px;font-weight:700;color:"
                + tone.ringText + ";\">" + tone.glyph + "</span></td></tr></table>";
    }

    public static String heading(String text) {
        return "<h1 class=\"h1 ink\" style=\"margin:0 0 14px;font-family:" + FONT + ";font-size:29px;line-height:1.18;"
                + "font-weight:800;letter-spacing:-0.9px;color:#15123A;" + WRAP + "\">" + esc(text) + "</h1>";
    }

    public static String paragraph(String text) {
        return "<p class=\"txt\" style=\"margin:0 0 16px;font-size:15.5px;line-height:1.65;color:#3D3A63;\">" + esc(text) + "</p>";
    }

    public static String small(String text) {
        return "<p class=\"muted\" style=\"margin:0;font-size:12.5px;line-height:1.6;color:#7A77A3;\">" + esc(text) + "</p>";
    }

    public static String rule() {
        return "<div class=\"rule\" style=\"border-top:1px solid #E6E2F7;margin:8px 0 20px 0;line-height:1px;font-size:1px;\">&nbsp;</div>";
    }

    /** A rounded callout with a coloured bar on the left. title is shown in bold; lines, when given, are listed under it. */
    public static String note(Tone tone, String title, List<String> lines) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("<table role=\"presentation\" class=\"").append(tone.noteClass)
                .append("\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:6px 0 22px;background-color:")
                .append(tone.noteBackground).append(";border:1px solid ").append(tone.noteBorder).append(";border-radius:14px;\"><tr>")
                .append("<td width=\"5\" style=\"width:5px;background-color:").append(tone.bar)
                .append(";border-radius:14px 0 0 14px;font-size:0;line-height:0;\">&nbsp;</td>")
                .append("<td class=\"").append(tone.noteTextClass).append("\" style=\"padding:15px 18px;font-family:").append(FONT)
                .append(";font-size:14px;line-height:1.6;color:").append(tone.noteText).append(";").append(WRAP).append("\">");
        if (title != null && !title.isBlank()) sb.append("<strong>").append(esc(title)).append("</strong>");
        for (String line : lines) {
            sb.append("<div style=\"margin-top:6px;\">").append(esc(line)).append("</div>");
        }
        return sb.append("</td></tr></table>").toString();
    }

    /**
     * One row of a detail table: the label on the left in small capitals, the value on the right. Returns nothing when
     * the value is blank. last drops the line under the final row.
     */
    public static String detailRow(String label, String value, boolean last) {
        if (value == null || value.isBlank()) return "";
        String border = last ? "none" : "1px solid #E6E2F7";
        return "<tr><td class=\"muted rule\" width=\"36%\" style=\"padding:13px 18px;width:36%;vertical-align:top;border-bottom:" + border
                + ";font-family:" + FONT + ";font-size:11.5px;letter-spacing:0.6px;text-transform:uppercase;color:#7A77A3;\">"
                + esc(label) + "</td>"
                + "<td class=\"txt rule\" style=\"padding:13px 18px;vertical-align:top;border-bottom:" + border + ";font-family:" + FONT
                + ";font-size:14px;line-height:1.5;font-weight:600;color:#15123A;" + WRAP + "\">" + esc(value) + "</td></tr>";
    }

    /** The rounded card around detail rows. rowsHtml is the rows built with detailRow. */
    public static String detailTable(String rowsHtml) {
        return "<table role=\"presentation\" class=\"box\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"margin:22px 0;background-color:#F6F4FD;border:1px solid #E6E2F7;border-radius:14px;\">"
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