package com.rikkahub.wordlite;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 42;
    private static final String PREFS = "wordlite";
    private TextView recentText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        styleSystemBars();
        setContentView(homeView());
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) uri = (Uri) stream;
        }
        if (uri != null) openViewer(uri);
    }

    private void chooseDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        try {
            startActivityForResult(intent, REQUEST_OPEN);
        } catch (Exception ignored) {
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_OPEN);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (Exception ignored) { }
            openViewer(uri);
        }
    }

    private void openViewer(Uri uri) {
        if (uri == null) return;
        Intent intent = new Intent(this, EditorActivity.class);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private View homeView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFEAF1EF, 0xFFF6F6F3});
        scroll.setBackground(bg);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(dp(20) + bars.left, dp(20) + bars.top,
                        dp(20) + bars.right, dp(20) + bars.bottom);
                return insets;
            });
        }

        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.ic_hero);
        hero.setElevation(dp(3));
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        heroParams.topMargin = dp(14);
        heroParams.bottomMargin = dp(16);
        root.addView(hero, heroParams);

        TextView title = text("Word Lite", 26, 0xFF1E2A27);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title, matchWrap());

        Button open = actionButton("打开文档");
        open.setOnClickListener(v -> chooseDocument());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-1, dp(54));
        openParams.topMargin = dp(26);
        root.addView(open, openParams);

        TextView heading = text("最近文件", 13, 0xFF8A938F);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setLetterSpacing(0.08f);
        heading.setPadding(dp(2), dp(30), 0, dp(10));
        root.addView(heading, matchWrap());

        LinearLayout recentCard = new LinearLayout(this);
        recentCard.setOrientation(LinearLayout.HORIZONTAL);
        recentCard.setGravity(Gravity.CENTER_VERTICAL);
        recentCard.setPadding(dp(14), dp(14), dp(16), dp(14));
        recentCard.setElevation(dp(1));
        recentCard.setBackground(card(0xFFFFFFFF));
        ImageView fileIcon = new ImageView(this);
        fileIcon.setImageResource(R.drawable.ic_file);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        iconParams.rightMargin = dp(14);
        recentCard.addView(fileIcon, iconParams);

        recentText = text("", 15, 0xFF1E2A27);
        recentText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        recentText.setSingleLine(true);
        recentText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        recentText.setGravity(Gravity.CENTER_VERTICAL);
        recentText.setMinHeight(dp(28));
        recentText.setOnClickListener(v -> openRecent());
        recentCard.addView(recentText, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(recentCard, matchWrap());
        updateRecent();
        scroll.addView(root);
        return scroll;
    }

    @Override protected void onResume() {
        super.onResume();
        updateRecent();
    }

    private void updateRecent() {
        if (recentText == null) return;
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = preferences.getString("last_name", "");
        boolean available = name.length() > 0 && preferences.getString("last_uri", "").length() > 0;
        recentText.setText(available ? name : "暂无文件");
        recentText.setEnabled(available);
        recentText.setTextColor(available ? 0xFF1E2A27 : 0xFF8A938F);
    }

    private void openRecent() {
        String uri = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_uri", "");
        if (uri.length() == 0) return;
        Uri document = Uri.parse(uri);
        try (android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(document, "r")) {
            if (descriptor == null) throw new java.io.IOException("No file descriptor");
        } catch (Exception error) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("last_uri").remove("last_name").apply();
            updateRecent();
            Toast.makeText(this, "文件无法访问，请重新选择", Toast.LENGTH_SHORT).show();
            chooseDocument();
            return;
        }
        openViewer(document);
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        button.setLetterSpacing(0.03f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setElevation(dp(3));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF43958B, 0xFF2C655E});
        fill.setCornerRadius(dp(14));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, fill));
        return button;
    }

    private Drawable card(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(0xFFEAF1EF);
        window.setNavigationBarColor(0xFFF6F6F3);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    static String displayName(android.content.Context context, Uri uri) {
        if (uri == null) return "论文.docx";
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && name.length() > 0) return name;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getLastPathSegment();
        return path == null || path.length() == 0 ? "论文.docx" : path;
    }
}
