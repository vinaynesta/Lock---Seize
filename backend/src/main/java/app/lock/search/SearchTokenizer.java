package app.lock.search;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns text into blind-index tokens (roadmap 3.4). Words are lower-cased, accents stripped and split
 * on anything that is not a letter or digit; each word is indexed whole and by every prefix of at
 * least {@link #MIN_PREFIX} characters, so "gradu" finds "graduation". Tokens are HMAC-SHA256 under
 * the vault's search key, truncated to 16 bytes.
 */
public final class SearchTokenizer {

    static final int MIN_PREFIX = 3;
    private static final int MAX_WORD = 40;
    private static final int TOKEN_BYTES = 16;

    private SearchTokenizer() {}

    /** Normalised words of {@code text}, in order, without duplicates. */
    public static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        for (String w : folded.split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) {
                out.add(w.length() > MAX_WORD ? w.substring(0, MAX_WORD) : w);
            }
        }
        return out;
    }

    /** Every token to store for {@code text}: whole words and their prefixes. */
    public static Set<String> indexTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String w : words(text)) {
            terms.add("w:" + w);
            for (int n = MIN_PREFIX; n < w.length(); n++) {
                terms.add("p:" + w.substring(0, n));
            }
        }
        return terms;
    }

    /**
     * The term to look up for one query word: the whole word matches itself or a longer word it
     * starts; a word shorter than the minimum prefix only matches whole words.
     */
    public static Set<String> queryTerms(String word) {
        return word.length() < MIN_PREFIX ? Set.of("w:" + word) : Set.of("w:" + word, "p:" + word);
    }

    public static byte[] blind(byte[] searchKey, String term) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(searchKey, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(term.getBytes(StandardCharsets.UTF_8)), TOKEN_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
