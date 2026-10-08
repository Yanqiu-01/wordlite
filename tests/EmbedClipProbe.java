package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/** temporary diagnostic probe (not a suite; deleted before hand-off). */
public final class EmbedClipProbe {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tests/corpus/real-prose.txt";
        String all = new String(Files.readAllBytes(Paths.get(path)), UTF8);
        ArrayList<String> paragraphs = new ArrayList<String>();
        for (String line : all.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < 120) continue;
            if (TextCorpus.citationLike(trimmed)) continue;
            paragraphs.add(trimmed);
        }
        ArrayList<String> copies = new ArrayList<String>();
        ArrayList<String> fillers = new ArrayList<String>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i % 2 == 0) copies.add(paragraphs.get(i)); else fillers.add(paragraphs.get(i));
        }
        TextCorpus corpus = new TextCorpus();
        for (int i = 0; i < copies.size(); i++) {
            TextCorpus.Source source = new TextCorpus.Source();
            source.id = "PAPER-" + i;
            source.title = "Paper" + i;
            source.engine = "local";
            corpus.add(source, copies.get(i));
        }
        StringBuilder draft = new StringBuilder();
        ArrayList<int[]> phrases = new ArrayList<int[]>();
        ArrayList<int[]> sentences = new ArrayList<int[]>();
        ArrayList<String> quotes = new ArrayList<String>();
        for (int i = 0; i < copies.size(); i++) {
            String shortOne = pick(copies.get(i), true);
            String host = pick(fillers.get(i % fillers.size()), false);
            if (shortOne == null || host == null) continue;
            String body = stripTail(host);
            int comma = body.indexOf((int) '，');
            if (comma < 4) continue;
            int from = draft.length();
            draft.append(body, 0, comma).append('，').append(stripTail(shortOne)).append('，')
                    .append(body.substring(comma + 1));
            int end = draft.length();
            draft.append('。').append('\n');
            int phraseStart = from + comma + 1 + 1;
            phrases.add(new int[] { phraseStart, phraseStart + stripTail(shortOne).length() });
            sentences.add(new int[] { from, end });
            quotes.add(stripTail(shortOne));
        }
        String text = draft.toString();
        String norm = TextCorpus.normalize(text);
        ArrayList<TextCorpus.Hit> hits = corpus.match(text, null).hits;
        System.out.println("sentences=" + sentences.size() + " hits=" + hits.size());
        for (int i = 0; i < sentences.size(); i++) {
            int[] phrase = phrases.get(i), sent = sentences.get(i);
            int sentValid = TextCorpus.validCount(norm, sent[0], sent[1]);
            int phraseValid = TextCorpus.validCount(norm, phrase[0], phrase[1]);
            int outside = 0, inside = 0;
            for (TextCorpus.Hit h : hits) {
                if (h.end <= sent[0] || h.start >= sent[1]) continue;
                outside += TextCorpus.validCount(norm, Math.max(sent[0], h.start), Math.min(sent[1], h.end));
                inside -= TextCorpus.validCount(norm, Math.max(phrase[0], h.start), Math.min(phrase[1], h.end));
            }
            outside += inside; inside = -inside;
            System.out.println("SENT " + i + " sent=[" + sent[0] + "," + sent[1] + ") valid=" + sentValid
                    + " phrase=[" + phrase[0] + "," + phrase[1] + ") valid=" + phraseValid
                    + " flaggedIn=" + inside + " flaggedOut=" + outside);
            System.out.println("   HOST   " + text.substring(sent[0], Math.min(sent[1], sent[0] + 46)));
            System.out.println("   PHRASE " + text.substring(phrase[0], phrase[1]));
            String quote = quotes.get(i);
            int trueStart = text.indexOf(quote, sent[0]);
            System.out.println("   QUOTE  len=" + quote.length() + " recorded=[" + phrase[0] + "," + phrase[1]
                    + ") found=[" + trueStart + "," + (trueStart + quote.length()) + ") shift=" + (trueStart - phrase[0])
                    + " before=[" + text.substring(sent[0], trueStart) + "]");
            for (TextCorpus.Hit h : hits) {
                if (h.end <= sent[0] || h.start >= sent[1]) continue;
                System.out.println("   HIT    [" + h.start + "," + h.end + ") score=" + h.score
                        + " src=" + (h.source == null ? "-" : h.source.id) + " txt="
                        + text.substring(Math.max(sent[0], h.start), Math.min(sent[1], h.end)));
            }
        }
    }

    private static String pick(String paragraph, boolean shortest) {
        ArrayList<int[]> spans = TextCorpus.sentences(paragraph);
        String best = null;
        for (int i = 0; i < spans.size(); i++) {
            String s = stripTail(paragraph.substring(spans.get(i)[0], spans.get(i)[1]));
            int chars = valid(s);
            if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
            if (best == null || (shortest ? chars < valid(best) : chars > valid(best))) best = s;
        }
        return best;
    }

    private static String stripTail(String sentence) {
        int end = sentence.length();
        while (end > 0 && isTerminator(sentence.charAt(end - 1))) end--;
        return sentence.substring(0, end);
    }

    private static boolean isTerminator(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '”' || c == '』' || c == '」';
    }

    private static int valid(String text) {
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }
}
