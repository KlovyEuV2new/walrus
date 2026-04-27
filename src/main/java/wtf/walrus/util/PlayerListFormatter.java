/*
 * This file is part of MLSAC - AI powered Anti-Cheat
 * Copyright (C) 2026 MLSAC Team
 *
 * Licensed under GPL-3.0
 */

package wtf.walrus.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerListFormatter {

    public static final String FALLBACK_EMPTY = "нет";

    private static final Pattern FORMAT_PATTERN =
            Pattern.compile("\\{players_format_(.*?)_(\\d+)_(.*?)_(\\d+)_(.+?)\\}");

    private static final Pattern SLOT_PATTERN =
            Pattern.compile("%(\\d+)%");

    private PlayerListFormatter() {}

    public static List<String> resolve(List<String> format, List<String> players) {
        List<String> result = new ArrayList<>();

        for (String line : format) {
            Matcher m = FORMAT_PATTERN.matcher(line);

            if (!m.find()) {
                result.add(line);
                continue;
            }

            String prefix1 = m.group(1);
            int    n1      = Math.max(1, Integer.parseInt(m.group(2)));
            String prefixN = m.group(3);
            int    nN      = Math.max(1, Integer.parseInt(m.group(4)));
            String tpl     = m.group(5);

            if (players.isEmpty()) {
                result.add(prefix1 + FALLBACK_EMPTY);
                continue;
            }

            result.addAll(renderLines(prefix1, n1, prefixN, nN, tpl, players));
        }

        return result;
    }

    private static List<String> renderLines(String prefix1, int n1,
                                            String prefixN, int nN,
                                            String template,
                                            List<String> players) {
        List<String> lines = new ArrayList<>();
        boolean      first = true;

        int i = 0;
        while (i < players.size()) {
            String prefix;
            int    n;

            if (first) {
                prefix = prefix1;
                n      = n1;
                first  = false;
            } else {
                prefix = prefixN;
                n      = nN;
            }

            int      end       = Math.min(i + n, players.size());
            int      chunkSize = end - i;
            String[] names     = players.subList(i, end).toArray(new String[0]);

            lines.add(prefix + fillAndTrim(template, names, chunkSize));
            i = end;
        }

        return lines;
    }

    private static String fillAndTrim(String template, String[] names, int chunkSize) {
        StringBuffer sb          = new StringBuffer();
        Matcher      m           = SLOT_PATTERN.matcher(template);
        int          lastFillEnd = -1;

        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));

            if (idx < chunkSize) {
                m.appendReplacement(sb, Matcher.quoteReplacement(names[idx]));
                lastFillEnd = sb.length();
            } else {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);

        if (lastFillEnd < 0) return FALLBACK_EMPTY;
        return sb.substring(0, lastFillEnd);
    }
}