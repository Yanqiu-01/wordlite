package com.rikkahub.wordlite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Public candidate -> local library bridge keeps material tiers and dedup semantics. */
public final class OpenCorpusImportRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File directory = new File("artifacts/tests/open-corpus-import");
        delete(directory);
        LocalLibrary library = new LocalLibrary(directory);

        PaperSources.Candidate abstractCandidate = new PaperSources.Candidate();
        abstractCandidate.source.engine = "openalex";
        abstractCandidate.source.title = "公开候选论文";
        abstractCandidate.source.authors = "张三";
        abstractCandidate.source.year = "2025";
        abstractCandidate.abstractText = "本文研究一种公开检索摘要的入库路径，用于测试候选材料。";

        PaperSources.Candidate recordCandidate = new PaperSources.Candidate();
        recordCandidate.source.engine = "crossref";
        recordCandidate.source.title = "只有题录的论文";

        ArrayList<PaperSources.Candidate> candidates = new ArrayList<PaperSources.Candidate>();
        candidates.add(abstractCandidate);
        candidates.add(abstractCandidate);
        candidates.add(recordCandidate);
        CorpusImport.Batch batch = CorpusImport.importCandidates(library, candidates, null, null);
        check(batch.imported() == 2, "same candidate body is deduplicated in one import batch");
        check(batch.duplicates() == 1, "duplicate candidate gets a normal receipt");
        check(library.size() == 2, "abstract and record candidates both enter the library");

        LocalLibrary.Entry first = library.entries().get(0);
        check(DuplicateEngine.MATERIAL_ABSTRACT.equals(first.material), "abstract candidate keeps abstract tier");
        check(new String(read(new File(directory, first.name)), StandardCharsets.UTF_8)
                .contains(abstractCandidate.abstractText), "only candidate metadata and abstract are stored");
        LocalLibrary.Entry second = library.entries().get(1);
        check(DuplicateEngine.MATERIAL_RECORD.equals(second.material), "missing abstract keeps record-only tier");

        delete(directory);
        System.out.println("SUMMARY " + checks + " assertions passed");
    }

    private static byte[] read(File file) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}
