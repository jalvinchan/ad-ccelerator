package lab.youtube.accelerator;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void onlyExactSecureYouTubeOriginsAreTrusted() {
        for (String url : new String[] {
            "https://m.youtube.com/", "https://www.youtube.com/watch?v=123",
            "https://m.youtube.com:443/watch?v=123#player"
        }) assertTrue(url, UrlPolicy.trusted(url));
        for (String url : new String[] {
            "http://m.youtube.com/", "https://youtube.com/", "https://m.youtube.com.evil.test/",
            "https://evil.test/m.youtube.com", "https://user@m.youtube.com/",
            "https://m.youtube.com:444/", "https://m.youtube.com:bad/",
            "https://m.youtube.com\\@evil.test/", "https://m.youtube.com./",
            "https://m.youtube.com%2f.evil.test/", "javascript:alert(1)",
            "intent://m.youtube.com/", "file:///etc/passwd", "https:///m.youtube.com/", null
        }) assertFalse(url, UrlPolicy.trusted(url));
    }

    @Test public void browserNavigationRejectsCredentialsAndMalformedUrls() {
        assertTrue(UrlPolicy.browsableHttps("https://example.com/path?q=youtube"));
        assertTrue(UrlPolicy.browsableHttps("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin"));
        assertTrue(UrlPolicy.browsableHttps("https://support.google.com/youtube/answer/3037019#zippy=%2Ccheck-that-youre-signed-into-youtube"));
        for (String url : new String[] {
            "https://user:secret@example.com/", "https://example.com:8443/",
            "https://", "http://example.com/", "intent://example.com/", null
        }) assertFalse(url, UrlPolicy.browsableHttps(url));
    }
}
