package com.u2m.kp;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslError;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.DrawableCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.List;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KioskWebViewJS";
    // 평문 대신 SHA-256 해시로 저장 — 소스가 공개 저장소에 있어도 비밀번호 원문이 그대로 보이지 않게 한다.
    private static final String ADMIN_PASSWORD_HASH = "7f6a1b1ad20c02938a31632cc095da8cc463a7f31a736d2c07182d7e0e031cf9";
    // 📷 QR로 관리자 종료를 트리거하는 코드도 같은 방식(해시)으로 저장한다.
    private static final String QR_ADMIN_EXIT_CODE_HASH = "8ff601bbac417981c69f3ecacbcd6495cf5770d9a2ff6ef28f1e1b9daf5efc6a";

    private static String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isAdminPasswordCorrect(String input) {
        return sha256(input).equals(ADMIN_PASSWORD_HASH);
    }

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Button homeFloatingButton;

    // 📶 얇은 상단 상태 바 (시각 / 배터리 / 네트워크 / 종료)
    private TextView tvClock;
    private TextView tvBattery;
    private ImageView ivBattery;
    private ImageView ivNetworkStatus;
    private ImageView ivQuit;
    private ImageView ivQrScan;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    // 📷 QR 스캔 — 결과 콜백/권한 요청은 onCreate 이전(필드 초기화 시점)에 등록해야 한다.
    private final ActivityResultLauncher<ScanOptions> qrScanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    onQrCodeScanned(result.getContents());
                }
            });
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchQrScanner();
                } else {
                    Toast.makeText(this, "QR 스캔을 위해 카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
                }
            });

    // 자동 로그인 계정 저장 변수
    private String savedU2mId = "";
    private String savedPhone = "";
    private String savedMathflatPw = "";
    private String savedStudentPhone = "";

    // 시스템 브로드캐스트 리시버 및 네트워크 모니터링
    private BroadcastReceiver screenOffReceiver = null;
    private BroadcastReceiver headsetPlugReceiver = null;
    private BroadcastReceiver batteryReceiver = null;
    private BroadcastReceiver downloadCompleteReceiver = null;
    private ConnectivityManager.NetworkCallback networkCallback = null;

    private boolean isPendingScreenOffLogout = false;
    private boolean isLowBatteryWarned = false;
    private boolean isHomeButtonHidden = false; // 길게 눌러 숨김 — 새로고침 전까지 유지
    private boolean isShowingNetworkErrorPage = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 🚨 1. 치명적 크래시 발생 시 자동 재시작 복구 핸들러
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "🚨 치명적 크래시 발생! 앱을 자동으로 재시작합니다: " + throwable.getMessage());
            Intent intent = new Intent(MainActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            Process.killProcess(Process.myPid());
            System.exit(10);
        });

        super.onCreate(savedInstanceState);

        // 🏠 2. 기본 홈 앱 설정 여부 확인 및 요청
        checkAndRequestHomeApp();

        try {
            // 🔒 캡처 및 화면 녹화 차단 (보안)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

            // 💡 앱 사용 중 화면 꺼짐 방지
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            setContentView(R.layout.activity_main);

            // 🎨 몰입형 몰입 모드 (상단바/하단바 숨김)
            if (getWindow() != null && getWindow().getDecorView() != null) {
                getWindow().getDecorView().post(() -> hideSystemUI());
            }

            webView = findViewById(R.id.webView);
            swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
            homeFloatingButton = findViewById(R.id.btnHome);
            tvClock = findViewById(R.id.tvClock);
            tvBattery = findViewById(R.id.tvBattery);
            ivBattery = findViewById(R.id.ivBattery);
            ivNetworkStatus = findViewById(R.id.ivNetworkStatus);
            ivQuit = findViewById(R.id.ivQuit);
            ivQrScan = findViewById(R.id.ivQrScan);

            if (webView == null) {
                throw new NullPointerException("❌ [XML 매칭 실패] activity_main.xml에 'webView' ID가 존재하지 않습니다.");
            }

            // 🕒 상단 상태 바 시계 (1초마다 갱신)
            if (tvClock != null) {
                clockRunnable = new Runnable() {
                    @Override
                    public void run() {
                        tvClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()));
                        clockHandler.postDelayed(this, 1000);
                    }
                };
                clockHandler.post(clockRunnable);
            }

            // 📶 상단 상태 바 네트워크 아이콘 탭 시 네트워크 설정 화면으로 이동
            if (ivNetworkStatus != null) {
                ivNetworkStatus.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                });
                updateNetworkStatusIcon();
            }

            updateBatteryStatusText();

            // ⛔ 상단 상태 바 종료 버튼 — 관리자 비밀번호 확인 후 앱 종료
            if (ivQuit != null) {
                ivQuit.setOnClickListener(v -> showAdminPasswordDialog());
            }

            // 📷 상단 상태 바 QR 스캔 버튼
            if (ivQrScan != null) {
                ivQrScan.setOnClickListener(v -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        launchQrScanner();
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
                    }
                });
            }

            // 🔄 당겨서 새로고침 리스너
            // WebView는 NestedScrollingChild를 구현하지 않아 SwipeRefreshLayout이 제스처를
            // 가로챌지 판단할 때 getScrollY() 값이 터치 이벤트와 정확히 동기화되지 않는
            // 경우가 있다 — 이 때문에 맨 위가 아닌 곳에서 위로 스크롤(아래로 쓸어내리는
            // 제스처)해도 새로고침이 발동하는 문제가 생긴다. canScrollVertically(-1)로
            // 판단을 대체하고, 스크롤 위치에 따라 아예 제스처 자체를 껐다 켰다 해서
            // 스크롤 맨 위에서만 당겨서 새로고침이 가능하도록 이중으로 막는다.
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setOnRefreshListener(() -> {
                    if (webView != null) {
                        isHomeButtonHidden = false;
                        webView.reload();
                    }
                });
                swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView.canScrollVertically(-1));

                if (webView != null) {
                    webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                            swipeRefreshLayout.setEnabled(scrollY == 0));
                }
            }

            // 🏠 플로팅 홈 버튼 클릭 시 세션 유지 이동
            if (homeFloatingButton != null) {
                homeFloatingButton.setText("");
                homeFloatingButton.setGravity(Gravity.CENTER);

                Drawable buttonBg = ContextCompat.getDrawable(this, R.drawable.bg_home_button);
                Drawable homeIcon = ContextCompat.getDrawable(this, R.drawable.ic_home);

                if (buttonBg != null && homeIcon != null) {
                    DrawableCompat.setTint(homeIcon, Color.WHITE);
                    Drawable[] layers = new Drawable[]{buttonBg, homeIcon};
                    LayerDrawable layerDrawable = new LayerDrawable(layers);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        layerDrawable.setLayerGravity(1, Gravity.CENTER);
                    } else {
                        layerDrawable.setLayerInset(1, 0, 0, 0, 0);
                    }

                    homeFloatingButton.setBackground(layerDrawable);
                    homeFloatingButton.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);

                    // 🌟 기본 검은 그림자 대신 버튼 색과 어울리는 파란빛 그림자
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        homeFloatingButton.setOutlineAmbientShadowColor(Color.parseColor("#004BBF"));
                        homeFloatingButton.setOutlineSpotShadowColor(Color.parseColor("#004BBF"));
                    }
                }

                homeFloatingButton.setVisibility(View.GONE);
                homeFloatingButton.setOnClickListener(v -> {
                    webView.loadUrl("https://u2mkst.github.io/home");
                });

                // 🙈 길게 누르면 숨김 — 새로고침(당겨서 새로고침 / 네트워크 재연결 자동 새로고침)
                // 전까지는 계속 숨겨져 있는다.
                homeFloatingButton.setOnLongClickListener(v -> {
                    isHomeButtonHidden = true;
                    homeFloatingButton.setVisibility(View.GONE);
                    Toast.makeText(this, "홈 버튼을 숨겼습니다. 새로고침하면 다시 나타납니다.", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // 🌐 웹뷰 고급 환경 설정
            WebSettings webSettings = webView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setUseWideViewPort(true);
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
            webSettings.setDatabaseEnabled(true);
            webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            // 🍪 쿠키 허용 설정 (로그인 세션 유지)
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(webView, true);
            }

            // 구글이 "; wv" 토큰으로 임베디드 웹뷰를 감지해 reCAPTCHA/로그인 등을 차단하므로
            // 커스텀 문자열은 덧붙이지 않고 표준 크롬 브라우저처럼 보이도록 wv 토큰만 제거한다.
            String defaultUserAgent = webSettings.getUserAgentString();
            webSettings.setUserAgentString(defaultUserAgent.replace("; wv", ""));

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    Log.d(TAG, "[WebConsole] " + consoleMessage.message());
                    return true;
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();

                    // 🔒 [추가] 외부 시스템 설정 진입 및 외부 패키지 차단
                    if (url.startsWith("intent:") || url.startsWith("android-app:")) {
                        if (url.contains("com.android.settings")) {
                            Toast.makeText(MainActivity.this, "🔒 시스템 설정 진입이 제한되어 있습니다.", Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }

                    if (url.contains("swgwanggyo.u2math.co.kr/Main/Iu2mfc")) {
                        view.loadUrl("https://swgwanggyo.u2math.co.kr/Mypage/MyMenu/");
                        return true;
                    }

                    if (url.toLowerCase().contains(".apk")) {
                        downloadApkFile(url);
                        return true;
                    }

                    if (isBlacklistedUrl(url) || isExternalUrl(url)) {
                        Toast.makeText(MainActivity.this, "보안 정책상 허용되지 않은 페이지입니다.", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    view.loadUrl(url);
                    return true;
                }

                // 🔒 잘못된(자체서명/만료/호스트 불일치) 인증서는 무조건 거부한다.
                // proceed()로 무시하면 같은 네트워크의 공격자가 중간자 공격으로
                // 모든 HTTPS 트래픽을 가로챌 수 있게 되므로 절대 허용하지 않는다.
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    Log.e(TAG, "SSL 인증서 오류로 접속 차단: " + error);
                    Toast.makeText(MainActivity.this, "🔒 안전하지 않은 연결이라 접속을 차단했습니다.", Toast.LENGTH_SHORT).show();
                    handler.cancel();
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);

                    if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                        swipeRefreshLayout.setRefreshing(false);
                    }

                    if (request.isForMainFrame()) {
                        Log.w(TAG, "⚠️ 네트워크 끊김 감지: " + error.getDescription());

                        String failedUrl = request.getUrl() != null ? request.getUrl().toString() : "https://u2mkst.github.io/home";
                        String retryUrl = failedUrl.replace("\\", "\\\\").replace("'", "\\'");

                        String errorHtml = "<!DOCTYPE html>" +
                                "<html><head><meta charset='UTF-8'>" +
                                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
                                "<style>" +
                                "* { margin: 0; padding: 0; box-sizing: border-box; }" +
                                "html, body { width: 100%; height: 100%; background: #F2F4F6; " +
                                "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Malgun Gothic', sans-serif; }" +
                                ".wrap { width: 100vw; height: 100vh; display: flex; flex-direction: column; " +
                                "  align-items: center; justify-content: center; padding: 24px; text-align: center; }" +
                                ".icon { width: 56px; height: 56px; margin-bottom: 22px; }" +
                                "h1 { font-size: 20px; font-weight: 800; color: #191F28; margin-bottom: 8px; }" +
                                "p { font-size: 13.5px; color: #8B95A1; line-height: 1.6; margin-bottom: 30px; }" +
                                "button { font-family: inherit; font-size: 15px; font-weight: 700; color: #FFFFFF; " +
                                "  background: #004BBF; border: none; border-radius: 14px; padding: 14px 34px; }" +
                                "button:active { opacity: 0.85; }" +
                                "</style></head><body>" +
                                "<div class='wrap'>" +
                                "  <svg class='icon' viewBox='0 0 24 24'>" +
                                "    <path fill='#8B95A1' fill-opacity='0.4' d='M1,14L5,14L5,20L1,20Z M7,10L11,10L11,20L7,20Z M13,6L17,6L17,20L13,20Z M19,2L23,2L23,20L19,20Z'/>" +
                                "    <path stroke='#191F28' stroke-width='2.2' stroke-linecap='round' d='M2,21L22,3'/>" +
                                "  </svg>" +
                                "  <h1>네트워크 연결 실패</h1>" +
                                "  <p>네트워크 상태를 확인한 뒤<br>다시 시도해 주세요</p>" +
                                "  <button onclick=\"location.href='" + retryUrl + "'\">다시 시도</button>" +
                                "</div>" +
                                "</body></html>";

                        isShowingNetworkErrorPage = true;
                        view.loadDataWithBaseURL("https://u2mkst.github.io/", errorHtml, "text/html", "UTF-8", null);
                    }
                }

                @Override
                public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                    super.doUpdateVisitedHistory(view, url, isReload);
                    checkAndToggleHomeButton(url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);

                    if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                        swipeRefreshLayout.setRefreshing(false);
                    }

                    // 오프라인 에러 페이지는 baseURL("https://u2mkst.github.io/")로만 보고되므로,
                    // 실제 콘텐츠 경로(/home, /login 등)로 넘어간 경우에만 에러 상태를 해제한다.
                    if (!"https://u2mkst.github.io/".equals(url)) {
                        isShowingNetworkErrorPage = false;
                    }

                    injectScrollBridge(view);
                    checkAndToggleHomeButton(url);
                    if (url == null) return;

                    if (url.contains("swgwanggyo.u2math.co.kr/Main/Iu2mfc")) {
                        view.loadUrl("https://swgwanggyo.u2math.co.kr/Mypage/MyMenu/");
                        return;
                    }

                    // 🍪 쿠키 플러시 (세션 동기화 유지)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().flush();
                    }

                    // 🔑 계정 주입 및 자동 로그인 실행
                    boolean isAlreadyLoggedInPage = url.contains("Mypage") || url.contains("MyMenu") || url.contains("mypage");

                    if (url.contains("u2math.co.kr") && !isAlreadyLoggedInPage && !savedU2mId.isEmpty() && !savedPhone.isEmpty()) {
                        injectOriginalU2MCode(view, savedU2mId, savedPhone);
                    } else if ((url.contains("mathflat.com") || url.contains("mathflat.co.kr")) && !url.contains("/student/")) {
                        if (!savedStudentPhone.isEmpty() && !savedMathflatPw.isEmpty()) {
                            injectMathflatCode(view, savedStudentPhone, savedMathflatPw);
                        }
                    }

                    // 🎧 동영상 재생 시 이어폰 착용 여부 감지 스크립트 오버라이드
                    String earphoneJs = "javascript:(function() {" +
                            "    var videos = document.getElementsByTagName('video');" +
                            "    for (var i = 0; i < videos.length; i++) {" +
                            "        videos[i].addEventListener('play', function(e) {" +
                            "            if (window.AndroidApp && !window.AndroidApp.isEarphonesPlugged()) {" +
                            "                this.pause();" +
                            "                window.AndroidApp.checkEarphonesBeforeVideo();" +
                            "            }" +
                            "        });" +
                            "    }" +
                            "})()";
                    view.evaluateJavascript(earphoneJs, null);
                }
            });

            // Javascript Interface 등록
            webView.addJavascriptInterface(new AndroidBridge(), "AndroidApp");
            // 🔐 앱을 완전히 종료했다 다시 켜는 경우(화면 꺼짐이 아니라 프로세스 자체가
            // 새로 시작하는 경우)에는 화면 꺼짐 브로드캐스트가 못 잡으므로, 매 콜드 스타트마다
            // 한 번 더 세션을 정리해서 이전 사용자로 자동 로그인되는 일을 막는다.
            executeSessionClear();
            webView.loadUrl("https://u2mkst.github.io/home/login");

            // 🚫 뒤로가기 완전 차단 — 화면 이동은 무조건 플로팅 홈 버튼으로만 하도록 강제한다.
            // (관리자 종료는 상단 종료 아이콘(ivQuit)으로 별도 접근 가능하므로 뒤로가기에 묶을 필요 없음)
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    Toast.makeText(MainActivity.this, "🏠 홈 버튼을 이용해 주세요.", Toast.LENGTH_SHORT).show();
                }
            });

            registerSystemReceivers();
            registerNetworkCallback();

        } catch (Exception e) {
            showEmergencyDiagnosticScreen(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 📌 [신규 추가] 포커스 변경 및 화면 상태 제어 (앱 고정 & 상단바 끌어내림 차단)
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 사용자가 상단바를 쓸어내려 포커스가 이탈되었다가 돌아올 때 상단바 즉시 강제 숨김
            hideSystemUI();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().post(() -> hideSystemUI());
        }

        // 🔒 [추가] 앱 고정 모드(Lock Task) 자동 요청
        requestAppPinning();

        if (isPendingScreenOffLogout) {
            isPendingScreenOffLogout = false;
            executeSessionClear();
            if (webView != null) {
                webView.loadUrl("https://u2mkst.github.io/home");
            }
        }
    }

    // 🔒 [추가] 앱 고정(App Pinning) 요청 메서드
    private void requestAppPinning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am != null) {
                int lockTaskMode = am.getLockTaskModeState();
                if (lockTaskMode == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "앱 고정 요청 실패: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 🛠️ 브로드캐스트 리시버 및 네트워킹 제어부
    // ---------------------------------------------------------------------------------------------

    private void registerSystemReceivers() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.d(TAG, "🎯 [보안] 태블릿 화면 꺼짐 감지 -> 데이터 삭제 및 로그아웃 대기 플래그 세팅");
                    executeSessionClear();
                    isPendingScreenOffLogout = true;
                }
            }
        };

        headsetPlugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                    int state = intent.getIntExtra("state", -1);
                    if (state == 1) {
                        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        if (am != null) {
                            int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int targetVol = (int) (maxVol * 0.4);
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                            Toast.makeText(MainActivity.this, "🎧 이어폰 연결됨: 볼륨이 적정 높이(40%)로 설정되었습니다.", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        };

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int batteryPct = (int) ((level / (float) scale) * 100);

                    int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;

                    applyBatteryUi(batteryPct, isCharging);

                    if (batteryPct <= 15 && !isCharging && !isLowBatteryWarned) {
                        isLowBatteryWarned = true;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("🔋 배터리 부족 경고")
                                .setMessage("현재 배터리가 " + batteryPct + "% 남았습니다.\n원활한 수업 진행을 위해 충전기를 연결해 주세요.")
                                .setPositiveButton("확인", null)
                                .show();
                    } else if (batteryPct > 20) {
                        isLowBatteryWarned = false;
                    }
                }
            }
        };

        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    Toast.makeText(context, "📦 업데이트 파일 다운로드 완료! 설치를 진행합니다.", Toast.LENGTH_LONG).show();
                    promptInstallApk();
                }
            }
        };

        IntentFilter screenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        IntentFilter headsetFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        ContextCompat.registerReceiver(this, screenOffReceiver, screenOffFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, headsetPlugReceiver, headsetFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, batteryReceiver, batteryFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, downloadCompleteReceiver, downloadFilter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        runOnUiThread(() -> {
                            updateNetworkStatusIcon();
                            if (webView != null && isShowingNetworkErrorPage) {
                                Toast.makeText(MainActivity.this, "📶 인터넷이 재연결되었습니다. 페이지를 새로고칩니다.", Toast.LENGTH_SHORT).show();
                                isHomeButtonHidden = false;
                                webView.reload();
                            }
                        });
                    }

                    @Override
                    public void onLost(Network network) {
                        runOnUiThread(MainActivity.this::updateNetworkStatusIcon);
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "네트워크 모니터링 등록 실패: " + e.getMessage());
        }
    }

    // 🔄 페이지 안의 내부 스크롤 div(랭킹/시간표 패널 등)까지 감안한 당겨서 새로고침 제어.
    // WebView 자체(document)는 스크롤이 안 되고 내부 div가 overflow-y로 스크롤되는
    // 화면에서는 canScrollVertically(-1)/getScrollY()가 항상 0을 가리켜서, 내부 스크롤이
    // 맨 위가 아닌데도 새로고침이 발동한다. capture 단계로 모든 하위 요소의 'scroll'
    // 이벤트를 잡아 실제로 스크롤된 요소의 scrollTop을 네이티브로 그대로 전달한다.
    private void injectScrollBridge(WebView view) {
        if (view == null) return;
        String js = "(function(){" +
                "if(window.__kpScrollBridgeInstalled)return;" +
                "window.__kpScrollBridgeInstalled=true;" +
                "document.addEventListener('scroll',function(e){" +
                "var el=(e.target===document)?document.scrollingElement:e.target;" +
                "if(el&&window.AndroidApp&&window.AndroidApp.reportScrollTop){" +
                "window.AndroidApp.reportScrollTop(el.scrollTop<=0);" +
                "}" +
                "},true);" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    // 📶 상단 상태 바의 네트워크 아이콘을 현재 연결 상태로 갱신 (기기마다 다르게 보이는
    // 이모지 대신 통일된 벡터 아이콘 두 종류만 사용: 연결됨 / 끊김)
    private void updateNetworkStatusIcon() {
        if (ivNetworkStatus == null) return;
        boolean hasInternet = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities capabilities = cm != null ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
            hasInternet = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            Log.e(TAG, "네트워크 상태 확인 실패: " + e.getMessage());
        }
        ivNetworkStatus.setImageResource(hasInternet ? R.drawable.ic_network_connected : R.drawable.ic_network_disconnected);
    }

    // 🔋 상단 상태 바의 배터리 표시를 현재 잔량으로 초기화
    private void updateBatteryStatusText() {
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, filter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                int batteryPct = (int) ((level / (float) scale) * 100);
                applyBatteryUi(batteryPct, isCharging);
            }
        } catch (Exception e) {
            Log.e(TAG, "배터리 상태 초기화 실패: " + e.getMessage());
        }
    }

    // 🔋 배터리 아이콘/퍼센트 텍스트를 함께 갱신 (충전 중이면 번개 아이콘으로 전환)
    private void applyBatteryUi(int batteryPct, boolean isCharging) {
        if (tvBattery != null) {
            tvBattery.setText(batteryPct + "%");
        }
        if (ivBattery != null) {
            ivBattery.setImageResource(isCharging ? R.drawable.ic_battery_charging : R.drawable.ic_battery);
        }
    }

    // ⛔ 상단 상태 바 종료 버튼 — 관리자 비밀번호 확인 후 앱 완전 종료
    // ⛔ 앱 고정(Lock Task) 상태를 해제하고 앱을 완전히 종료
    private void quitApp() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "앱 고정 해제 실패: " + e.getMessage());
        }

        // 🏠 우리 앱이 기본 홈(런처)으로 지정돼 있으면, 그냥 종료해도 시스템이
        // "홈 화면이 항상 떠 있어야 한다"는 규칙 때문에 즉시 우리 앱을 다시 띄워버린다.
        // "항상 이 앱으로" 선택을 초기화하고, 태블릿에 설치된 다른 실제 런처가 있으면
        // 그 화면을 직접 띄운 뒤에 우리 앱을 종료해야 진짜 바탕화면으로 빠져나갈 수 있다.
        try {
            PackageManager pm = getPackageManager();
            pm.clearPackagePreferredActivities(getPackageName());

            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : resolveInfos) {
                String pkg = info.activityInfo.packageName;
                if (pkg != null && !pkg.equals(getPackageName())) {
                    homeIntent.setClassName(pkg, info.activityInfo.name);
                    homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(homeIntent);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "다른 런처로 전환 실패: " + e.getMessage());
        }

        finishAffinity();
        System.exit(0);
    }

    // ---------------------------------------------------------------------------------------------
    // 📱 앱 버전 및 APK 자동 설치 보조
    // ---------------------------------------------------------------------------------------------

    private String getAppVersionName() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    private void promptInstallApk() {
        try {
            File apkFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "kst.apk");
            if (apkFile.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                Uri apkUri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    apkUri = Uri.fromFile(apkFile);
                }
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "APK 설치 인텐트 실행 실패: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 📷 QR 코드 스캔
    // ---------------------------------------------------------------------------------------------

    private void launchQrScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("QR 코드를 스캔해 주세요");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        // 태블릿을 화면이 보이는 방향(사용자 쪽)으로 거치해두고 쓰는 경우가 많아, 뒤집지
        // 않고 바로 스캔할 수 있도록 후면 대신 전면(셀카) 카메라를 강제로 사용한다.
        options.setCameraId(1); // 0 = 후면, 1 = 전면
        qrScanLauncher.launch(options);
    }

    // 🔮 지금은 스캔 결과를 웹 페이지로 그대로 전달해주기만 한다 — QR을 찍었을 때 실제로
    // 무엇을 보여줄지(이벤트, 쿠폰, 특정 페이지 이동 등)는 아직 정해지지 않았으므로,
    // index.html 쪽에서 window.onKstQrScanned(content)를 구현하면 그 내용으로 원하는
    // 동작을 나중에 자유롭게 확장할 수 있도록 훅만 걸어둔다.
    private void onQrCodeScanned(String content) {
        Log.d(TAG, "QR 스캔 결과: " + content);

        // 🔑 관리자 종료 코드 QR — 비밀번호 입력 없이 이 QR을 스캔하면 바로 앱을 종료한다.
        if (sha256(content).equals(QR_ADMIN_EXIT_CODE_HASH)) {
            quitApp();
            return;
        }

        if (webView != null) {
            String js = "window.onKstQrScanned && window.onKstQrScanned(" + toJsStringLiteral(content) + ");";
            webView.evaluateJavascript(js, null);
        }
        Toast.makeText(this, "QR 스캔 완료", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------------------------------------
    // ⚙️ 관리자 모드 비밀번호 다이얼로그
    // ---------------------------------------------------------------------------------------------

    private void showAdminPasswordDialog() {
        try {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_admin, null);
            AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).setCancelable(true).create();
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            EditText etPassword = dialogView.findViewById(R.id.et_password);
            Button btnExit = dialogView.findViewById(R.id.btn_exit);
            TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
            TextView tvCurrentVersion = dialogView.findViewById(R.id.tv_current_version);
            TextView tvUpdateStatus = dialogView.findViewById(R.id.tv_update_status);
            Button btnUpdate = dialogView.findViewById(R.id.btn_update);
            TextView tvBatteryInfo = dialogView.findViewById(R.id.tv_battery_info);
            TextView tvEarphoneInfo = dialogView.findViewById(R.id.tv_earphone_info);

            tvCurrentVersion.setText("v" + getAppVersionName());
            tvBatteryInfo.setText(getBatteryLevel() + "%");
            tvEarphoneInfo.setText(isEarphonesPlugged() ? "연결됨" : "미연결");

            btnExit.setOnClickListener(v -> {
                if (isAdminPasswordCorrect(etPassword.getText().toString())) {
                    dialog.dismiss();
                    quitApp();
                } else {
                    Toast.makeText(this, "비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
                }
            });

            btnCancel.setOnClickListener(v -> dialog.dismiss());

            checkForAppUpdate(tvUpdateStatus, btnUpdate);

            dialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 🆕 u2mkst/app의 최신 GitHub Release와 현재 앱 버전을 비교해 업데이트 여부를 표시.
    // 다르면 "업데이트" 버튼을 노출하고, 누르면 바로 최신 apk 다운로드를 시작한다.
    private void checkForAppUpdate(TextView tvUpdateStatus, Button btnUpdate) {
        new Thread(() -> {
            String latestVersion = null;
            try {
                java.net.URL url = new java.net.URL("https://api.github.com/repos/u2mkst/app/releases/latest");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");

                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                String tagName = new org.json.JSONObject(sb.toString()).optString("tag_name", "");
                if (tagName.startsWith("v")) tagName = tagName.substring(1);
                if (!tagName.isEmpty()) latestVersion = tagName;
            } catch (Exception e) {
                Log.e(TAG, "업데이트 확인 실패: " + e.getMessage());
            }

            final String finalLatest = latestVersion;
            runOnUiThread(() -> {
                if (finalLatest == null) {
                    tvUpdateStatus.setText("확인 실패");
                    return;
                }
                if (finalLatest.equals(getAppVersionName())) {
                    tvUpdateStatus.setText("최신 버전");
                    btnUpdate.setVisibility(View.GONE);
                } else {
                    tvUpdateStatus.setVisibility(View.GONE);
                    btnUpdate.setText("v" + finalLatest + " 업데이트");
                    btnUpdate.setVisibility(View.VISIBLE);
                    btnUpdate.setOnClickListener(v -> {
                        btnUpdate.setEnabled(false);
                        btnUpdate.setText("다운로드 중...");
                        downloadApkFile("https://u2mkst.github.io/home/kst.apk?t=" + System.currentTimeMillis());
                    });
                }
            });
        }).start();
    }

    // ---------------------------------------------------------------------------------------------
    // 유틸리티 및 라이프사이클 메서드
    // ---------------------------------------------------------------------------------------------

    private void checkAndRequestHomeApp() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);

            android.content.pm.ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            String currentHomePackage = resolveInfo != null ? resolveInfo.activityInfo.packageName : "";

            if (!getPackageName().equals(currentHomePackage)) {
                new AlertDialog.Builder(this)
                        .setTitle("🏠 키오스크 홈 화면 설정")
                        .setMessage("안전한 학습 환경을 위해 이 앱을 태블릿의 '기본 홈 앱'으로 설정해야 합니다.\n\n[설정하기]를 누른 뒤, 목록에서 이 앱을 선택하고 '항상'을 눌러주세요.")
                        .setCancelable(false)
                        .setPositiveButton("설정하기", (dialog, which) -> {
                            try {
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(this, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "홈 앱 상태 확인 실패: " + e.getMessage());
        }
    }

    private void downloadApkFile(String url) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType("application/vnd.android.package-archive");
            request.setDescription("K&P 수학입시학원 키오스크 설치 파일 다운로드 중...");
            request.setTitle("kst.apk");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "kst.apk");

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "📥 APK 다운로드를 시작합니다.\n다운로드가 끝나면 설치 창이 자동으로 나타납니다.", Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "APK 다운로드 빌더 오류: " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ 다운로드 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Toast.makeText(this, "보안 규정상 캡처 및 화면 녹화를 할 수 없습니다.", Toast.LENGTH_SHORT).show();
    }

    private void showEmergencyDiagnosticScreen(Exception e) {
        try {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setBackgroundColor(Color.parseColor("#121212"));
            layout.setPadding(50, 80, 50, 50);

            TextView title = new TextView(this);
            title.setText("Error Code");
            title.setTextColor(Color.RED);
            title.setTextSize(22);
            title.setPadding(0, 0, 0, 40);
            layout.addView(title);

            TextView subtitle = new TextView(this);
            subtitle.setText("관리자에게 아래 에러코드를 알려주세요:");
            subtitle.setTextColor(Color.WHITE);
            subtitle.setTextSize(14);
            subtitle.setPadding(0, 0, 0, 40);
            layout.addView(subtitle);

            ScrollView scrollView = new ScrollView(this);
            TextView errorText = new TextView(this);
            errorText.setText(Log.getStackTraceString(e));
            errorText.setTextColor(Color.YELLOW);
            errorText.setTextSize(13);
            scrollView.addView(errorText);

            layout.addView(scrollView);
            setContentView(layout);
        } catch (Exception fatal) {
            fatal.printStackTrace();
        }
    }

    private void executeSessionClear() {
        savedU2mId = ""; savedPhone = ""; savedMathflatPw = ""; savedStudentPhone = "";
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.removeAllCookies(value -> { if (value) cookieManager.flush(); });

            WebStorage.getInstance().deleteAllData();

            runOnUiThread(() -> {
                if (webView != null) {
                    webView.clearCache(true);
                    webView.clearFormData();
                    webView.clearHistory();
                    webView.evaluateJavascript("window.localStorage.clear(); window.sessionStorage.clear();", null);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isBlacklistedUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("youtube") || lower.contains("youtu.be") || lower.contains("facebook") ||
                lower.contains("instagram") || lower.contains("blog.naver.com");
    }

    // 🔒 허용된 도메인 목록 — 반드시 "정확히 이 호스트이거나, 이 호스트의 서브도메인"일 때만
    // 통과시킨다. 예전에는 URL 문자열 전체에 이 글자들이 "포함"되기만 해도 통과였어서
    // https://공격자도메인.com/?x=u2math.co.kr 같은 URL도 내부 웹뷰(전역 JS 브릿지 노출 상태)에
    // 그대로 로드될 수 있었다.
    private static final String[] ALLOWED_HOSTS = {
            "u2mkst.github.io", "u2math.co.kr", "mathflat.com", "mathflat.co.kr"
    };

    private boolean isExternalUrl(String url) {
        if (url == null) return true;
        String host = Uri.parse(url).getHost();
        if (host == null) return true;
        host = host.toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return false;
            }
        }
        return true;
    }

    // 🔒 JS 문자열 리터럴에 값을 끼워 넣기 전, 따옴표/역슬래시 등을 이스케이프한다.
    // (org.json.JSONObject.quote()는 앞뒤 큰따옴표까지 포함한 안전한 JS 문자열을 만들어준다.)
    private static String toJsStringLiteral(String value) {
        return org.json.JSONObject.quote(value == null ? "" : value);
    }

    // 🔑 유투엠(U2M) 자동 로그인 JS 코드 주입
    private void injectOriginalU2MCode(WebView view, final String id, final String pw) {
        String jsId = toJsStringLiteral(id);
        String jsPw = toJsStringLiteral(pw);
        String jsCode = "javascript:(function() {" +
                "    var idInput = document.getElementById('input_01') || document.querySelector('input[name=\"LOGIN_ID\"]');" +
                "    var pwInput = document.getElementById('input_02') || document.querySelector('input[name=\"LOGIN_PWD\"]');" +
                "    var loginBtn = document.querySelector('.login_btn');" +
                "    if (idInput && pwInput && loginBtn && idInput.value === '') {" +
                "        idInput.value = " + jsId + ";" +
                "        pwInput.value = " + jsPw + ";" +
                "        setTimeout(function() { loginBtn.click(); }, 200);" +
                "    }" +
                "})()";
        view.evaluateJavascript(jsCode, null);
    }

    // 🔑 매스플랫(Mathflat) 자동 로그인 JS 코드 주입
    private void injectMathflatCode(WebView view, final String id, final String pw) {
        String jsId = toJsStringLiteral(id);
        String jsPw = toJsStringLiteral(pw);
        String jsCode = "javascript:(function() {" +
                "    var maxAttempts = 40;" +
                "    var attempts = 0;" +
                "    var checkExist = setInterval(function() {" +
                "        var idInput = document.querySelector('input[name=\"id\"]') || document.querySelector('input[type=\"text\"]');" +
                "        var pwInput = document.querySelector('input[name=\"password\"]') || document.querySelector('input[type=\"password\"]');" +
                "        var loginBtn = document.querySelector('button.submit-button') || document.querySelector('button[data-track=\"시작하기\"]') || document.querySelector('button[type=\"submit\"]');" +
                "        if (idInput && pwInput && loginBtn) {" +
                "            clearInterval(checkExist);" +
                "            var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
                "            if (nativeSetter) {" +
                "                nativeSetter.call(idInput, " + jsId + ");" +
                "                idInput.dispatchEvent(new Event('input', { bubbles: true }));" +
                "                nativeSetter.call(pwInput, " + jsPw + ");" +
                "                pwInput.dispatchEvent(new Event('input', { bubbles: true }));" +
                "            } else {" +
                "                idInput.value = " + jsId + ";" +
                "                pwInput.value = " + jsPw + ";" +
                "            }" +
                "            setTimeout(function() { loginBtn.click(); }, 200);" +
                "        }" +
                "        attempts++;" +
                "        if (attempts >= maxAttempts) { clearInterval(checkExist); }" +
                "    }, 150);" +
                "})()";
        view.evaluateJavascript(jsCode, null);
    }

    private void checkAndToggleHomeButton(String url) {
        if (url == null || homeFloatingButton == null) return;
        if (isHomeButtonHidden) return; // 새로고침 전까지는 사용자가 숨긴 상태를 유지

        Uri uri = Uri.parse(url);
        String path = uri.getPath();

        boolean isExactHomePage = (path != null && (path.equals("/home") || path.equals("/home/")));
        boolean shouldHideButton = url.contains("u2mkst.github.io") && isExactHomePage;

        runOnUiThread(() -> homeFloatingButton.setVisibility(shouldHideButton ? View.GONE : View.VISIBLE));
    }

    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (getWindow() != null) {
                    final WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "하단바 제어 우회: " + e.getMessage());
        }
    }

    private boolean isEarphonesPlugged() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                return audioManager.isWiredHeadsetOn() || audioManager.isBluetoothA2dpOn();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private int getBatteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 100;
    }

    // 🌉 JavaScript 연동 인터페이스
    private class AndroidBridge {
        @JavascriptInterface
        public void loginToU2M(String u2mId, String phone, String mathflatPw) { loginToU2M(u2mId, phone, mathflatPw, ""); }
        @JavascriptInterface
        public void loginToU2M(String u2mId, String phone, String mathflatPw, String studentPhone) {
            savedU2mId = u2mId; savedPhone = phone; savedMathflatPw = mathflatPw; savedStudentPhone = studentPhone;
        }
        @JavascriptInterface
        public void logoutAll() { executeSessionClear(); }

        @JavascriptInterface
        public void reportScrollTop(boolean atTop) {
            runOnUiThread(() -> {
                if (swipeRefreshLayout != null) swipeRefreshLayout.setEnabled(atTop);
            });
        }

        @JavascriptInterface
        public int getBatteryLevel() { return MainActivity.this.getBatteryLevel(); }

        @JavascriptInterface
        public boolean isEarphonesPlugged() { return MainActivity.this.isEarphonesPlugged(); }

        @JavascriptInterface
        public void checkEarphonesBeforeVideo() {
            runOnUiThread(() -> {
                if (!isEarphonesPlugged()) {
                    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (audioManager != null) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    }

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("🎧 이어폰 착용 안내")
                            .setMessage("스마트룸 에티켓을 위해 이어폰을 반드시 착용해 주세요.\n이어폰이 연결되지 않으면 영상이 재생되지 않습니다.")
                            .setCancelable(false)
                            .setPositiveButton("확인", (dialog, which) -> dialog.dismiss())
                            .show();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            if (screenOffReceiver != null) unregisterReceiver(screenOffReceiver);
            if (headsetPlugReceiver != null) unregisterReceiver(headsetPlugReceiver);
            if (batteryReceiver != null) unregisterReceiver(batteryReceiver);
            if (downloadCompleteReceiver != null) unregisterReceiver(downloadCompleteReceiver);

            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (clockRunnable != null) clockHandler.removeCallbacks(clockRunnable);
    }
}