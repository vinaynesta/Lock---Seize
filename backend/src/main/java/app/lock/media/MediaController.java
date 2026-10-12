package app.lock.media;

import app.lock.common.CurrentUser;
import app.lock.memory.MemoryAsset;
import app.lock.memory.MemoryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping("/memories/{memoryId}/assets/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaService.UploadTicket initiate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId,
                                             @Valid @RequestBody UploadRequest req) {
        return media.initiateUpload(memoryId, CurrentUser.id(jwt), req.type(), req.mimeType(), req.sizeBytes(), req.checksumSha256());
    }

    @PostMapping("/assets/{assetId}/complete")
    public MemoryView.AssetView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assetId) {
        return MemoryView.AssetView.of(media.completeUpload(assetId, CurrentUser.id(jwt)));
    }

    @GetMapping("/assets/{assetId}/download")
    public DownloadResponse download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assetId) {
        return new DownloadResponse(media.downloadUrl(assetId, CurrentUser.id(jwt)));
    }

    @DeleteMapping("/assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assetId) {
        media.removeFromDraft(assetId, CurrentUser.id(jwt));
    }

    public record UploadRequest(
            @NotNull MemoryAsset.Type type,
            @NotBlank String mimeType,
            @Positive long sizeBytes,
            @NotBlank String checksumSha256) {}

    public record DownloadResponse(URI url) {}
}
