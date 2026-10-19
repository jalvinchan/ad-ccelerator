package lab.youtube.accelerator;

import java.net.URI;
import java.net.URISyntaxException;

/** Navigation and extension-origin policy. Subresources are handled by GeckoView. */
final class UrlPolicy {
    static boolean trusted(String url) {
        URI uri = secureUrl(url);
        return uri != null && ("m.youtube.com".equals(uri.getHost()) ||
            "www.youtube.com".equals(uri.getHost()));
    }

    /** A normal video page, which opens in the player layer above the browse page. Shorts stay in the feed. */
    static boolean watch(String url) {
        if (!trusted(url)) return false;
        URI uri = secureUrl(url);
        String path = uri.getRawPath();
        if (path != null && path.matches("/live/[^/]+/?")) return true;
        if (!"/watch".equals(path) || uri.getRawQuery() == null) return false;
        for (String pair : uri.getRawQuery().split("&")) {
            if (pair.startsWith("v=") && pair.length() > 2) return true;
        }
        return false;
    }

    static boolean browsableHttps(String url) {
        return secureUrl(url) != null;
    }

    private static URI secureUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null &&
                uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                ? uri : null;
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private UrlPolicy() {}
}
