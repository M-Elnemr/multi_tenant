package com.platform;

import com.platform.commerce.RemoteImages;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for the download of a post's picture: every address gives the same tiny PNG. */
@Component
@Primary
public class FakeRemoteImages implements RemoteImages {
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    public static final List<String> FETCHED = new ArrayList<>();

    @Override
    public synchronized Optional<Image> fetch(String url) {
        FETCHED.add(url);
        return Optional.of(new Image(PNG, "image/png"));
    }
}
