package app.lock.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Proof of preservation: a SHA-256 over everything that makes up the sealed memory.
 *
 * <p>It hashes the stored ciphertext and asset checksums, not plaintext, so the hash reveals
 * nothing about short or guessable content. Recomputing it later and comparing proves nothing
 * changed.
 */
final class ContentHasher {

    private ContentHasher() {}

    static String hash(Memory m, List<MemoryAsset> assets) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            field(d, "v1");
            field(d, m.getId().toString());
            field(d, m.getVaultId().toString());
            field(d, m.getCreatedBy().toString());
            field(d, m.getTitleCt());
            field(d, m.getThoughtCt());
            field(d, m.getFeelingCt());
            field(d, m.getLocationCt());
            field(d, String.valueOf(m.getOccurredAt()));
            field(d, String.valueOf(m.getRevealAt()));
            assets.stream()
                    .sorted(Comparator.comparing(MemoryAsset::getId))
                    .forEach(a -> {
                        field(d, a.getId().toString());
                        field(d, a.getObjectKey());
                        field(d, a.getMimeType());
                        field(d, Long.toString(a.getSizeBytes()));
                        field(d, a.getChecksumSha256());
                    });
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(MessageDigest d, String value) {
        field(d, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    /** Length-prefixed so field boundaries are unambiguous. */
    private static void field(MessageDigest d, byte[] value) {
        if (value == null) {
            d.update(Base64.getEncoder().encode("<null>".getBytes(StandardCharsets.UTF_8)));
            d.update((byte) 0);
            return;
        }
        d.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
        d.update((byte) ':');
        d.update(value);
    }
}
