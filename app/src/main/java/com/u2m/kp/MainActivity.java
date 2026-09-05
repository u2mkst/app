package com.u2m.kp;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
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
import android.os.CountDownTimer;
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
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "KioskWebViewJS";
    private static final String ADMIN_PASSWORD = "kstadmin";
    private static final int MAX_SAFE_VOLUME_PERCENT = 70; // 최대 볼륨 제한 (70%)

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private Button homeFloatingButton;

    // 자동 로그인 계정 저장 변수
    private String savedU2mId = "";
    private String savedPhone = "";
    private String savedMathflatPw = "";
    private String savedStudentPhone = "";

    // ⏱️ 유휴 시간 타이머 (30분 미활동 시 경고 및 자동 로그아웃)
    private Handler idleHandler = new Handler(Looper.getMainLooper());
    private Runnable idleRunnable;
    private AlertDialog warningDialog = null;
    private CountDownTimer countDownTimer = null;

    // 시스템 브로드캐스트 리시버 및 네트워크 모니터링
    private BroadcastReceiver screenOffReceiver = null;
    private BroadcastReceiver headsetPlugReceiver = null;
    private BroadcastReceiver volumeChangeReceiver = null;
    private BroadcastReceiver batteryReceiver = null;
    private BroadcastReceiver downloadCompleteReceiver = null;
    private ConnectivityManager.NetworkCallback networkCallback = null;

    private boolean isPendingScreenOffLogout = false;
    private boolean isLowBatteryWarned = false;

    private static final long IDLE_TIMEOUT = 30 * 60 * 1000; // 30분
    private static final long WARNING_TIMEOUT = 30 * 1000;     // 30초 카운트다운

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

            if (webView == null) {
                throw new NullPointerException("❌ [XML 매칭 실패] activity_main.xml에 'webView' ID가 존재하지 않습니다.");
            }

            // 🔄 당겨서 새로고침 리스너
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setOnRefreshListener(() -> {
                    if (webView != null) {
                        webView.reload();
                    }
                });
                swipeRefreshLayout.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
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
                }

                homeFloatingButton.setVisibility(View.GONE);
                homeFloatingButton.setOnClickListener(v -> {
                    webView.loadUrl("https://u2mkst.github.io/home");
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

            String defaultUserAgent = webSettings.getUserAgentString();
            webSettings.setUserAgentString(defaultUserAgent + " Chrome/Mobile KioskApp");

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

                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    super.onReceivedError(view, request, error);

                    if (swipeRefreshLayout != null && swipeRefreshLayout.isRefreshing()) {
                        swipeRefreshLayout.setRefreshing(false);
                    }

                    if (request.isForMainFrame()) {
                        Log.w(TAG, "⚠️ 네트워크 끊김 감지: " + error.getDescription());

                        String errorImageHtml = "<!DOCTYPE html>" +
                                "<html><head><meta charset='UTF-8'>" +
                                "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>" +
                                "<style>" +
                                "  * { margin: 0; padding: 0; box-sizing: border-box; }" +
                                "  html, body { width: 100%; height: 100%; overflow: hidden; background-color: #f7f3f6; }" +
                                "  .img-container { width: 100vw; height: 100vh; display: flex; justify-content: center; align-items: center; }" +
                                "  img { width: 100%; height: 100%; object-fit: cover; cursor: pointer; }" +
                                "</style></head><body>" +
                                "  <div class='img-container'>" +
                                "    <img src='file:///android_res/drawable/wifi_error.png' onclick='location.href=\"https://u2mkst.github.io/home\"' alt='Network Error'>" +
                                "  </div>" +
                                "</body></html>";

                        view.loadDataWithBaseURL("https://u2mkst.github.io/", errorImageHtml, "text/html", "UTF-8", null);
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
            webView.loadUrl("https://u2mkst.github.io/home/login");

            // 뒤로가기 처리
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        WebBackForwardList backList = webView.copyBackForwardList();
                        int currentIndex = backList.getCurrentIndex();

                        if (currentIndex > 0) {
                            String previousUrl = backList.getItemAtIndex(currentIndex - 1).getUrl();

                            if (previousUrl != null && previousUrl.contains("u2mkst.github.io")) {
                                Uri uri = Uri.parse(previousUrl);
                                String path = uri.getPath();
                                if (path != null && (path.equals("/home") || path.equals("/home/"))) {
                                    webView.loadUrl("https://u2mkst.github.io/home");
                                    return;
                                }
                            }
                        }
                        webView.goBack();
                    } else {
                        showAdminPasswordDialog();
                    }
                }
            });

            initIdleTimer();
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
                    stopCountDown();
                    if (warningDialog != null && warningDialog.isShowing()) warningDialog.dismiss();
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

        volumeChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        int limitVol = (int) (maxVol * (MAX_SAFE_VOLUME_PERCENT / 100.0));

                        if (currentVol > limitVol) {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, limitVol, 0);
                            Toast.makeText(MainActivity.this, "🔊 청력 보호를 위해 최대 음량이 제한됩니다. (" + MAX_SAFE_VOLUME_PERCENT + "%)", Toast.LENGTH_SHORT).show();
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
        IntentFilter volumeFilter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        ContextCompat.registerReceiver(this, screenOffReceiver, screenOffFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, headsetPlugReceiver, headsetFilter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, volumeChangeReceiver, volumeFilter, ContextCompat.RECEIVER_EXPORTED);
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
                            if (webView != null && webView.getUrl() != null && webView.getUrl().contains("wifi_error.png")) {
                                Toast.makeText(MainActivity.this, "📶 인터넷이 재연결되었습니다. 페이지를 새로고칩니다.", Toast.LENGTH_SHORT).show();
                                webView.reload();
                            }
                        });
                    }
                };
                cm.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "네트워크 모니터링 등록 실패: " + e.getMessage());
        }
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
    // ⚙️ 관리자 모드 비밀번호 다이얼로그
    // ---------------------------------------------------------------------------------------------

    private void showAdminPasswordDialog() {
        try {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_admin, null);
            AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).setCancelable(true).create();
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            EditText etPassword = dialogView.findViewById(R.id.et_password);
            Button btnReset = dialogView.findViewById(R.id.btn_reset);
            Button btnExit = dialogView.findViewById(R.id.btn_exit);
            TextView btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnClearCache = dialogView.findViewById(R.id.btn_clear_cache);

            btnReset.setOnClickListener(v -> {
                if (etPassword.getText().toString().equals(ADMIN_PASSWORD)) {
                    dialog.dismiss();
                    finishAffinity();
                    startActivity(new Intent(this, MainActivity.class));
                }
            });

            if (btnClearCache != null) {
                btnClearCache.setOnClickListener(v -> {
                    if (etPassword.getText().toString().equals(ADMIN_PASSWORD)) {
                        executeSessionClear();
                        if (webView != null) webView.reload();
                        Toast.makeText(MainActivity.this, "🔄 웹 저장소 및 캐시 청소가 완료되었습니다.", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        Toast.makeText(MainActivity.this, "비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            btnExit.setText("태블릿 설정");
            btnExit.setOnClickListener(v -> {
                if (etPassword.getText().toString().equals(ADMIN_PASSWORD)) {
                    dialog.dismiss();
                    try {
                        Intent intent = new Intent(Settings.ACTION_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "설정 창을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    }
                }
            });

            btnCancel.setOnClickListener(v -> dialog.dismiss());

            showAdminDiagnosticSummary();

            dialog.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAdminDiagnosticSummary() {
        String info = "📱 앱 버전: v" + getAppVersionName() + "\n" +
                "🔋 배터리 잔량: " + getBatteryLevel() + "%\n" +
                "🎧 이어폰 상태: " + (isEarphonesPlugged() ? "연결됨" : "미연결");
        Toast.makeText(this, info, Toast.LENGTH_LONG).show();
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

    private void requestHomeAppSettingPopup() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "홈 설정 팝업 실행 실패: " + e.getMessage());
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

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdleTimer();
    }

    private void initIdleTimer() {
        idleRunnable = () -> showIdleWarningDialog();
        startIdleTimer();
    }

    private void startIdleTimer() {
        if (idleHandler != null && idleRunnable != null) {
            idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT);
        }
    }

    private void resetIdleTimer() {
        if (idleHandler != null && idleRunnable != null) {
            idleHandler.removeCallbacks(idleRunnable);
            startIdleTimer();
        }
    }

    private void showIdleWarningDialog() {
        if (isFinishing() || isDestroyed()) return;
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("사용 시간 초과 안내");
            builder.setCancelable(false);
            builder.setPositiveButton("계속 학습하기", (dialog, which) -> {
                stopCountDown();
                resetIdleTimer();
            });

            warningDialog = builder.create();
            warningDialog.show();

            countDownTimer = new CountDownTimer(WARNING_TIMEOUT, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    if (warningDialog != null && warningDialog.isShowing()) {
                        warningDialog.setMessage("30분 동안 움직임이 없어 안전을 위해\n" + (millisUntilFinished / 1000) + "초 후 자동으로 로그아웃됩니다.");
                    }
                }
                @Override
                public void onFinish() {
                    if (warningDialog != null && warningDialog.isShowing()) warningDialog.dismiss();
                    executeAutoLogout();
                }
            }.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopCountDown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    private void executeAutoLogout() {
        executeSessionClear();
        runOnUiThread(() -> {
            if (webView != null) webView.loadUrl("https://u2mkst.github.io/home");
        });
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

    private boolean isExternalUrl(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase();
        return !(lower.contains("u2mkst.github.io") || lower.contains("u2math.co.kr") ||
                lower.contains("mathflat.com") || lower.contains("litt.ly") || lower.contains("mathflat.co.kr"));
    }

    // 🔑 유투엠(U2M) 자동 로그인 JS 코드 주입
    private void injectOriginalU2MCode(WebView view, final String id, final String pw) {
        String jsCode = "javascript:(function() {" +
                "    var idInput = document.getElementById('input_01') || document.querySelector('input[name=\"LOGIN_ID\"]');" +
                "    var pwInput = document.getElementById('input_02') || document.querySelector('input[name=\"LOGIN_PWD\"]');" +
                "    var loginBtn = document.querySelector('.login_btn');" +
                "    if (idInput && pwInput && loginBtn && idInput.value === '') {" +
                "        idInput.value = '" + id + "';" +
                "        pwInput.value = '" + pw + "';" +
                "        setTimeout(function() { loginBtn.click(); }, 200);" +
                "    }" +
                "})()";
        view.evaluateJavascript(jsCode, null);
    }

    // 🔑 매스플랫(Mathflat) 자동 로그인 JS 코드 주입
    private void injectMathflatCode(WebView view, final String id, final String pw) {
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
                "                nativeSetter.call(idInput, '" + id + "');" +
                "                idInput.dispatchEvent(new Event('input', { bubbles: true }));" +
                "                nativeSetter.call(pwInput, '" + pw + "');" +
                "                pwInput.dispatchEvent(new Event('input', { bubbles: true }));" +
                "            } else {" +
                "                idInput.value = '" + id + "';" +
                "                pwInput.value = '" + pw + "';" +
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
        public void loginToMathflat(String mathflatId, String mathflatPw) { savedStudentPhone = mathflatId; savedMathflatPw = mathflatPw; }
        @JavascriptInterface
        public void logoutAll() { executeSessionClear(); }

        @JavascriptInterface
        public int getBatteryLevel() { return MainActivity.this.getBatteryLevel(); }

        @JavascriptInterface
        public String getAppVersion() { return getAppVersionName(); }

        @JavascriptInterface
        public void rebootDevice() { finishAffinity(); System.exit(0); }

        @JavascriptInterface
        public void requestHomeAppSetting() { runOnUiThread(() -> requestHomeAppSettingPopup()); }

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
            if (volumeChangeReceiver != null) unregisterReceiver(volumeChangeReceiver);
            if (batteryReceiver != null) unregisterReceiver(batteryReceiver);
            if (downloadCompleteReceiver != null) unregisterReceiver(downloadCompleteReceiver);

            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (idleHandler != null && idleRunnable != null) idleHandler.removeCallbacks(idleRunnable);
        stopCountDown();
    }
}