package com.example.service.infrastructure.judge;

import java.util.Arrays;

/**
 * 输出比对：采用 OJ 通行的宽松比较——忽略行尾空白、忽略末尾空行、统一换行符，
 * 但行内与行间的实质差异（含行数不同）仍判为不通过。
 */
public final class JudgeOutputMatcher {

    private JudgeOutputMatcher() {
    }

    public static boolean matches(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    /** 归一化：CRLF/CR → LF，逐行去除行尾空白，去掉末尾空行。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n", -1);
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = stripTrailing(lines[i]);
        }
        while (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        return String.join("\n", Arrays.copyOf(lines, end));
    }

    private static String stripTrailing(String line) {
        int i = line.length();
        while (i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
            i--;
        }
        return line.substring(0, i);
    }
}
