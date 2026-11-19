package com.neymis.web;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 42;
    private static final int REQ_STORAGE = 43;

    private static final int BG = 0xFF121212;
    private static final int PANEL = 0xFF1E1E1E;
    private static final int CARD = 0xFF262430;
    private static final int ACCENT = 0xFFB388FF;
    private static final int ACCENT_2 = 0xFF7C6CFF;
    private static final int TEXT = 0xFFE8EAF6;
    private static final int DIM = 0xFF9E9AC8;
    private static final int PINK = 0xFFFF77B5;

    private final List<Tab> tabs = new ArrayList<>();
    private int current;
    private BrowserStore store;

    private LinearLayout mainColumn;
    private FrameLayout webContainer;
    private FrameLayout fullscreenContainer;
    private LinearLayout bottomPanel;
    private EditText address;
    private TextView tabsButton;
    private TextView reloadButton;
    private ProgressBar progress;
    private boolean loading;

    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private String pendingHtml;
    private long lastBack;

    private final Tab.Callback tabCallback = new Tab.Callback() {
        @Override
        public void onTitleChanged(Tab tab, String title) {
            saveTabs();
        }

        @Override
        public void onUrlChanged(Tab tab, String url) {
            if (tab != currentTab()) return;
            if (!address.hasFocus()) address.setText(url);
        }

        @Override
        public void onProgress(Tab tab, int value) {
            if (tab != currentTab()) return;
            loading = value > 0 && value < 100;
            progress.setProgress(value);
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            reloadButton.setText(loading ? "✕" : "⟳");
            if (value >= 100) progress.setVisibility(View.GONE);
        }

        @Override
        public void onPageFinished(Tab tab) {
            if (tab != currentTab()) return;
            address.setText(tab.url);
            if (!tab.incognito) store.addHistory(tab.title, tab.url);
            saveTabs();
        }

        @Override
        public void onDownload(String url, String userAgent, String disposition, String mimeType) {
            startDownload(url, userAgent, mimeType);
        }

        @Override
        public void onFileChooser(ValueCallback<Uri[]> callback) {
            filePathCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(intent, "Выбери файл"), REQ_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                toast("Не удалось открыть выбор файла");
            }
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            customView = view;
            customCallback = callback;
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenContainer.setVisibility(View.VISIBLE);
            hideSystemUi();
        }

        @Override
        public void onHideCustomView() {
            exitCustomView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainColumn = findViewById(R.id.mainColumn);
        webContainer = findViewById(R.id.webContainer);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        bottomPanel = findViewById(R.id.bottomPanel);
        address = findViewById(R.id.address);
        tabsButton = findViewById(R.id.tabsButton);
        reloadButton = findViewById(R.id.reloadButton);
        progress = findViewById(R.id.progress);

        GradientDrawable panelShape = new GradientDrawable();
        panelShape.setColor(PANEL);
        panelShape.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        bottomPanel.setBackground(panelShape);

        try {
            progress.getProgressDrawable().setColorFilter(ACCENT_2, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {
        }

        store = new BrowserStore(this);
        WebView.setWebContentsDebuggingEnabled(true);

        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigate(address.getText().toString());
                return true;
            }
            return false;
        });
        address.setOnFocusChangeListener((v, focused) -> {
            if (focused) address.selectAll();
        });

        findViewById(R.id.backButton).setOnClickListener(v -> goBack());
        findViewById(R.id.forwardButton).setOnClickListener(v -> {
            if (currentTab().view.canGoForward()) currentTab().view.goForward();
        });
        reloadButton.setOnClickListener(v -> {
            if (loading) currentTab().stop();
            else currentTab().reload();
        });
        findViewById(R.id.homeButton).setOnClickListener(v -> navigate(store.home()));
        tabsButton.setOnClickListener(v -> showTabs());
        findViewById(R.id.menuButton).setOnClickListener(v -> showMenu());

        restoreTabs();

        Intent incoming = getIntent();
        if (incoming != null && Intent.ACTION_VIEW.equals(incoming.getAction())
                && incoming.getData() != null) {
            navigate(incoming.getDataString());
            address.setText(incoming.getDataString());
            address.clearFocus();
        }
    }

    private Tab currentTab() {
        if (tabs.isEmpty()) return null;
        return tabs.get(Math.min(current, tabs.size() - 1));
    }

    private void navigate(String text) {
        Tab tab = currentTab();
        if (tab == null) return;
        tab.load(text);
        address.setText(tab.url);
        address.clearFocus();
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void newTab(String url, boolean incognito) {
        Tab tab = new Tab(this, incognito, store.desktop(), store.eruda(), tabCallback);
        tabs.add(tab);
        switchTo(tabs.size() - 1);
        tab.load(url == null || url.isEmpty() ? store.home() : url);
        saveTabs();
    }

    private void switchTo(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab previous = currentTab();
        if (previous != null && previous.view.getParent() != null) {
            ((ViewGroup) previous.view.getParent()).removeView(previous.view);
        }
        current = index;
        Tab tab = tabs.get(current);
        if (tab.view.getParent() != null) {
            ((ViewGroup) tab.view.getParent()).removeView(tab.view);
        }
        webContainer.addView(tab.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        address.setText(tab.url);
        progress.setProgress(tab.view.getProgress());
        loading = tab.view.getProgress() > 0 && tab.view.getProgress() < 100;
        updateTabsButton();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        if (tab == currentTab() && tab.view.getParent() != null) {
            ((ViewGroup) tab.view.getParent()).removeView(tab.view);
        }
        tab.destroy();
        tabs.remove(index);
        if (tabs.isEmpty()) {
            current = 0;
            newTab(null, false);
            return;
        }
        if (current >= tabs.size()) current = tabs.size() - 1;
        switchTo(current);
        saveTabs();
    }

    private void updateTabsButton() {
        tabsButton.setText("▣ " + tabs.size());
    }

    private void goBack() {
        if (customView != null) {
            exitCustomView();
            return;
        }
        Tab tab = currentTab();
        if (tab != null && tab.view.canGoBack()) {
            tab.view.goBack();
            address.setText(tab.url);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            toast("Нажми ещё раз, чтобы выйти");
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(resultCode == RESULT_OK && data != null
                        ? new Uri[]{data.getData()} : null);
            }
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED && pendingHtml != null) {
            String html = pendingHtml;
            pendingHtml = null;
            saveHtmlLegacy(html);
        }
    }

    private void startDownload(String url, String userAgent, String mimeType) {
        try {
            String name = "neymis-download";
            try {
                String path = Uri.parse(url).getLastPathSegment();
                if (path != null && !path.isEmpty()) name = URLDecoder.decode(path, "UTF-8");
            } catch (Exception ignored) {
            }
            name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!name.contains(".")) {
                name += mimeType != null && mimeType.contains("pdf") ? ".pdf" : ".bin";
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("Neymis Web");
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                toast("Загрузка началась: " + name);
            }
        } catch (Exception e) {
            toast("Не удалось скачать: " + e.getMessage());
        }
    }

    private void showTabs() {
        Dialog dialog = new Dialog(this, R.style.FullDialogTheme);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout head = row(outer, "Вкладки · " + tabs.size(), null, true);
        head.setPadding(dp(6), dp(6), dp(6), dp(16));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            final Tab tab = tabs.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(round(CARD, 16));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(10);
            list.addView(card, cardParams);

            TextView title = new TextView(this);
            title.setText(tab.incognito ? "🕶 " + tab.title : tab.title);
            title.setTextColor(tab.incognito ? PINK : TEXT);
            title.setTextSize(15);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(title);

            TextView url = new TextView(this);
            url.setText(tab.url.isEmpty() ? "Новая вкладка" : tab.url);
            url.setTextColor(DIM);
            url.setTextSize(11);
            url.setMaxLines(1);
            url.setEllipsize(TextUtils.TruncateAt.END);
            url.setPadding(0, dp(3), dp(34), 0);
            card.addView(url);

            TextView close = new TextView(this);
            close.setText("✕");
            close.setTextColor(DIM);
            close.setTextSize(16);
            close.setPadding(dp(10), dp(4), dp(6), dp(4));
            card.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));
            close.setOnClickListener(v -> {
                dialog.dismiss();
                closeTab(index);
                showTabs();
            });

            card.setOnClickListener(v -> {
                dialog.dismiss();
                switchTo(index);
            });
        }

        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView add = action("＋ Новая вкладка", v -> {
            dialog.dismiss();
            newTab(null, false);
        });
        TextView incognito = action("🕶 Инкогнито", v -> {
            dialog.dismiss();
            newTab(null, true);
        });
        actions.addView(add, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(incognito, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        outer.addView(actions);

        dialog.setContentView(outer);
        fullWidth(dialog);
        dialog.show();
    }

    private void showMenu() {
        Dialog dialog = new Dialog(this, R.style.SheetTheme);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(PANEL, 20));
        box.setPadding(dp(16), dp(14), dp(16), dp(20));

        TextView handle = new TextView(this);
        handle.setText("━━━━━");
        handle.setTextColor(DIM);
        handle.setGravity(Gravity.CENTER);
        handle.setTextSize(12);
        box.addView(handle);

        addAction(box, "＋   Новая вкладка", v -> {
            dialog.dismiss();
            newTab(null, false);
        });
        addAction(box, "🕶   Новая вкладка инкогнито", v -> {
            dialog.dismiss();
            newTab(null, true);
        });
        addAction(box, "★   Закладки", v -> {
            dialog.dismiss();
            showEntries(true);
        });
        addAction(box, "◷   История", v -> {
            dialog.dismiss();
            showEntries(false);
        });
        addAction(box, "☆   Добавить в закладки", v -> {
            dialog.dismiss();
            Tab tab = currentTab();
            if (tab != null && !tab.url.isEmpty()) {
                store.addBookmark(tab.title, tab.url);
                toast("В закладках");
            }
        });
        addAction(box, "</>   Исходный код страницы", v -> {
            dialog.dismiss();
            showSource();
        });

        Switch erudaSwitch = addSwitch(box, "Консоль отладки (Eruda)", store.eruda());
        erudaSwitch.setOnCheckedChangeListener((button, checked) -> {
            store.setEruda(checked);
            for (Tab tab : tabs) tab.setEruda(checked);
            toast(checked ? "Eruda включена" : "Eruda выключена");
        });

        Switch desktopSwitch = addSwitch(box, "Версия для ПК", store.desktop());
        desktopSwitch.setOnCheckedChangeListener((button, checked) -> {
            store.setDesktop(checked);
            for (Tab tab : tabs) tab.applyDesktop(checked);
            Tab tab = currentTab();
            if (tab != null) tab.reload();
            toast(checked ? "Версия для ПК" : "Мобильная версия");
        });

        addAction(box, "⛶   Полноэкранный режим", v -> {
            dialog.dismiss();
            toggleImmersive();
        });
        addAction(box, "⌂   Сделать главной текущую страницу", v -> {
            dialog.dismiss();
            Tab tab = currentTab();
            if (tab != null && !tab.url.isEmpty()) {
                store.setHome(tab.url);
                toast("Главная страница изменена");
            }
        });
        addAction(box, "i   О Neymis Web", v -> {
            dialog.dismiss();
            showAbout();
        });

        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showEntries(final boolean bookmarks) {
        Dialog dialog = new Dialog(this, R.style.FullDialogTheme);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(bookmarks ? "Закладки" : "История");
        title.setTextColor(TEXT);
        title.setTextSize(20);
        title.setPadding(dp(6), dp(6), dp(6), dp(14));
        outer.addView(title);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<BrowserStore.Entry> source = bookmarks ? store.bookmarks : store.history;
        if (source.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(bookmarks ? "Закладок пока нет" : "История пуста");
            empty.setTextColor(DIM);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(48), dp(12), dp(48));
            list.addView(empty);
        }

        for (final BrowserStore.Entry entry : new ArrayList<>(source)) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.VERTICAL);
            line.setBackground(round(CARD, 14));
            line.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);

            TextView name = new TextView(this);
            name.setText(entry.title == null || entry.title.isEmpty() ? entry.url : entry.title);
            name.setTextColor(TEXT);
            name.setTextSize(14);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(name);

            TextView link = new TextView(this);
            link.setText(entry.url);
            link.setTextColor(DIM);
            link.setTextSize(11);
            link.setMaxLines(1);
            link.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(link);

            TextView remove = new TextView(this);
            remove.setText("✕");
            remove.setTextColor(DIM);
            remove.setTextSize(15);
            remove.setPadding(dp(8), dp(2), dp(4), dp(2));
            line.addView(remove, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP));
            remove.setOnClickListener(v -> {
                if (bookmarks) store.removeBookmark(entry.url);
                else store.removeHistory(entry.url);
                dialog.dismiss();
                showEntries(bookmarks);
            });

            line.setOnClickListener(v -> {
                dialog.dismiss();
                navigate(entry.url);
            });
            list.addView(line, params);
        }

        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!bookmarks && !store.history.isEmpty()) {
            addAction(outer, "Очистить историю", v -> {
                store.clearHistory();
                dialog.dismiss();
                showEntries(false);
            });
        }
        addAction(outer, "Закрыть", v -> dialog.dismiss());

        dialog.setContentView(outer);
        fullWidth(dialog);
        dialog.show();
    }

    private void showSource() {
        Tab tab = currentTab();
        if (tab == null) return;
        tab.view.evaluateJavascript("document.documentElement.outerHTML", value -> {
            String html = "";
            if (value != null) {
                try {
                    html = String.valueOf(new JSONTokener(value).nextValue());
                } catch (Exception ignored) {
                    html = value;
                }
            }
            showText("Исходный код страницы", html, true);
        });
    }

    private void showText(String titleText, String content, final boolean saveable) {
        Dialog dialog = new Dialog(this, R.style.FullDialogTheme);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(TEXT);
        title.setTextSize(18);
        title.setPadding(dp(6), dp(6), dp(6), dp(12));
        outer.addView(title);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(content.isEmpty() ? "(пусто)" : content);
        body.setTextColor(TEXT);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (saveable) {
            addAction(outer, "Сохранить в .html", v -> {
                dialog.dismiss();
                saveHtml(content);
            });
        }
        addAction(outer, "Копировать", v -> copy(content));
        addAction(outer, "Закрыть", v -> dialog.dismiss());

        dialog.setContentView(outer);
        fullWidth(dialog);
        dialog.show();
    }

    private void showAbout() {
        showText("Neymis Web 1.0",
                "Браузер на базе Android System WebView (Chromium).\n\n"
                        + "• Несколько вкладок и инкогнито\n"
                        + "• Eruda-консоль, исходный код страницы, версия для ПК\n"
                        + "• Закладки, история, скачивания, полноэкранный режим\n\n"
                        + "Открытый исходный код, без рекламы и аналитики.\n"
                        + "github.com/s93240056-boop",
                false);
    }

    private void saveHtml(String html) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put("_display_name", "neymis-web-" + System.currentTimeMillis() + ".html");
                values.put("mime_type", "text/html");
                values.put("relative_path", Environment.DIRECTORY_DOWNLOADS + "/NeymisWeb");
                Uri uri = getContentResolver().insert(
                        Uri.parse("content://media/external/downloads"), values);
                if (uri == null) throw new Exception("не удалось создать файл");
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new Exception("нет доступа к файлу");
                out.write(html.getBytes("UTF-8"));
                out.close();
                toast("Сохранено в Загрузки/NeymisWeb");
            } catch (Exception e) {
                toast("Ошибка сохранения: " + e.getMessage());
            }
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingHtml = html;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            return;
        }
        saveHtmlLegacy(html);
    }

    private void saveHtmlLegacy(String html) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "NeymisWeb");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("не удалось создать папку");
            File file = new File(dir, "neymis-web-" + System.currentTimeMillis() + ".html");
            FileOutputStream out = new FileOutputStream(file);
            out.write(html.getBytes("UTF-8"));
            out.close();
            toast("Сохранено в Загрузки/NeymisWeb");
        } catch (Exception e) {
            toast("Ошибка сохранения: " + e.getMessage());
        }
    }

    private void exitCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeAllViews();
        fullscreenContainer.setVisibility(View.GONE);
        if (customCallback != null) {
            try {
                customCallback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
        }
        customView = null;
        customCallback = null;
        showSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void toggleImmersive() {
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if ((flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) showSystemUi();
        else hideSystemUi();
    }

    private void saveTabs() {
        try {
            JSONArray array = new JSONArray();
            for (Tab tab : tabs) {
                JSONObject item = new JSONObject();
                item.put("url", tab.url);
                item.put("incognito", tab.incognito);
                array.put(item);
            }
            getSharedPreferences("web", MODE_PRIVATE).edit()
                    .putString("tabs", array.toString())
                    .putInt("current", current).apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreTabs() {
        try {
            String raw = getSharedPreferences("web", MODE_PRIVATE).getString("tabs", "");
            if (raw.isEmpty()) {
                newTab(null, false);
                return;
            }
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                newTab(item.optString("url", ""), item.optBoolean("incognito", false));
            }
            if (tabs.isEmpty()) newTab(null, false);
            int saved = getSharedPreferences("web", MODE_PRIVATE).getInt("current", 0);
            switchTo(Math.min(saved, tabs.size() - 1));
        } catch (Exception e) {
            if (tabs.isEmpty()) newTab(null, false);
        }
    }

    private TextView addAction(LinearLayout parent, String text, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(14);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setOnClickListener(click);
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private Switch addSwitch(LinearLayout parent, String text, boolean checked) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setChecked(checked);
        view.setTextColor(TEXT);
        view.setTextSize(14);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private LinearLayout row(LinearLayout parent, String text, View.OnClickListener click, boolean header) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(header ? TEXT : ACCENT);
        view.setTextSize(header ? 19 : 14);
        view.setPadding(dp(6), dp(10), dp(6), dp(10));
        if (click != null) view.setOnClickListener(click);
        parent.addView(view);
        return parent;
    }

    private TextView action(String text, View.OnClickListener click) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(ACCENT);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(CARD, 14));
        view.setPadding(dp(8), dp(14), dp(8), dp(14));
        view.setOnClickListener(click);
        return view;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void fullWidth(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("Neymis Web", text));
                toast("Скопировано");
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
