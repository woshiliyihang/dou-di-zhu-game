package com.example.vtrans.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 把识别出来的连续文本切成"句子"喂给 NLLB。
 *
 * <p>为什么要切：NLLB-600M 对长句的质量下降明显，而且逐句翻译能让译文
 * 一句一句地跳出来，用户等第一句的时间短得多。
 */
public final class SentenceSplitter {

    /** 句子太长就按逗号再切一刀，避免 NLLB 吃掉后半段 */
    private static final int MAX_LEN = 40;
    /** 短于这个长度先攒着，等下一段识别结果来了再拼，避免"我""们"这种碎片进 MT */
    private static final int MIN_LEN = 2;

    private final StringBuilder pending = new StringBuilder();

    /** 追加新的识别结果，返回已经成句的部分（可能为 null） */
    public List<String> push(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        pending.append(text);
        return drain(false);
    }

    /** 一句话说完，把残留的部分也吐出来 */
    public List<String> flush() {
        return drain(true);
    }

    public void reset() {
        pending.setLength(0);
    }

    private List<String> drain(boolean force) {
        List<String> out = new ArrayList<>();
        String s = pending.toString();
        int lastCut = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isTerminator(c)) continue;
            String piece = s.substring(lastCut, i + 1).trim();
            if (!piece.isEmpty()) out.add(piece);
            lastCut = i + 1;
        }
        String rest = s.substring(lastCut);
        // 超长（无标点或一口气说了很长）：循环找逗号/空格断开，找不到就硬切，
        // 直到 rest 不超长——保证 flush() 收尾时绝不把整段超长文本丢给 NLLB。
        while (rest.length() > MAX_LEN) {
            int cut = -1;
            for (int i = MAX_LEN; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '，' || c == ',' || c == '、' || c == ' ') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < 0) cut = MAX_LEN;
            String piece = rest.substring(0, cut).trim();
            if (!piece.isEmpty()) out.add(piece);
            rest = rest.substring(cut);
        }
        pending.setLength(0);
        if (force) {
            String tail = rest.trim();
            if (!tail.isEmpty()) out.add(tail);
        } else {
            pending.append(rest);
        }
        return out.isEmpty() ? null : out;
    }

    /** 还没成句、也没攒够长度的残留文本长度，用来决定要不要等一等 */
    public int pendingLength() {
        return pending.length();
    }

    public boolean tooShort() {
        return pending.length() < MIN_LEN;
    }

    private static boolean isTerminator(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '.' || c == '!' || c == '?' || c == '\n';
    }
}
