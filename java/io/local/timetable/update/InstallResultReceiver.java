package io.local.timetable.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** 接收 PackageInstaller 的安装结果；需要用户确认时拉起系统安装器。 */
public class InstallResultReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_RESULT = "io.local.timetable.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    Toast.makeText(context, "无法打开系统安装器", Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(context, "系统需要确认安装，但没有返回安装界面", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "更新完成", Toast.LENGTH_LONG).show();
            return;
        }
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            message = "错误码 " + status;
        }
        Toast.makeText(context, "安装失败：" + message, Toast.LENGTH_LONG).show();
    }
}
