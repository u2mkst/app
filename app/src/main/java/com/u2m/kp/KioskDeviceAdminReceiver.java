package com.u2m.kp;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.annotation.NonNull;

public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "K&P 학원 관리자 권한이 활성화되었습니다.", Toast.LENGTH_SHORT).show();
    }
}