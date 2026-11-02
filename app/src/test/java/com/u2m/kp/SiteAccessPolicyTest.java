package com.u2m.kp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SiteAccessPolicyTest {

    private final SiteAccessPolicy policy = new SiteAccessPolicy();

    @Test
    public void defaultList_allowsCoreStudySites() {
        assertTrue(policy.isAllowed("https://u2mkst.github.io/home/login"));
        assertTrue(policy.isAllowed("https://swgwanggyo.u2math.co.kr/Mypage/MyMenu/"));
        assertTrue(policy.isAllowed("https://mathflat.com/login"));
        assertTrue(policy.isAllowed("https://student.mathflat.com/"));
        assertTrue(policy.isAllowed("https://mathflat.co.kr/"));
        assertTrue(policy.isAllowed("https://www.neis.go.kr/"));
    }

    @Test
    public void defaultList_blocksEverythingElse() {
        assertFalse(policy.isAllowed("https://www.google.com/"));
        assertFalse(policy.isAllowed("https://www.youtube.com/watch?v=1"));
        assertFalse(policy.isAllowed("https://m.naver.com/"));
    }

    @Test
    public void allowed_requiresRealHostNotTextInUrl() {
        assertFalse(policy.isAllowed("https://evil.com/?x=u2math.co.kr"));
        assertFalse(policy.isAllowed("https://evil.com/u2math.co.kr/"));
        assertFalse(policy.isAllowed("https://u2math.co.kr.evil.com/"));
        assertFalse(policy.isAllowed("https://notmathflat.com/"));
        assertFalse(policy.isAllowed("https://xu2mkst.github.io/"));
        assertFalse(policy.isAllowed("https://u2mkst.github.io@evil.com/"));
        assertFalse(policy.isAllowed("https://evil.com#@u2mkst.github.io/"));
    }

    @Test
    public void allowed_hostCaseAndHttpAreHandled() {
        assertTrue(policy.isAllowed("HTTPS://MathFlat.COM/Login"));
        assertTrue(policy.isAllowed("http://swgwanggyo.u2math.co.kr/Login/Go?info=1"));
    }

    @Test
    public void nonWebSchemesAreNeverAllowed() {
        assertFalse(policy.isAllowed("javascript:alert(1)"));
        assertFalse(policy.isAllowed("file:///sdcard/a.html"));
        assertFalse(policy.isAllowed("intent://scan/#Intent;scheme=zxing;end"));
        assertFalse(policy.isAllowed("content://media/external/file/1"));
        assertFalse(policy.isAllowed("data:text/html,hi"));
        assertFalse(policy.isAllowed("about:blank"));
        assertFalse(policy.isAllowed(null));
        assertFalse(policy.isAllowed(""));
        assertFalse(policy.isAllowed("not a url"));
    }

    @Test
    public void setAllowedHosts_addsTeacherSitesAndKeepsCore() {
        policy.setAllowedHosts(Arrays.asList("ebs.co.kr", "https://www.example.org/path", "  Khan.Example.com "));
        assertTrue(policy.isAllowed("https://www.ebs.co.kr/"));
        assertTrue(policy.isAllowed("https://example.org/"));
        assertTrue(policy.isAllowed("https://khan.example.com/x"));
        assertTrue(policy.isAllowed("https://u2mkst.github.io/home/"));
        assertFalse("목록을 교체했으니 기본 목록의 수학 사이트는 서버가 다시 주지 않으면 닫힌다",
                policy.isAllowed("https://mathflat.com/"));
    }

    @Test
    public void setAllowedHosts_nullOrEmptyStillKeepsCoreOpen() {
        policy.setAllowedHosts(null);
        assertTrue(policy.isAllowed("https://u2mkst.github.io/home"));
        policy.setAllowedHosts(Collections.<String>emptyList());
        assertTrue(policy.isAllowed("https://u2mkst.github.io/home"));
        assertFalse(policy.isAllowed("https://mathflat.com/"));
    }

    @Test
    public void setAllowedHosts_rejectsDangerousEntries() {
        policy.setAllowedHosts(Arrays.asList("com", "kr", "co.kr", "go.kr", "*.com", "evil", "", "  ",
                "a b.com", "github.io", "vercel.app", "../x", "javascript:alert(1)"));
        assertFalse(policy.isAllowed("https://anything.com/"));
        assertFalse(policy.isAllowed("https://anything.co.kr/"));
        assertFalse(policy.isAllowed("https://x.go.kr/"));
        assertFalse(policy.isAllowed("https://someone.github.io/"));
        assertFalse(policy.isAllowed("https://app.vercel.app/"));
        assertEquals(Collections.singletonList(SiteAccessPolicy.CORE_HOST), policy.getAllowedHosts());
    }

    @Test
    public void sanitizeEntry_normalizes() {
        assertEquals("ebs.co.kr", SiteAccessPolicy.sanitizeEntry("https://www.EBS.co.kr/main?x=1"));
        assertEquals("mathflat.com", SiteAccessPolicy.sanitizeEntry("mathflat.com:443"));
        assertNull(SiteAccessPolicy.sanitizeEntry(null));
        assertNull(SiteAccessPolicy.sanitizeEntry("co.kr"));
    }

    @Test
    public void coreAndAutoLoginSites_areDecidedByHostOnly() {
        assertTrue(SiteAccessPolicy.isCoreSite("https://u2mkst.github.io/home"));
        assertFalse(SiteAccessPolicy.isCoreSite("https://mathflat.com/?u=u2mkst.github.io"));
        assertFalse(SiteAccessPolicy.isCoreSite("https://u2mkst.github.io.evil.com/"));

        assertTrue(SiteAccessPolicy.isU2mSite("https://swgwanggyo.u2math.co.kr/Login"));
        assertFalse(SiteAccessPolicy.isU2mSite("https://evil.com/u2math.co.kr"));

        assertTrue(SiteAccessPolicy.isMathflatSite("https://mathflat.com/login"));
        assertTrue(SiteAccessPolicy.isMathflatSite("https://app.mathflat.co.kr/"));
        assertFalse(SiteAccessPolicy.isMathflatSite("https://evil.com/mathflat.com"));
    }

    @Test
    public void normalizeTypedInput_acceptsAddressesOnly() {
        assertEquals("https://mathflat.com", SiteAccessPolicy.normalizeTypedInput("mathflat.com"));
        assertEquals("https://mathflat.com/login", SiteAccessPolicy.normalizeTypedInput("  mathflat.com/login "));
        assertEquals("https://a.kr:8443/x", SiteAccessPolicy.normalizeTypedInput("a.kr:8443/x"));
        assertEquals("http://swgwanggyo.u2math.co.kr/", SiteAccessPolicy.normalizeTypedInput("http://swgwanggyo.u2math.co.kr/"));

        assertNull(SiteAccessPolicy.normalizeTypedInput("수학 문제집 추천"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("hello"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("a b.com"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("javascript:alert(1)"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("javascript:a.b"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("file:///a.b"));
        assertNull(SiteAccessPolicy.normalizeTypedInput("intent://x.y"));
        assertNull(SiteAccessPolicy.normalizeTypedInput(""));
        assertNull(SiteAccessPolicy.normalizeTypedInput(null));
    }
}
