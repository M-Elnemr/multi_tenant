package com.platform.marketing;

import com.platform.files.FileService;
import com.platform.shared.BusinessException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Short vertical slideshow videos (up to 5 product pictures, 3 s each, 9:16) made with ffmpeg on the server: no paid video AI. Off by default because it needs CPU and
 * the ffmpeg program; one video is rendered at a time. The file is kept under {@code app.marketing.video.dir} and downloaded by the owner to post as a reel.
 */
@Service
public class ReelService {
    private static final Logger log = LoggerFactory.getLogger(ReelService.class);
    private final Semaphore one = new Semaphore(1);
    private final JdbcClient jdbc;
    private final FileService files;
    private final boolean enabled;
    private final Path dir;
    private final String ffmpeg;

    public ReelService(JdbcClient jdbc, FileService files, @Value("${app.marketing.video.enabled:false}") boolean enabled,
                       @Value("${app.marketing.video.dir:${java.io.tmpdir}/reels}") String dir, @Value("${app.marketing.video.ffmpeg:ffmpeg}") String ffmpeg) {
        this.jdbc = jdbc;
        this.files = files;
        this.enabled = enabled;
        this.dir = Path.of(dir);
        this.ffmpeg = ffmpeg;
    }

    public boolean enabled() { return enabled; }

    /** Renders the video for a plan item and returns its path. Blocks the caller (the dashboard shows a spinner); only one render runs at a time. */
    public Path render(UUID tenantId, UUID itemId, UUID productId) {
        if (!enabled) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_NOT_AVAILABLE", "Video making is not enabled on this server");
        if (productId == null) throw BusinessException.badRequest("VALIDATION_ERROR", "A video needs a product with pictures");
        List<UUID> ids = jdbc.sql("SELECT file_id FROM commerce.product_media WHERE product_id = :p AND tenant_id = :t AND file_id IS NOT NULL ORDER BY sort_order LIMIT 5").param("p", productId).param("t", tenantId).query(UUID.class).list();
        if (ids.isEmpty()) throw BusinessException.badRequest("IMAGE_REQUIRED", "This product has no pictures to make a video from");
        try {
            if (!one.tryAcquire(60, TimeUnit.SECONDS)) throw new BusinessException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "VIDEO_BUSY", "Another video is being made, try again in a minute");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "VIDEO_BUSY", "Try again");
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("reel");
            // One picture at a time into a small clip, then the clips are joined without re-encoding: peak memory stays near 140 MB instead of 300 MB for all pictures at once (the server has little spare RAM).
            List<Path> clips = new ArrayList<>();
            int n = 0;
            for (UUID id : ids) {
                var raw = files.readForExport(tenantId, id);
                if (raw.isEmpty()) continue;
                Path img = work.resolve("p" + n + ".jpg"), clip = work.resolve("c" + n + ".mp4");
                Files.write(img, raw.get().data());
                run(List.of(ffmpeg, "-y", "-loglevel", "error", "-threads", "1", "-loop", "1", "-t", "3", "-i", img.toString(), "-vf",
                        "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,setsar=1,fps=25,fade=t=in:st=0:d=0.4,fade=t=out:st=2.6:d=0.4,format=yuv420p",
                        "-an", "-c:v", "libx264", "-preset", "veryfast", "-movflags", "+faststart", clip.toString()), 60);
                Files.delete(img);
                clips.add(clip);
                n++;
            }
            if (n == 0) throw BusinessException.badRequest("IMAGE_REQUIRED", "The pictures could not be read");
            Path list = work.resolve("list.txt");
            Files.writeString(list, clips.stream().map(c -> "file '" + c.toString().replace("'", "") + "'").collect(java.util.stream.Collectors.joining("\n")) + "\n");
            Path out = dir.resolve(tenantId.toString()).resolve(itemId + ".mp4");
            Files.createDirectories(out.getParent());
            run(List.of(ffmpeg, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", "-movflags", "+faststart", out.toString()), 30);
            if (!Files.exists(out)) throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "VIDEO_FAILED", "The video could not be made");
            return out;
        } catch (java.io.IOException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "VIDEO_FAILED", "The video could not be made");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "VIDEO_FAILED", "The video was interrupted");
        } finally {
            one.release();
            if (work != null) { File[] fs = work.toFile().listFiles(); if (fs != null) for (File f : fs) f.delete(); work.toFile().delete(); }
        }
    }

    private void run(List<String> cmd, int seconds) throws java.io.IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new BusinessException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "VIDEO_FAILED", "The video took too long"); }
        if (p.exitValue() != 0) { log.warn("ffmpeg failed: {}", output); throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "VIDEO_FAILED", "The video could not be made"); }
    }
}
