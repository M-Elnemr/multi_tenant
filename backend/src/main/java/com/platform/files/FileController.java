package com.platform.files;

import com.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;

    public FileController(FileService files) { this.files = files; }

    public record PresignRequest(@NotBlank String filename, @NotBlank String contentType, @NotNull Long size, @NotBlank String category, String visibility) {}

    /** Step 1 (authenticated): ask permission to upload. Returns a short-lived signed upload URL. */
    @PostMapping("/presign")
    public Map<String, Object> presign(@Valid @RequestBody PresignRequest r, Authentication a) {
        return files.presign(TenantContext.require().id(), (UUID) a.getPrincipal(), r.filename(), r.contentType(), r.size(), r.category(), r.visibility());
    }

    /** Step 2: PUT the raw bytes to the signed URL. The signature in the URL is the credential. */
    @PutMapping("/{id}/content")
    public Map<String, Object> upload(@PathVariable UUID id, @RequestParam long exp, @RequestParam String sig, @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType, @RequestBody byte[] data) {
        return files.upload(TenantContext.require().id(), id, exp, sig, contentType, data);
    }

    /**
     * Public images: redirect (when an edge proxy serves the bucket) or stream with immutable caching; ?variant=thumb|medium|original.
     * Private files: authorized callers only (404 otherwise), never cacheable.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam(required = false) String variant, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, Authentication a) {
        if (variant != null && !java.util.Set.of("thumb", "medium", "original").contains(variant)) variant = null;
        UUID user = a == null || !(a.getPrincipal() instanceof UUID u) ? null : u;
        Set<String> authorities = a == null ? Set.of() : a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        FileService.Download d = files.download(TenantContext.require().id(), id, variant, user, authorities);
        if (d.redirectPath() != null)
            return ResponseEntity.status(302).header(HttpHeaders.LOCATION, d.redirectPath()).cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic()).build();
        if (d.isPublic() && d.etag().equals(ifNoneMatch)) return ResponseEntity.status(304).eTag(d.etag()).build();
        var res = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff");
        if (d.isPublic()) res.eTag(d.etag()).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable());
        else res.cacheControl(CacheControl.noStore().cachePrivate());
        return res.body(d.data());
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication a) {
        Set<String> authorities = a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        files.delete(TenantContext.require().id(), (UUID) a.getPrincipal(), authorities, id);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage() { return files.usage(TenantContext.require().id()); }
}
