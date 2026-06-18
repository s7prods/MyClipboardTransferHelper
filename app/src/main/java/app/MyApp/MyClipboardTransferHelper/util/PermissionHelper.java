package app.MyApp.MyClipboardTransferHelper.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import top.clspd.apps.MyClipboardTransferHelper.R;

public class PermissionHelper {

    public static final int REQUEST_CODE_ACCESS_LOCAL_NETWORK = 1;

    private PermissionHelper() {}

    @SuppressLint("InlinedApi")
    public static void promptLocalNetworkPermission(Activity activity, SharedPreferences prefs, Runnable onDone) {
        if (Build.VERSION.SDK_INT < 37 || activity.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                == PackageManager.PERMISSION_GRANTED) {
            onDone.run();
            return;
        }

        if (!prefs.getBoolean("access_local_network_dialog_shown", false)) {
            prefs.edit().putBoolean("access_local_network_dialog_shown", true).apply();
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.access_local_network_prompt_dialog_title)
                    .setMessage(R.string.access_local_network_prompt_dialog_content)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        activity.requestPermissions(
                                new String[]{android.Manifest.permission.ACCESS_LOCAL_NETWORK},
                                REQUEST_CODE_ACCESS_LOCAL_NETWORK);
                        onDone.run();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> {
                        Toast.makeText(activity, R.string.access_local_network_denied_toast_content,
                                Toast.LENGTH_SHORT).show();
                        onDone.run();
                    })
                    .show();
            return;
        }

        Toast.makeText(activity, R.string.access_local_network_denied_toast_content, Toast.LENGTH_SHORT).show();
        onDone.run();
    }
}
