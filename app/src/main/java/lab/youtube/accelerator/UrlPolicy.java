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
