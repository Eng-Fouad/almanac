package io.quarkiverse.almanac.maven;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// [RangeFetcher] over HTTP range requests; also copes with servers that ignore `Range`.
final class HttpRangeFetcher implements RangeFetcher {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private final Http http;
    private final URI uri;

    HttpRangeFetcher(Http http, URI uri) {
        this.http = http;
        this.uri = uri;
    }

    @Override
    public Optional<Tail> tail(int length) throws IOException {
        Http.Response response = http.getRange(uri, "bytes=-" + length);
        return switch (response.status()) {
            case 404 -> Optional.empty();
            case 206 -> Optional.of(new Tail(response.body(), contentRange(response)[2]));
            case 200 -> {
                byte[] body = response.body();
                int from = Math.max(0, body.length - length);
                yield Optional.of(new Tail(Arrays.copyOfRange(body, from, body.length), body.length));
            }
            default -> throw unexpected(response);
        };
    }

    @Override
    public byte[] range(long offset, int length) throws IOException {
        Http.Response response = http.getRange(uri, "bytes=" + offset + "-" + (offset + length - 1));
        byte[] body = response.body();
        if (response.status() == 206) {
            long[] range = contentRange(response);
            if (range[0] != offset || body.length != length) {
                throw new IOException("unexpected range " + Arrays.toString(range) + " for " + uri);
            }
            return body;
        }
        if (response.status() == 200 && offset + length <= body.length) {
            return Arrays.copyOfRange(body, Math.toIntExact(offset), Math.toIntExact(offset + length));
        }
        throw unexpected(response);
    }

    @Override
    public byte[] all() throws IOException {
        Http.Response response = http.get(uri);
        if (response.status() != 200) {
            throw unexpected(response);
        }
        return response.body();
    }

    /// `[first, last, total]` of a `Content-Range` header.
    private long[] contentRange(Http.Response response) throws IOException {
        String header = response.header("Content-Range").orElse("");
        Matcher m = CONTENT_RANGE.matcher(header);
        if (!m.matches()) {
            throw new IOException("unparsable Content-Range '" + header + "' for " + uri);
        }
        return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))};
    }

    private IOException unexpected(Http.Response response) {
        return new IOException("HTTP " + response.status() + " for " + uri);
    }
}
