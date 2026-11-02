package com.u2m.kp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 브라우저 탭이 열 수 있는 사이트를 판정한다. Android 클래스를 쓰지 않는 순수 Java라서
 * 기기 없이 단위 테스트로 검증할 수 있다.
 *
 * 허용 기준은 "호스트가 허용 목록 항목과 정확히 같거나 그 서브도메인"이다. URL 문자열에
 * 허용 도메인이 들어 있기만 하면 통과시키던 방식은 쓰지 않는다
 * (예: https://공격자.com/?x=u2math.co.kr).
 */
public final class SiteAccessPolicy {

    /** 우리 학습 사이트 — 항상 허용되며, 앱 JS 브릿지는 이 호스트에서만 열린다. */
    public static final String CORE_HOST = "u2mkst.github.io";

    private static final List<String> DEFAULT_ALLOWED_HOSTS = Collections.unmodifiableList(Arrays.asList(
            CORE_HOST, "u2math.co.kr", "mathflat.com", "mathflat.co.kr", "neis.go.kr"));

    // 이 값 자체를 허용 항목으로 넣으면 그 아래 모든 사이트가 열려 버리므로 거부한다.
    private static final List<String> PUBLIC_SUFFIXES = Arrays.asList(
            "co.kr", "or.kr", "go.kr", "ne.kr", "re.kr", "pe.kr", "ac.kr", "hs.kr", "ms.kr", "es.kr", "kg.kr",
            "co.uk", "org.uk", "ac.uk", "com.au", "co.jp", "github.io", "vercel.app", "web.app", "firebaseapp.com");

    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");
    private static final Pattern TYPED_URL_PATTERN = Pattern.compile("^[^\\s/:]+\\.[^\\s/:]+([:/?#]\\S*)?$");

    private volatile List<String> allowedHosts = DEFAULT_ALLOWED_HOSTS;

    public static List<String> defaultAllowedHosts() {
        return DEFAULT_ALLOWED_HOSTS;
    }

    /**
     * 서버에서 받은 허용 목록으로 교체한다. 잘못된 항목은 조용히 버리고, 우리 사이트는 항상 포함한다.
     * 결과가 비어 있어도(서버 오류 등) 우리 사이트는 열려 있다.
     */
    public void setAllowedHosts(Collection<String> hosts) {
        List<String> cleaned = new ArrayList<>();
        cleaned.add(CORE_HOST);
        if (hosts != null) {
            for (String raw : hosts) {
                String host = sanitizeEntry(raw);
                if (host != null && !cleaned.contains(host)) cleaned.add(host);
            }
        }
        allowedHosts = Collections.unmodifiableList(cleaned);
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    /** 허용 목록 항목 하나를 정리한다. 쓸 수 없는 값이면 null. */
    static String sanitizeEntry(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("https://")) s = s.substring(8);
        else if (s.startsWith("http://")) s = s.substring(7);
        int cut = indexOfAny(s, "/?#:");
        if (cut >= 0) s = s.substring(0, cut);
        if (s.startsWith("www.")) s = s.substring(4);
        if (!HOST_PATTERN.matcher(s).matches()) return null;
        if (PUBLIC_SUFFIXES.contains(s)) return null;
        return s;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    public boolean isAllowed(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        for (String allowed : allowedHosts) {
            if (matchesHost(host, allowed)) return true;
        }
        return false;
    }

    /** http/https 이고 호스트가 있는 URL의 소문자 호스트. 그 밖에는 null. */
    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesHost(String host, String allowed) {
        return host.equals(allowed) || host.endsWith("." + allowed);
    }

    /** 앱 JS 브릿지(로그인 정보 전달 등)를 열어도 되는 우리 사이트인가. */
    public static boolean isCoreSite(String url) {
        String host = hostOf(url);
        return host != null && matchesHost(host, CORE_HOST);
    }

    /** 자동 로그인 대상 판정 — URL에 글자가 들어 있는지가 아니라 호스트로만 판단한다. */
    public static boolean isU2mSite(String url) {
        String host = hostOf(url);
        return host != null && matchesHost(host, "u2math.co.kr");
    }

    public static boolean isMathflatSite(String url) {
        String host = hostOf(url);
        return host != null && (matchesHost(host, "mathflat.com") || matchesHost(host, "mathflat.co.kr"));
    }

    /**
     * 주소창에 입력한 글자를 열 URL로 바꾼다. 주소처럼 보이지 않는 글자(검색어 등)나
     * javascript:, file:, intent: 같은 다른 스킴은 null.
     */
    public static String normalizeTypedInput(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return hostOf(s) == null ? null : s;
        }
        if (!TYPED_URL_PATTERN.matcher(s).matches()) return null;
        String candidate = "https://" + s;
        return hostOf(candidate) == null ? null : candidate;
    }
}
