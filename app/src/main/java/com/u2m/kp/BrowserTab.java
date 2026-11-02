package com.u2m.kp;

import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;

/** 브라우저 탭 하나 — 웹뷰와 탭 줄에 보이는 칸, 그리고 그 탭의 상태를 묶어 둔다. */
final class BrowserTab {

    final WebView webView;

    View tabView;
    TextView titleView;
    ImageView closeView;

    String title = "새 탭";
    /** 사용자가 보고 있는(또는 열려다 막힌) 실제 주소. 내부 안내 페이지 주소는 담지 않는다. */
    String url = "";
    int progress = 0;
    boolean loading = false;
    /** 허용 목록 밖이라 안내 화면을 보여 주는 중. 새로고침하면 허용 여부를 다시 판정한다. */
    boolean blocked = false;
    boolean showingNetworkError = false;
    boolean loadedOnce = false;

    BrowserTab(WebView webView) {
        this.webView = webView;
    }

    /** loadDataWithBaseURL 로 직접 그린 안내 페이지(차단/네트워크 오류)의 주소인가. */
    static boolean isInternalPageUrl(String url) {
        return url == null || url.equals("about:blank") || url.startsWith("data:");
    }

    /** 실제 페이지로 이동했을 때만 주소를 갱신한다. */
    void onUrlChanged(String newUrl) {
        if (isInternalPageUrl(newUrl)) return;
        url = newUrl;
        blocked = false;
    }
}
