package app.MyApp.MyClipboardTransferHelper.util;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

import top.clspd.apps.MyClipboardTransferHelper.R;

public class ThemeHelper {

    private ThemeHelper() {}

    public static void refreshNightMode(Activity activity) {
        // Action bar background
        ActionBar ab = ((androidx.appcompat.app.AppCompatActivity) activity).getSupportActionBar();
        if (ab != null) {
            TypedValue surface = new TypedValue();
            activity.getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, surface, true);
            ab.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface.data));
        }
        // Window background
        View root = activity.findViewById(R.id.main);
        if (root != null) {
            TypedValue bg = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
            root.setBackgroundColor(bg.data);
            // Text colors in view tree
            refreshViewTree(activity, root);
        }
    }

    private static void refreshViewTree(Activity activity, View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            TypedValue tc = new TypedValue();
            activity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tc, true);
            tv.setTextColor(tc.data);
            if (view instanceof EditText) {
                TypedValue hc = new TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.textColorHint, hc, true);
                ((EditText) view).setHintTextColor(hc.data);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                refreshViewTree(activity, vg.getChildAt(i));
            }
        }
    }
}
